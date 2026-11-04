import XCTest
import KassaShared
@testable import Kassa

/// Batch J through the Swift API, on the demo backup: budgets (add, edit and delete in one transaction each, a refused or failed save
/// leaving every row as it was), Activity's search and filters, the week's first weekday, and the app model's read state. The figures
/// are worked out independently from the demo's entries in the JVM tests (`ReadPagesTest`); here the Swift side must run and show them.
final class ReadPagesTests: XCTestCase {

    private let mask = "\u{2022}\u{2022}\u{2022}\u{2022}"
    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!
    private let day: Int32 = DemoFixture.lastOfOctober

    private struct Boom: Error {}

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-read-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
        store = try LedgerStore(directory: directory, keyStore: keys)
        try store.replace(with: try DemoFixture.data())
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func budgetRow(_ name: String) throws -> BudgetRowData {
        let page = try ledger().budgetsPage(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        return try XCTUnwrap(page.rows.first { $0.name == name }, "no budget row " + name)
    }

    private func save(_ id: Int64, every: String, thisMonth: String = "") throws {
        let plan = try ledger().budgetPlan(categoryId: id, every: every, thisMonth: thisMonth, todayEpochDay: day)
        XCTAssertTrue(plan.problems.isEmpty)
        try store.applyBudgetChanges(plan.changes)
    }

    // MARK: Budgets

    func testDemoBudgetsAsStored() throws {
        let groceries = try budgetRow("Groceries")
        XCTAssertEqual(groceries.budgetText, "$600.00")
        XCTAssertEqual(groceries.everyText, "$600.00")
        // Dining has 250.00 every month and 300.00 for October, which is the one in force.
        let dining = try budgetRow("Dining")
        XCTAssertEqual(dining.everyText, "$250.00")
        XCTAssertEqual(dining.monthText, "$300.00")
        XCTAssertEqual(dining.budgetText, "$300.00")
        XCTAssertEqual(groceries.statusKind, "LEFT")
    }

    func testAddEditAndDeleteABudget() throws {
        let groceries = try budgetRow("Groceries")
        // Edit.
        try save(groceries.categoryId, every: "650")
        XCTAssertEqual(try budgetRow("Groceries").budgetText, "$650.00")
        // Add: a category with no budget yet.
        let page = try ledger().budgetsPage(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        let bare = try XCTUnwrap(page.rows.first { !$0.hasBudget })
        try save(bare.categoryId, every: "100")
        XCTAssertEqual(try budgetRow(bare.name).budgetText, "$100.00")
        // A blank amount removes it; so does "Remove budget".
        try save(bare.categoryId, every: "")
        XCTAssertFalse(try budgetRow(bare.name).hasBudget)
        try store.removeBudgets(categoryId: groceries.categoryId)
        XCTAssertFalse(try budgetRow("Groceries").hasBudget)
    }

    func testAFailedSaveLeavesEveryRowAsItWas() throws {
        let groceries = try budgetRow("Groceries")
        let dining = try budgetRow("Dining")
        let changes = [
            BudgetChangeData(categoryId: groceries.categoryId, month: 0, amount: 70_000, isRemoval: false),
            BudgetChangeData(categoryId: dining.categoryId, month: 0, amount: 0, isRemoval: false),
        ]
        // The second amount is not a budget: the whole save is refused, and the first row is not written.
        XCTAssertThrowsError(try store.applyBudgetChanges(changes))
        XCTAssertEqual(try budgetRow("Groceries").budgetText, "$600.00")
        // A failure after the writes rolls them back too.
        store.afterInsertHook = { throw Boom() }
        let fine = [BudgetChangeData(categoryId: groceries.categoryId, month: 0, amount: 70_000, isRemoval: false)]
        XCTAssertThrowsError(try store.applyBudgetChanges(fine))
        XCTAssertThrowsError(try store.removeBudgets(categoryId: dining.categoryId))
        store.afterInsertHook = nil
        XCTAssertEqual(try budgetRow("Groceries").budgetText, "$600.00")
        XCTAssertEqual(try budgetRow("Dining").budgetText, "$300.00")
    }

    func testBadTypedAmountsAreProblemsAndWriteNothing() throws {
        let groceries = try budgetRow("Groceries")
        let plan = try ledger().budgetPlan(categoryId: groceries.categoryId, every: "abc", thisMonth: "", todayEpochDay: day)
        XCTAssertFalse(plan.problems.isEmpty)
        XCTAssertEqual(plan.problems.first?.field, "EVERY_MONTH")
        XCTAssertTrue(plan.changes.isEmpty)
    }

    func testHiddenBudgetsGiveNothingAway() throws {
        let page = try ledger().budgetsPage(hidden: true, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(page.totalText, mask)
        for row in page.rows {
            XCTAssertFalse(row.over)
            XCTAssertEqual(row.statusKind, "")
            XCTAssertEqual(row.barPermille, 0)
        }
    }

    // MARK: Activity search and filters

    func testSearchEscapesWhatIsTyped() throws {
        let view = try ledger()
        XCTAssertEqual(view.activitySearch(query: "rent", hidden: false, timeZoneId: "UTC").count, 13)
        XCTAssertEqual(view.activitySearch(query: "45.00", hidden: false, timeZoneId: "UTC").count, 12)
        // A percent sign, an underscore and a backslash are text, not patterns.
        XCTAssertEqual(view.activitySearch(query: "%", hidden: false, timeZoneId: "UTC").count, 0)
        XCTAssertEqual(view.activitySearch(query: "_", hidden: false, timeZoneId: "UTC").count, 0)
        XCTAssertEqual(view.activitySearch(query: "\\", hidden: false, timeZoneId: "UTC").count, 0)
        let masked = view.activitySearch(query: "rent", hidden: true, timeZoneId: "UTC")
        XCTAssertEqual(masked.count, 13)
        if masked.hasSpent { XCTAssertEqual(masked.spentText, mask) }
    }

    func testFiltersKeepOnlyTheirEntries() throws {
        let view = try ledger()
        func count(_ filter: String) -> Int {
            let page = view.activityFiltered(year: 2026, month: 10, filter: filter, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
            var total = 0
            for item in page.days { total += item.entries.count }
            return total
        }
        XCTAssertEqual(count("ALL"), 27)
        XCTAssertEqual(count("EXPENSE"), 20)
        XCTAssertEqual(count("INCOME"), 3)
        // The totals stay whatever the filter.
        let all = view.activityFiltered(year: 2026, month: 10, filter: "ALL", hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        let only = view.activityFiltered(year: 2026, month: 10, filter: "INCOME", hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(all.spendingText, only.spendingText)
        XCTAssertEqual(ActivityBridge.shared.toggledFilter(current: "EXPENSE", tapped: "EXPENSE"), "ALL")
        XCTAssertEqual(ActivityBridge.shared.toggledFilter(current: "ALL", tapped: "INCOME"), "INCOME")
    }

    func testMonthPickerDisablesLaterMonths() {
        let pick = ActivityBridge.shared.monthPick(year: 2026, latestYear: 2026, latestMonth: 10)
        XCTAssertEqual(pick.cells.count, 12)
        XCTAssertTrue(pick.cells[9].enabled)
        XCTAssertFalse(pick.cells[10].enabled)
        XCTAssertFalse(pick.canNextYear)
        XCTAssertTrue(pick.canPreviousYear)
    }

    // MARK: The week

    func testTheWeekStartsOnThePhonesFirstWeekday() throws {
        let view = try ledger()
        // Thursday 2026-10-22. Calendar.firstWeekday 1 is Sunday, 2 is Monday.
        let thursday: Int32 = 20748
        let sunday = view.weekView(hidden: false, todayEpochDay: thursday, timeZoneId: "UTC", firstWeekday: 1)
        let monday = view.weekView(hidden: false, todayEpochDay: thursday, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertEqual(sunday.startDay, 20744)
        XCTAssertEqual(monday.startDay, 20745)
        XCTAssertEqual(sunday.totalText, "$163")
        XCTAssertEqual(sunday.compareText, "$194")
        XCTAssertEqual(monday.totalText, "$157")
        XCTAssertEqual(monday.compareText, "$145")
        XCTAssertEqual(sunday.days.count, 7)
        XCTAssertEqual(sunday.days.first?.weekday, 7)
        XCTAssertEqual(monday.days.first?.weekday, 1)
    }

    func testHiddenPatternsAndWeekAreMasked() throws {
        let view = try ledger()
        let patterns = view.patterns(hidden: true, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(patterns.tiles.count, 6)
        for tile in patterns.tiles {
            XCTAssertTrue(tile.bars.isEmpty)
            XCTAssertFalse(tile.attention)
        }
        let week = view.weekView(hidden: true, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 1)
        XCTAssertEqual(week.totalText, mask)
        for line in week.lines {
            XCTAssertFalse(line.over)
            XCTAssertEqual(line.barPermille, 0)
        }
    }

    func testPatternsHaveTheSixTiles() throws {
        let patterns = try ledger().patterns(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        let kinds: [String] = patterns.tiles.map { $0.kind }
        XCTAssertEqual(kinds, ["BIG", "STORE", "SMALL", "CREEP", "REPEAT", "BUSIEST"])
    }

    // MARK: The app model

    private func freshDefaults() -> UserDefaults {
        let name = "kassa-read-" + UUID().uuidString
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

    func testModelSearchFilterPagesAndBudgets() throws {
        let folder = directory.appendingPathComponent("model", isDirectory: true)
        let opened = keys
        let model = AppModel(defaults: freshDefaults(), openStore: { try LedgerStore(directory: folder, keyStore: opened!) })
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try DemoFixture.url())
        XCTAssertTrue(waitFor { model.isReady && model.activity != nil && model.patterns != nil })
        // Filter: a tap filters, the same tap shows everything.
        model.tapFilter("EXPENSE")
        XCTAssertEqual(model.activityFilter, "EXPENSE")
        model.tapFilter("EXPENSE")
        XCTAssertEqual(model.activityFilter, "ALL")
        // Search.
        model.setSearch("rent")
        XCTAssertTrue(waitFor { model.searchResult?.count == 13 })
        model.setSearch("")
        XCTAssertTrue(waitFor { model.searchResult == nil })
        // A month after the current one is refused.
        let year = model.shownYear
        model.pickMonth(year: year + 1, month: 1)
        XCTAssertEqual(model.shownYear, year)
        // The read stack opens, moves and closes.
        model.openRead(ReadRoute.detail("REPEATING"))
        XCTAssertTrue(waitFor { model.readPage?.detail != nil })
        model.openRead(ReadRoute.spending(month: true, income: false, back: 0))
        XCTAssertTrue(waitFor { model.readPage?.spending != nil })
        model.closeRead()
        XCTAssertTrue(waitFor { model.readPage?.detail != nil })
        model.closeRead()
        XCTAssertTrue(model.readStack.isEmpty)
        // A budget saved through the model.
        model.openBudgets()
        XCTAssertTrue(waitFor { model.budgets != nil })
        let row = try XCTUnwrap(model.budgets?.rows.first { $0.name == "Groceries" })
        var outcome: WriteOutcome?
        model.saveBudget(categoryId: row.categoryId, every: "700", thisMonth: "") { result in outcome = result }
        XCTAssertTrue(waitFor { outcome != nil })
        XCTAssertEqual(outcome, WriteOutcome.saved(row.categoryId))
        XCTAssertTrue(waitFor { model.budgets?.rows.first { $0.name == "Groceries" }?.everyText == "$700.00" })
        // A bad amount is refused under its field.
        var refused: WriteOutcome?
        model.saveBudget(categoryId: row.categoryId, every: "abc", thisMonth: "") { result in refused = result }
        XCTAssertTrue(waitFor { refused != nil })
        if case .refused(let field, _) = refused! {
            XCTAssertEqual(field, "EVERY_MONTH")
        } else {
            XCTFail("expected a refusal")
        }
    }
}
