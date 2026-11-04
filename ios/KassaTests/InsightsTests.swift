import XCTest
import KassaShared
@testable import Kassa

/// Insights through the Swift API: the demo backup's October (276,329 cents of spending in `expected.json`), hidden mode, the
/// category page, and the app model's month and category state. The category splits are worked out independently in the JVM
/// tests (`DemoBackupTest`); here the Overview, which has its own code path, is the cross-check.
final class InsightsTests: XCTestCase {

    private let mask = "\u{2022}\u{2022}\u{2022}\u{2022}"
    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-insights-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func freshDefaults() -> UserDefaults {
        let name = "kassa-insights-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name) ?? UserDefaults.standard
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    private func waitFor(_ condition: @escaping () -> Bool, timeout: TimeInterval = 60) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }

    func testOctoberFromTheDemoBackup() throws {
        let expected = try DemoFixture.expected()
        XCTAssertEqual(expected.octoberSpending, 276_329)
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let october = view.insights(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0)
        XCTAssertEqual(october.year, 2026)
        XCTAssertEqual(october.month, 10)
        XCTAssertTrue(october.isCurrentMonth)
        XCTAssertTrue(october.tone.name == "Ink")
        XCTAssertEqual(october.totalText, "$2,763.29")
        let names: [String] = october.lines.map { $0.name }
        XCTAssertEqual(Array(names.prefix(3)), ["Home", "Groceries", "Dining"])
        // The same top three as Overview.
        let overview = view.overview(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        let overviewAmounts: [String] = overview.spending.top.map { $0.amountText }
        let amounts: [String] = october.lines.prefix(3).map { $0.amountText }
        XCTAssertEqual(amounts, overviewAmounts)
        XCTAssertEqual(october.lines[0].barPermille, 1000)
        XCTAssertTrue(october.lines[0].percent > 0)
    }

    func testOverviewWeekMatchesTheSpendingPageOfThatWeek() throws {
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let today: Int32 = DemoFixture.lastOfOctober
        for first: Int32 in [1, 2, 7] {
            let overview = view.overview(hidden: false, todayEpochDay: today, timeZoneId: "UTC", firstWeekday: first)
            let page = view.spendingPage(hidden: false, todayEpochDay: today, timeZoneId: "UTC", firstWeekday: first, month: false, income: false, back: 0)
            XCTAssertEqual(overview.weekSpending.totalText, page.totalText)
            XCTAssertEqual(overview.weekStartDay, page.fromDay)
            let income = view.spendingPage(hidden: false, todayEpochDay: today, timeZoneId: "UTC", firstWeekday: first, month: false, income: true, back: 0)
            XCTAssertEqual(overview.weekIncome.totalText, income.totalText)
        }
    }

    func testHiddenMasksEverything() throws {
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let october = view.insights(hidden: true, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0)
        XCTAssertEqual(october.totalText, mask)
        XCTAssertTrue(october.tone.name == "Ink")
        XCTAssertFalse(october.lines.isEmpty)
        for line in october.lines {
            XCTAssertEqual(line.amountText, mask)
            XCTAssertEqual(line.percent, -1)
            XCTAssertEqual(line.barPermille, 0)
        }
        let page = view.categoryPage(
            hidden: true, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0, categoryId: october.lines[0].id
        )
        XCTAssertEqual(page.totalText, mask)
        for day in page.days {
            XCTAssertEqual(day.spentText, mask)
            for entry in day.entries {
                XCTAssertEqual(entry.amountText, mask)
            }
        }
    }

    func testCategoryPageTotalEqualsItsLine() throws {
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let october = view.insights(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0)
        let first = october.lines[1]
        let page = view.categoryPage(
            hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0, categoryId: first.id
        )
        XCTAssertTrue(page.found)
        XCTAssertEqual(page.name, first.name)
        XCTAssertEqual(page.totalText, first.amountText)
        var count = 0
        for day in page.days { count += day.entries.count }
        XCTAssertEqual(Int(page.entryCount), count)
        XCTAssertTrue(count > 0)
    }

    func testModelMovesMonthsAndOpensACategory() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try DemoFixture.url())
        XCTAssertTrue(waitFor { model.isReady && model.insights != nil })
        XCTAssertEqual(model.insightsBack, 0)
        let currentMonth = model.insights?.month
        // Nothing is later than the current month.
        model.shiftInsights(1)
        XCTAssertEqual(model.insightsBack, 0)
        model.shiftInsights(-1)
        XCTAssertEqual(model.insightsBack, 1)
        XCTAssertTrue(waitFor { model.insights?.isCurrentMonth == false })
        XCTAssertNotEqual(model.insights?.month, currentMonth)
        model.openInsightsCategory(0)
        XCTAssertTrue(waitFor { model.categoryPage != nil })
        XCTAssertEqual(model.categoryPage?.id, 0)
        model.closeInsightsCategory()
        XCTAssertNil(model.openCategoryId)
        XCTAssertNil(model.categoryPage)
        model.setHidden(true)
        XCTAssertTrue(waitFor { model.insights?.totalText == self.mask })
    }

    private func makeModel() -> AppModel {
        let folder = directory
        let store = keys
        return AppModel(defaults: freshDefaults(), openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
    }
}
