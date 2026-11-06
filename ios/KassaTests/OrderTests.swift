import XCTest
import KassaShared
@testable import Kassa

/// Batch R through the store and the phone's own preferences: the arrangement of Accounts (kept in the store, so a backup carries it),
/// the sort and the picked Overview tiles (UserDefaults), the budget month's first day and Account to person. Every rule is :domain's
/// (JVM-tested); these tests check the Swift side keeps and reads them.
final class OrderTests: XCTestCase {

    private var directory: URL!
    private var store: LedgerStore!
    private var suite: String!
    private var defaults: UserDefaults!

    private let day: Int32 = 20522

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-order-" + UUID().uuidString, isDirectory: true)
        store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
        try store.startFresh(currency: "USD", categories: [])
        suite = "kassa-order-" + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDownWithError() throws {
        store = nil
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func account(_ type: String, _ name: String, balance: String = "") -> AccountInput {
        return AccountInput(type: type, name: name, last4: "", balance: balance, statementDay: "", dueDay: "", limit: "", payFromId: 0)
    }

    private func card(_ name: String) -> AccountInput {
        return AccountInput(type: "CREDIT_CARD", name: name, last4: "", balance: "", statementDay: "10", dueDay: "28", limit: "", payFromId: 0)
    }

    /// The ledger as AppModel makes it: the store, then the phone's own choices.
    private func ledger() throws -> LedgerView {
        let made = LedgerView(data: try store.export(), fallbackCurrency: nil)
        let prefs = PhonePrefs(defaults: defaults)
        made.accountSort = prefs.accountSort
        made.pinnedKeys = prefs.pins
        return made
    }

    private func accounts() throws -> AccountsData {
        return try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
    }

    private func names(_ key: String) throws -> [String] {
        let data = try accounts()
        for group in data.groups where group.key == key { return group.rows.map { $0.name } }
        return []
    }

    private func seed() throws {
        _ = try store.addAccount(account("CHECKING", "Main", balance: "100"))
        _ = try store.addAccount(account("SAVINGS", "Rainy day", balance: "900"))
        _ = try store.addAccount(card("Visa"))
    }

    // MARK: Order

    func testWithNothingChosenTheTabIsAToZ() throws {
        try seed()
        let data = try accounts()
        XCTAssertEqual(data.sort, "NAME")
        XCTAssertEqual(data.arrangeKeys, ["CASH_AND_BANK", "CREDIT_CARDS"])
        XCTAssertEqual(try names("CASH_AND_BANK"), ["Main", "Rainy day"])
    }

    func testBalanceOrdersLargestFirstAndIsRemembered() throws {
        try seed()
        let prefs = PhonePrefs(defaults: defaults)
        prefs.accountSort = AccountOrderBridge.shared.sortOf(stored: "BALANCE")
        XCTAssertEqual(try names("CASH_AND_BANK"), ["Rainy day", "Main"])
        XCTAssertEqual(try accounts().sort, "BALANCE")
    }

    func testAMovedOrderIsSavedInTheStoreAndShownAsYourOrder() throws {
        try seed()
        let shown = try accounts().order
        let moved = AccountOrderBridge.shared.moveGroup(shown: shown, key: "CREDIT_CARDS", delta: -1)
        try store.setAccountArrangement(moved)
        PhonePrefs(defaults: defaults).accountSort = "CUSTOM"
        let data = try accounts()
        XCTAssertEqual(data.sort, "CUSTOM")
        XCTAssertEqual(data.arrangeKeys, ["CREDIT_CARDS", "CASH_AND_BANK"])
        XCTAssertEqual(try store.accountArrangementText(), moved)
    }

    func testADropGivesTheNewOrderAndAnAccountStaysInItsGroup() throws {
        try seed()
        let shown = try accounts().order
        let dropped = AccountOrderBridge.shared.dropAccount(shown: shown, groupKey: "CASH_AND_BANK", from: 0, destination: 2)
        try store.setAccountArrangement(dropped)
        PhonePrefs(defaults: defaults).accountSort = "CUSTOM"
        XCTAssertEqual(try names("CASH_AND_BANK"), ["Rainy day", "Main"])
        XCTAssertEqual(try names("CREDIT_CARDS"), ["Visa"])
    }

    func testPickingASortDropsTheArrangement() throws {
        try seed()
        try store.setAccountArrangement(AccountOrderBridge.shared.moveGroup(shown: try accounts().order, key: "CREDIT_CARDS", delta: -1))
        PhonePrefs(defaults: defaults).accountSort = "CUSTOM"
        XCTAssertEqual(try accounts().arrangeKeys.first, "CREDIT_CARDS")
        // What AppModel.chooseSort does.
        PhonePrefs(defaults: defaults).accountSort = "NAME"
        try store.setAccountArrangement(nil)
        XCTAssertNil(try store.accountArrangementText())
        XCTAssertEqual(try accounts().arrangeKeys.first, "CASH_AND_BANK")
        XCTAssertEqual(try accounts().sort, "NAME")
    }

    func testAnArrangementFromABackupShowsAsYourOrderWithNoStoredSort() throws {
        try seed()
        try store.setAccountArrangement("CREDIT_CARDS,CASH_AND_BANK|")
        XCTAssertEqual(PhonePrefs(defaults: defaults).accountSort, "")
        XCTAssertEqual(try accounts().sort, "CUSTOM")
        XCTAssertEqual(try accounts().arrangeKeys, ["CREDIT_CARDS", "CASH_AND_BANK"])
    }

    func testARestoreForgetsTheSortAndThePickedTiles() throws {
        let prefs = PhonePrefs(defaults: defaults)
        prefs.accountSort = "BALANCE"
        prefs.pins = "acct:1"
        prefs.theme = "DARK"
        DueSettings(defaults: defaults).forgetAccountReferences()
        XCTAssertEqual(prefs.accountSort, "")
        XCTAssertNil(prefs.pins)
        // The look of the app is not about accounts.
        XCTAssertEqual(prefs.theme, "DARK")
    }

    // MARK: Folds

    func testFoldsStartWithTheClosedGroupsAndAreRemembered() throws {
        let prefs = PhonePrefs(defaults: defaults)
        XCTAssertEqual(Set(prefs.folded), ["CLOSED", "ARCHIVED", "ARCHIVED_PEOPLE"])
        prefs.folded = ["LOANS"]
        XCTAssertEqual(PhonePrefs(defaults: defaults).folded, ["LOANS"])
        prefs.folded = []
        XCTAssertEqual(PhonePrefs(defaults: defaults).folded, [])
    }

    // MARK: Pinned tiles

    func testTilesFollowThePicksAndNothingPickedIsNone() throws {
        try seed()
        XCTAssertEqual(try ledger().overview(hidden: false, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 1).pins.count, 3)
        let prefs = PhonePrefs(defaults: defaults)
        let made = try ledger()
        prefs.pins = made.pinToggled(key: "acct:3")
        let after = try ledger()
        XCTAssertEqual(after.pinChoices().filter { $0.picked }.count, 2)
        XCTAssertEqual(after.overview(hidden: false, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 1).pins.count, 2)
        prefs.pins = ""
        XCTAssertEqual(try ledger().overview(hidden: false, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 1).pins.count, 0)
    }

    // MARK: Phone choices

    func testThemeAndHomeTabFallBackToTheDefaults() throws {
        let prefs = PhonePrefs(defaults: defaults)
        XCTAssertEqual(prefs.theme, "SYSTEM")
        XCTAssertEqual(prefs.homeTab, "TODAY")
        prefs.theme = "LIGHT"
        prefs.homeTab = "ACCOUNTS"
        XCTAssertEqual(prefs.theme, "LIGHT")
        XCTAssertEqual(PhonePrefs.shellTab(prefs.homeTab), .accounts)
        prefs.homeTab = "SETTINGS"
        XCTAssertEqual(PhonePrefs.shellTab(prefs.homeTab), .overview)
        XCTAssertEqual(KassaApp.scheme("DARK"), .dark)
        XCTAssertNil(KassaApp.scheme("SYSTEM"))
    }

    func testTheBudgetMonthStartIsKeptToOneToTwentyEight() throws {
        try store.setMonthStartDay(15)
        XCTAssertEqual(try ledger().budgetMonthStart(), 15)
        try store.setMonthStartDay(40)
        XCTAssertEqual(try ledger().budgetMonthStart(), 28)
        try store.setMonthStartDay(0)
        XCTAssertEqual(try ledger().budgetMonthStart(), 1)
    }

    // MARK: Account to person

    func testAnAccountBecomesAPersonWithTheSameBalanceAndIsArchived() throws {
        let tab = try store.addAccount(account("CHECKING", "Old tab", balance: "-25"))
        let personId = try store.convertAccountToPerson(accountId: tab, name: "  Sam ")
        XCTAssertGreaterThan(personId, 0)
        let data = try accounts()
        XCTAssertEqual(data.payableText, "$25.00")
        XCTAssertTrue(data.groups.contains { $0.key == "ARCHIVED" })
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 1)
        XCTAssertEqual(try ledger().convertibleAccounts(hidden: false).count, 0)
    }

    func testAnAccountWithNothingInItLeavesNoLine() throws {
        let tab = try store.addAccount(account("CASH", "Wallet"))
        try store.convertAccountToPerson(accountId: tab, name: "Ria")
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try store.rowCount(table: "people"), 1)
    }

    func testTheConversionRefusesATakenNameACardAndAGoneAccount() throws {
        _ = try store.addPerson(name: "Sam", theyOwe: true, amount: "")
        let tab = try store.addAccount(account("CHECKING", "Old tab"))
        let visa = try store.addAccount(card("Visa"))
        var taken: WriteRefusal?
        do { try store.convertAccountToPerson(accountId: tab, name: "sam") } catch let found as WriteRefusal { taken = found }
        XCTAssertEqual(taken?.message, "sam is already used.")
        var card: WriteRefusal?
        do { try store.convertAccountToPerson(accountId: visa, name: "Lee") } catch let found as WriteRefusal { card = found }
        XCTAssertNotNil(card)
        var gone: WriteRefusal?
        do { try store.convertAccountToPerson(accountId: 999, name: "Lee") } catch let found as WriteRefusal { gone = found }
        XCTAssertNotNil(gone)
        // Nothing was written by the refused ones.
        XCTAssertEqual(try store.rowCount(table: "people"), 1)
    }
}
