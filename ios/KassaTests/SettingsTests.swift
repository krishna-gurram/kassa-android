import XCTest
import KassaShared
@testable import Kassa

/// The demo backup (`app/src/test/resources/backup-demo`) and the figures `expected.json` worked out from its entries alone.
enum DemoFixture {

    struct Expected {
        let today: Int32
        let octoberSpending: Int
        let octoberIncome: Int
    }

    /// 2026-10-31: the last day of the month whose totals `expected.json` lists (its own "today" is 3 November).
    static let lastOfOctober: Int32 = 20757

    private static func bundle() -> Bundle {
        return Bundle(for: SettingsTests.self)
    }

    static func url() throws -> URL {
        return try XCTUnwrap(bundle().url(forResource: "demo", withExtension: "kassa"), "missing demo.kassa")
    }

    static func data() throws -> BackupData {
        let json = try BackupFile.open(try Data(contentsOf: try url()), password: nil)
        return try BackupBridge.shared.parse(text: json)
    }

    static func expected() throws -> Expected {
        let file = try XCTUnwrap(bundle().url(forResource: "expected", withExtension: "json"), "missing expected.json")
        let object = try JSONSerialization.jsonObject(with: try Data(contentsOf: file))
        let root = try XCTUnwrap(object as? [String: Any])
        let today = try XCTUnwrap(root["todayEpochDay"] as? Int)
        let months = try XCTUnwrap(root["months"] as? [String: Any])
        let october = try XCTUnwrap(months["2026-10"] as? [String: Any])
        let spending = try XCTUnwrap(october["spending"] as? Int)
        let income = try XCTUnwrap(october["income"] as? Int)
        return Expected(today: Int32(today), octoberSpending: spending, octoberIncome: income)
    }

    static func view(directory: URL, keys: MemoryKeyStore) throws -> LedgerView {
        let store = try LedgerStore(directory: directory, keyStore: keys)
        try store.replace(with: try data())
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }
}

/// Settings and Overview: Hide balances that is remembered, the Overview figures against the demo backup, backing up and restoring
/// through the model, the currency, the notices and the wording of failures.
final class SettingsTests: XCTestCase {

    private let mask = "\u{2022}\u{2022}\u{2022}\u{2022}"
    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-settings-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func freshDefaults() -> UserDefaults {
        let name = "kassa-settings-" + UUID().uuidString
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

    private func makeModel(defaults: UserDefaults? = nil) -> AppModel {
        let folder = directory
        let store = keys
        let used = defaults ?? freshDefaults()
        return AppModel(defaults: used, openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
    }

    private func goldenURL(_ name: String) throws -> URL {
        return try XCTUnwrap(Bundle(for: SettingsTests.self).url(forResource: name, withExtension: "kassa"), "missing \(name).kassa")
    }

    /// A model that has restored the demo backup and is on its tabs.
    private func demoModel() throws -> AppModel {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try DemoFixture.url())
        XCTAssertTrue(waitFor { model.isReady && model.overview != nil })
        return model
    }

    private func digest(_ data: BackupData) -> String {
        return BackupBridge.shared.digest(data: data)
    }

    // MARK: Hide balances

    func testHideBalancesIsRemembered() {
        let defaults = freshDefaults()
        let first = AppModel(defaults: defaults, openStore: { throw KeyUnreadable() })
        XCTAssertFalse(first.hideAmounts)
        first.setHidden(true)
        XCTAssertTrue(first.hideAmounts)
        XCTAssertTrue(defaults.bool(forKey: AppModel.hideKey))
        // A new launch reads it back.
        let second = AppModel(defaults: defaults, openStore: { throw KeyUnreadable() })
        XCTAssertTrue(second.hideAmounts)
        second.toggleHidden()
        XCTAssertFalse(second.hideAmounts)
        XCTAssertFalse(defaults.bool(forKey: AppModel.hideKey))
        let third = AppModel(defaults: defaults, openStore: { throw KeyUnreadable() })
        XCTAssertFalse(third.hideAmounts)
    }

    func testHidingMasksEveryTabsFigures() throws {
        let model = try demoModel()
        model.setHidden(true)
        XCTAssertTrue(waitFor { model.overview?.spending.totalText == self.mask && model.accounts?.netWorthText == self.mask })
        XCTAssertEqual(model.activity?.spendingText, mask)
        model.setHidden(false)
        XCTAssertTrue(waitFor { model.overview?.spending.totalText != self.mask && model.accounts?.netWorthText != self.mask })
    }

    // MARK: Overview figures

    func testOverviewMonthAgainstTheDemoBackup() throws {
        let expected = try DemoFixture.expected()
        XCTAssertEqual(expected.octoberSpending, 276_329)
        XCTAssertEqual(expected.octoberIncome, 520_286)
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let october = view.overview(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertEqual(october.year, 2026)
        XCTAssertEqual(october.month, 10)
        XCTAssertEqual(october.spending.totalText, "$2,763.29")
        XCTAssertEqual(october.income.totalText, "$5,202.86")
        XCTAssertTrue(october.income.tone.name == "Green")
        XCTAssertTrue(october.spending.tone.name == "Ink")
        let names: [String] = october.spending.top.map { $0.name }
        XCTAssertEqual(names, ["Home", "Groceries", "Dining"])
        let amounts: [String] = october.spending.top.map { $0.amountText }
        XCTAssertEqual(amounts, ["$1,800.00", "$443.20", "$192.23"])
        // The same month through Activity, which has its own code path.
        let activity = view.activity(year: 2026, month: 10, hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC")
        XCTAssertEqual(activity.spendingText, october.spending.totalText)
        XCTAssertEqual(activity.incomeText, october.income.totalText)
    }

    func testOverviewOnTheDemoDayIsTheCurrentMonthToDate() throws {
        let expected = try DemoFixture.expected()
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let now = view.overview(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertEqual(now.month, 11)
        XCTAssertEqual(now.spending.totalText, "$1,815.99")
        XCTAssertEqual(now.income.totalText, "$2,600.00")
    }

    func testOverviewPinsFollowAccountsOrder() throws {
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let overview = view.overview(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        let names: [String] = overview.pins.map { $0.name }
        XCTAssertEqual(names, ["Brokerage", "Car loan", "Everyday checking", "Rainy day savings", "Travel card", "Wallet", ""])
        XCTAssertTrue(overview.pins[6].isPeople)
        XCTAssertLessThanOrEqual(overview.pins.count, 8)
        // The amounts are the Accounts tab's own.
        let accounts = view.accounts(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC")
        let rows: [AccountRowData] = accounts.groups.flatMap { $0.rows }
        let checking = rows.first { $0.name == "Everyday checking" }
        let pinned = overview.pins.first { $0.name == "Everyday checking" }
        XCTAssertNotNil(checking)
        XCTAssertEqual(pinned?.amountText, checking?.amountText)
    }

    func testOverviewHiddenIsMaskedEverywhere() throws {
        let view = try DemoFixture.view(directory: directory, keys: keys)
        let overview = view.overview(hidden: true, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertEqual(overview.spending.totalText, mask)
        XCTAssertEqual(overview.income.totalText, mask)
        XCTAssertTrue(overview.income.tone.name == "Ink")
        for item in overview.spending.top + overview.income.top {
            XCTAssertEqual(item.amountText, mask)
        }
        for pin in overview.pins {
            XCTAssertEqual(pin.amountText, mask)
            XCTAssertTrue(pin.amountTone.name == "Ink")
        }
    }

    func testOverviewThroughTheModelFollowsTheSwitch() throws {
        let model = try demoModel()
        XCTAssertEqual(model.overview?.pins.count, 7)
        XCTAssertEqual(model.currency, "USD")
        model.toggleHidden()
        XCTAssertTrue(waitFor { model.overview?.spending.totalText == self.mask })
    }

    // MARK: Back up and restore

    func testBackupMadeFromTheModelOpensAndEqualsTheStore() throws {
        let model = try demoModel()
        model.makeBackup(password: nil)
        XCTAssertTrue(waitFor { model.pendingExport != nil })
        let made = try XCTUnwrap(model.pendingExport)
        XCTAssertTrue(made.name.hasPrefix("Kassa_"))
        XCTAssertTrue(made.name.hasSuffix(".kassa"))
        let json = try BackupFile.open(made.bytes, password: nil)
        let reread = try BackupBridge.shared.parse(text: json)
        XCTAssertEqual(digest(reread), digest(try DemoFixture.data()))
        XCTAssertFalse(model.working)
    }

    func testAProtectedBackupNeedsItsPassword() throws {
        let model = try demoModel()
        model.makeBackup(password: "a long enough secret")
        XCTAssertTrue(waitFor { model.pendingExport != nil })
        let made = try XCTUnwrap(model.pendingExport)
        XCTAssertThrowsError(try BackupFile.open(made.bytes, password: nil)) { error in
            XCTAssertEqual(error as? BackupError, BackupError.needsPassword)
        }
        XCTAssertThrowsError(try BackupFile.open(made.bytes, password: "wrong")) { error in
            XCTAssertEqual(error as? BackupError, BackupError.wrongPassword)
        }
        let json = try BackupFile.open(made.bytes, password: "a long enough secret")
        let reread = try BackupBridge.shared.parse(text: json)
        XCTAssertEqual(digest(reread), digest(try DemoFixture.data()))
    }

    func testExportingAndRestoringRoundTripsThroughTwoPhones() throws {
        let model = try demoModel()
        model.makeBackup(password: "round trip")
        XCTAssertTrue(waitFor { model.pendingExport != nil })
        let made = try XCTUnwrap(model.pendingExport)
        let file = directory.appendingPathComponent(made.name)
        try made.bytes.write(to: file)
        // A second phone: its own empty store.
        let other = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-settings-other-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: other) }
        let otherKeys = MemoryKeyStore()
        let second = AppModel(defaults: freshDefaults(), openStore: { try LedgerStore(directory: other, keyStore: otherKeys) })
        second.start()
        XCTAssertTrue(waitFor { second.isFirstRun })
        second.chooseBackup(file)
        XCTAssertTrue(waitFor { second.asksPassword })
        second.submitPassword("round trip")
        XCTAssertTrue(waitFor { second.isReady && second.accounts != nil })
        XCTAssertEqual(second.accounts?.netWorthText, model.accounts?.netWorthText)
        XCTAssertEqual(second.accounts?.groups.count, model.accounts?.groups.count)
    }

    func testRestoreOverExistingDataAsksFirstAndCanBeRefused() throws {
        let model = try demoModel()
        let before = model.accounts?.netWorthText
        XCTAssertNotNil(before)
        model.chooseBackup(try goldenURL("plain"), replacing: true)
        XCTAssertTrue(waitFor { model.asksReplace != nil })
        XCTAssertFalse((model.asksReplace ?? "").isEmpty)
        // Nothing changed yet, and saying no keeps it so.
        XCTAssertEqual(model.accounts?.netWorthText, before)
        model.cancelReplace()
        XCTAssertNil(model.asksReplace)
        XCTAssertTrue(waitFor { !model.working })
        XCTAssertEqual(model.accounts?.netWorthText, before)
    }

    func testRestoreOverExistingDataReplacesAfterConfirming() throws {
        let model = try demoModel()
        model.chooseBackup(try goldenURL("plain"), replacing: true)
        XCTAssertTrue(waitFor { model.asksReplace != nil })
        model.confirmReplaceNow()
        XCTAssertTrue(waitFor { model.accounts?.netWorthText == "$1,001,245.49" })
        XCTAssertTrue(waitFor { model.notice == String(localized: "settings.restored") })
        XCTAssertTrue(model.isReady)
        // The store itself was replaced, not just the figures: a new read of it shows the golden backup.
        let rows: Int = model.queue.sync { (try? model.store?.rowCount(table: "transactions")) ?? -1 }
        XCTAssertEqual(rows, 3)
    }

    func testAWrongBackupFileFromSettingsLeavesTheDataAndSaysWhy() throws {
        let model = try demoModel()
        let before = model.accounts?.netWorthText
        let junk = directory.appendingPathComponent("junk.kassa")
        try Data("not a backup".utf8).write(to: junk)
        model.chooseBackup(junk, replacing: true)
        XCTAssertTrue(waitFor { model.restoreMessage == BackupError.notABackup.message })
        XCTAssertNil(model.asksReplace)
        XCTAssertEqual(model.accounts?.netWorthText, before)
    }

    // MARK: Currency

    func testChangingTheCurrencyRelabelsWithoutConverting() throws {
        let model = try demoModel()
        XCTAssertEqual(model.currency, "USD")
        XCTAssertEqual(model.currencyName, "US dollar")
        model.changeCurrency("EUR")
        XCTAssertTrue(waitFor { model.currency == "EUR" })
        // Net worth is 2,789,861 minor units: the same number, a different symbol.
        XCTAssertTrue(waitFor { model.accounts?.netWorthText == "\u{20AC}27,898.61" })
        let saved: String? = model.queue.sync { (try? model.store?.export())?.currency }
        XCTAssertEqual(saved, "EUR")
    }

    // MARK: Files and words

    func testBackupFileNameIsAndroidsPattern() {
        let name = BackupNames.fileName(for: Date(timeIntervalSince1970: 0), zone: KassaDates.utc)
        XCTAssertEqual(name, "Kassa_19700101_000000.kassa")
    }

    func testThirdPartyNoticesAreBundled() {
        XCTAssertEqual(Licences.all.map { $0.file }, ["apache-2.0", "sqlcipher"])
        let sqlcipher = Licences.all[1]
        let text = Licences.text(for: sqlcipher)
        XCTAssertTrue(text.contains("ZETETIC LLC"))
        XCTAssertTrue(text.contains("Redistributions of source code must retain the above copyright"))
        XCTAssertTrue(Licences.text(for: Licences.all[0]).contains("Apache License"))
    }

    func testFailuresAreWordedNeverRaw() {
        let full = NSError(domain: NSCocoaErrorDomain, code: 640, userInfo: [NSLocalizedDescriptionKey: "secret/path/raw text"])
        XCTAssertEqual(Failures.writeBackup(full), "Not enough space on this phone. Free some space and try again.")
        let gone = NSError(domain: NSCocoaErrorDomain, code: 513, userInfo: nil)
        XCTAssertEqual(Failures.writeBackup(gone), "Kassa can't reach that folder or file any more. Pick it again.")
        let odd = NSError(domain: "Elsewhere", code: 7, userInfo: [NSLocalizedDescriptionKey: "secret/path/raw text"])
        XCTAssertEqual(Failures.writeBackup(odd), "The backup couldn't be saved. Try again.")
        XCTAssertEqual(Failures.writeBackup(BackupWriteError.keyFailed), "The backup couldn't be saved. Try again.")
        let denied = NSError(domain: NSCocoaErrorDomain, code: 257, userInfo: nil)
        XCTAssertEqual(Failures.readFile(denied), "That file couldn't be opened. Try picking it again.")
        XCTAssertEqual(Failures.readFile(odd), "Couldn't open that file.")
        XCTAssertFalse(Failures.readFile(odd).contains("secret"))
    }
}
