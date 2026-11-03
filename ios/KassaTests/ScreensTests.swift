import XCTest
import KassaShared
@testable import Kassa

/// The golden backup restored into an encrypted store, read back through the shared `LedgerView` the way the app does.
enum GoldenFixture {

    /// 2026-01-01 as an epoch day: the day after the golden entries (29, 30 and 31 December 2025).
    static let today: Int32 = 20454

    static func data(_ name: String = "plain") throws -> BackupData {
        let url = try XCTUnwrap(Bundle(for: ScreensTests.self).url(forResource: name, withExtension: "kassa"), "missing \(name).kassa")
        let json = try BackupFile.open(try Data(contentsOf: url), password: name == "protected" ? "golden test password" : nil)
        return try BackupBridge.shared.parse(text: json)
    }

    static func view(directory: URL, keys: MemoryKeyStore) throws -> LedgerView {
        let store = try LedgerStore(directory: directory, keyStore: keys)
        try store.replace(with: try data())
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }
}

/// What the screens show, through the Swift API: figures from the store, hidden mode, the wording of rows, and the app model's
/// restore flow.
final class ScreensTests: XCTestCase {

    private let mask = "\u{2022}\u{2022}\u{2022}\u{2022}"
    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-screens-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Accounts

    func testNetWorthFromTheRestoredStore() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        XCTAssertEqual(view.currencyCode(), "USD")
        let accounts = view.accounts(hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        XCTAssertEqual(accounts.netWorthText, "$1,001,245.49")
        XCTAssertEqual(accounts.assetsText, "$1,001,245.49")
        XCTAssertEqual(accounts.liabilitiesText, "$0.00")
        XCTAssertTrue(accounts.netWorthTone.name == "Green")
        let keysShown: [String] = accounts.groups.map { $0.key }
        XCTAssertEqual(keysShown, ["CASH_AND_BANK", "CREDIT_CARDS", "PEOPLE", "CLOSED"])
        let cash = accounts.groups[0]
        XCTAssertEqual(cash.rows.count, 1)
        XCTAssertEqual(cash.rows[0].name, "Checking")
        XCTAssertEqual(cash.rows[0].amountText, "$1,001,245.49")
        let closed = accounts.groups[3]
        XCTAssertEqual(closed.rows[0].name, "Car loan")
        XCTAssertTrue(closed.rows[0].dueKind.name == "Closed")
    }

    func testHiddenGivesTheMaskForEveryAmount() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        let accounts = view.accounts(hidden: true, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        XCTAssertEqual(accounts.netWorthText, mask)
        XCTAssertEqual(accounts.assetsText, mask)
        XCTAssertEqual(accounts.liabilitiesText, mask)
        XCTAssertEqual(accounts.receivableText, mask)
        XCTAssertEqual(accounts.payableText, mask)
        XCTAssertTrue(accounts.netWorthTone.name == "Ink")
        for group in accounts.groups {
            if group.key != "CLOSED" {
                XCTAssertEqual(group.totalText, mask, group.key)
            }
            XCTAssertTrue(group.totalTone.name == "Ink")
            for row in group.rows {
                if !row.amountText.isEmpty {
                    XCTAssertEqual(row.amountText, mask, row.name)
                }
                XCTAssertTrue(row.amountTone.name == "Ink")
                XCTAssertTrue(row.side.name == "Hidden")
            }
        }
        let activity = view.activity(year: 2025, month: 12, hidden: true, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        XCTAssertEqual(activity.spendingText, mask)
        XCTAssertEqual(activity.incomeText, mask)
        XCTAssertTrue(activity.incomeTone.name == "Ink")
        for day in activity.days {
            XCTAssertEqual(day.spentText, mask)
            for entry in day.entries {
                XCTAssertEqual(entry.amountText, mask)
            }
        }
    }

    func testSwitchingHiddenBackShowsTheFigures() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        _ = view.accounts(hidden: true, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        let shown = view.accounts(hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        XCTAssertEqual(shown.netWorthText, "$1,001,245.49")
    }

    // MARK: Activity

    func testActivityMonthFromTheRestoredStore() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        let month = view.activity(year: 2025, month: 12, hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        XCTAssertEqual(month.spendingText, "$4.50")
        XCTAssertEqual(month.incomeText, "$999,999.99")
        XCTAssertEqual(month.entryCount, 3)
        XCTAssertFalse(month.isCurrentMonth)
        let dayNumbers: [Int32] = month.days.map { $0.epochDay }
        XCTAssertEqual(dayNumbers, [20453, 20452, 20451])
        let latte = month.days[0].entries[0]
        XCTAssertEqual(latte.note, "Latte ₹1,00,000")
        XCTAssertEqual(latte.amountText, "$4.50")
        XCTAssertEqual(month.days[1].entries[0].amountText, "+$999,999.99")
        XCTAssertTrue(view.activity(year: 2026, month: 1, hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC").isCurrentMonth)
    }

    // MARK: Wording

    func testEntryWording() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        let month = view.activity(year: 2025, month: 12, hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        let latte = Wording.entry(month.days[0].entries[0])
        XCTAssertEqual(latte, EntryWords(title: "Latte ₹1,00,000", subtitle: "Café ☕", note: "Checking"))
        let salary = Wording.entry(month.days[1].entries[0])
        XCTAssertEqual(salary.title, "Salary")
        XCTAssertEqual(salary.subtitle, "Salary")
        let transfer = Wording.entry(month.days[2].entries[0])
        XCTAssertEqual(transfer, EntryWords(title: "To Car loan", subtitle: "From Checking", note: "Loan principal"))
    }

    func testAccountWording() throws {
        let view = try GoldenFixture.view(directory: directory, keys: keys)
        let accounts = view.accounts(hidden: false, todayEpochDay: GoldenFixture.today, timeZoneId: "UTC")
        let card = accounts.groups[1].rows[0]
        XCTAssertEqual(Wording.note(card), "Nothing to pay")
        XCTAssertNil(Wording.secondLine(card))
        let closed = accounts.groups[3]
        XCTAssertEqual(Wording.groupTitle(closed), "Closed · 1")
        XCTAssertEqual(Wording.note(closed.rows[0]), "Kept in your history")
        XCTAssertNotNil(Wording.secondLine(closed.rows[0]))
        XCTAssertEqual(Wording.groupTitle(accounts.groups[0]), "Cash and bank")
    }

    func testStringsAreBundled() {
        XCTAssertEqual(String(localized: "accounts.title"), "Accounts")
        XCTAssertEqual(String(localized: "a11y.hideBalances"), "Hide balances")
        XCTAssertEqual(String(localized: "a11y.showBalances"), "Show balances")
        XCTAssertEqual(String(format: String(localized: "accounts.dueOn"), "Nov 14"), "Due Nov 14")
    }

    // MARK: Dates

    func testMonthArithmetic() {
        let back = KassaDates.shifted(year: 2026, month: 1, by: -1)
        XCTAssertEqual(back.year, 2025)
        XCTAssertEqual(back.month, 12)
        let forward = KassaDates.shifted(year: 2025, month: 12, by: 1)
        XCTAssertEqual(forward.year, 2026)
        XCTAssertEqual(forward.month, 1)
        let far = KassaDates.shifted(year: 2026, month: 3, by: -15)
        XCTAssertEqual(far.year, 2024)
        XCTAssertEqual(far.month, 12)
    }

    func testEpochDaysAreCalendarDays() {
        let parts = KassaDates.yearMonth(epochDay: 20453)
        XCTAssertEqual(parts.year, 2025)
        XCTAssertEqual(parts.month, 12)
        XCTAssertTrue(KassaDates.monthYear(year: 2025, month: 12).contains("2025"))
        XCTAssertFalse(KassaDates.shortDate(epochDay: 20453).isEmpty)
        XCTAssertFalse(KassaDates.weekdayDate(epochDay: 20453).isEmpty)
    }

    // MARK: The app model

    private func freshDefaults() -> UserDefaults {
        let name = "kassa-screens-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name) ?? UserDefaults.standard
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    private func goldenURL(_ name: String) throws -> URL {
        return try XCTUnwrap(Bundle(for: ScreensTests.self).url(forResource: name, withExtension: "kassa"), "missing \(name).kassa")
    }

    /// Runs the main run loop until [condition] holds (the model publishes on the main queue).
    private func waitFor(_ condition: @escaping () -> Bool, timeout: TimeInterval = 30) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }

    private func makeModel() -> AppModel {
        let folder = directory
        let store = keys
        return AppModel(defaults: freshDefaults(), openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
    }

    func testAnEmptyStoreAsksForABackup() {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
    }

    func testRestoringAPlainBackupOpensTheApp() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try goldenURL("plain"))
        XCTAssertTrue(waitFor { model.isReady })
        XCTAssertEqual(model.accounts?.netWorthText, "$1,001,245.49")
        XCTAssertEqual(model.hideAmounts, false)
        model.toggleHidden()
        XCTAssertTrue(waitFor { model.accounts?.netWorthText == self.mask })
        model.toggleHidden()
        XCTAssertTrue(waitFor { model.accounts?.netWorthText == "$1,001,245.49" })
    }

    func testAProtectedBackupAsksForItsPassword() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try goldenURL("protected"))
        XCTAssertTrue(waitFor { model.asksPassword })
        model.submitPassword("not the password")
        XCTAssertTrue(waitFor { model.asksPassword && model.restoreMessage == BackupError.wrongPassword.message })
        model.submitPassword("golden test password")
        XCTAssertTrue(waitFor { model.isReady })
        XCTAssertEqual(model.accounts?.netWorthText, "$1,001,245.49")
    }

    func testANewerBackupIsRefusedInPlainWords() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try goldenURL("newer"))
        let expected = "This backup is from a newer version of Kassa. Update Kassa to open it."
        XCTAssertTrue(waitFor { model.restoreMessage == expected })
        XCTAssertTrue(model.isFirstRun)
    }

    func testAFileThatIsNotABackupIsRefusedInPlainWords() throws {
        let junk = directory.appendingPathComponent("junk.kassa")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        try Data("not a backup".utf8).write(to: junk)
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(junk)
        XCTAssertTrue(waitFor { model.restoreMessage == BackupError.notABackup.message })
        XCTAssertTrue(model.isFirstRun)
    }

    func testStartFreshOpensEmptyAndIsRemembered() {
        let defaults = freshDefaults()
        let folder = directory
        let store = keys
        let model = AppModel(defaults: defaults, openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.startFresh()
        XCTAssertTrue(waitFor { model.isReady })
        XCTAssertEqual(model.accounts?.netWorthText, "$0.00")
        // A second launch finds the empty store and the choice already made.
        let again = AppModel(defaults: defaults, openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
        again.start()
        XCTAssertTrue(waitFor { again.isReady })
    }
}
