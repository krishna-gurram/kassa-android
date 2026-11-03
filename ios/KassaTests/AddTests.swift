import XCTest
import KassaShared
@testable import Kassa

/// The write path through the store: start fresh, accounts, and expense, income and transfer entries, read back through the same
/// `LedgerView` the screens use. Every rule is :domain's (JVM-tested); these tests check that the Swift side runs it in one
/// transaction and that what it stores is what the screens then show. The suite runs as en-US, so amounts read "$1,137.50".
final class AddTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!

    /// 2026-03-10, at noon UTC.
    private let day: Int32 = 20522
    private let millis: Int64 = 20522 * 86_400_000 + 43_200_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-add-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        store = try LedgerStore(directory: directory, keyStore: keys)
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func account(_ type: String, _ name: String, balance: String = "") -> AccountInput {
        return AccountInput(type: type, name: name, last4: "", balance: balance, statementDay: "", dueDay: "", limit: "", payFromId: 0)
    }

    private func entry(
        id: Int64 = 0, kind: String, cents: Int64, from: Int64, to: Int64 = 0, category: Int64 = 0, note: String = ""
    ) -> EntryInput {
        return EntryInput(
            id: id, kind: kind, amount: cents, accountId: from, toAccountId: to, categoryId: category, epochMillis: millis, note: note
        )
    }

    private func categoryId(_ name: String, kind: String) throws -> Int64 {
        let found: [CategoryChoiceData] = try ledger().categoryChoices(kind: kind, query: "")
        return try XCTUnwrap(found.first { $0.name == name }, "no category " + name).id
    }

    private func rowText(_ name: String) throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == name { return row.amountText }
        }
        return nil
    }

    private func activity() throws -> ActivityData {
        return try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
    }

    private func refusal(_ body: () throws -> Void) -> WriteRefusal? {
        do {
            try body()
        } catch let found as WriteRefusal {
            return found
        } catch {
            return nil
        }
        return nil
    }

    /// A store with a currency, two starter categories and two accounts.
    private func seeded() throws -> (main: Int64, savings: Int64, groceries: Int64, salary: Int64) {
        try store.startFresh(currency: "USD", categories: ["Groceries", "Salary"])
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let savings = try store.addAccount(account("SAVINGS", "Rainy day"))
        return (main, savings, try categoryId("Groceries", kind: "EXPENSE"), try categoryId("Salary", kind: "INCOME"))
    }

    // MARK: Start fresh

    func testStartFreshSetsCurrencyAndTheTickedStarterCategories() throws {
        XCTAssertNil(try store.currencyCode())
        try store.startFresh(currency: "USD", categories: ["Groceries", "Salary"])
        XCTAssertEqual(try store.currencyCode(), "USD")
        XCTAssertEqual(try store.rowCount(table: "categories"), 2)
        let spending: [CategoryChoiceData] = try ledger().categoryChoices(kind: "EXPENSE", query: "")
        let income: [CategoryChoiceData] = try ledger().categoryChoices(kind: "INCOME", query: "")
        XCTAssertEqual(spending.map { $0.name }, ["Groceries"])
        XCTAssertEqual(income.map { $0.name }, ["Salary"])
        XCTAssertEqual(try store.rowCount(table: "accounts"), 0)
    }

    func testStartFreshWithTheDefaultsMakesAndroidsStarters() throws {
        let starters: [StarterData] = StartBridge.shared.starters()
        let names: [String] = starters.filter { $0.on }.map { $0.name }
        try store.startFresh(currency: "USD", categories: names)
        XCTAssertEqual(try store.rowCount(table: "categories"), names.count)
        XCTAssertGreaterThan(names.count, 10)
    }

    func testStartFreshWithNoCategoriesMakesNone() throws {
        try store.startFresh(currency: "USD", categories: [])
        XCTAssertEqual(try store.rowCount(table: "categories"), 0)
    }

    // MARK: Accounts

    func testAddedAccountsShowTheirOpeningBalances() throws {
        _ = try seeded()
        XCTAssertEqual(try rowText("Main"), "$1,000.00")
        XCTAssertEqual(try rowText("Rainy day"), "$0.00")
        XCTAssertEqual(try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC").netWorthText, "$1,000.00")
    }

    func testAccountRulesComeFromTheDomain() throws {
        _ = try seeded()
        let duplicate = refusal { _ = try store.addAccount(account("CHECKING", "main")) }
        XCTAssertNotNil(duplicate)
        // A card needs its statement and due days.
        let card = refusal { _ = try store.addAccount(account("CREDIT_CARD", "Visa")) }
        XCTAssertNotNil(card)
        XCTAssertEqual(try store.rowCount(table: "accounts"), 2)
    }

    func testAddedPersonStartsWithWhatIsOwed() throws {
        try store.startFresh(currency: "USD", categories: [])
        _ = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        XCTAssertEqual(try store.rowCount(table: "people"), 1)
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 1)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertNotNil(refusal { _ = try store.addPerson(name: "sam", theyOwe: true, amount: "") })
    }

    // MARK: Entries

    func testExpenseIncomeAndTransferMoveBalancesAndActivityTotals() throws {
        let ids = try seeded()
        _ = try store.addEntry(entry(kind: "EXPENSE", cents: 1250, from: ids.main, category: ids.groceries, note: "Milk"))
        _ = try store.addEntry(entry(kind: "INCOME", cents: 20000, from: ids.main, category: ids.salary))
        _ = try store.addEntry(entry(kind: "TRANSFER", cents: 5000, from: ids.main, to: ids.savings))
        XCTAssertEqual(try rowText("Main"), "$1,137.50")
        XCTAssertEqual(try rowText("Rainy day"), "$50.00")
        XCTAssertEqual(try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC").netWorthText, "$1,187.50")
        let month = try activity()
        XCTAssertEqual(month.spendingText, "$12.50")
        XCTAssertEqual(month.incomeText, "$200.00")
        XCTAssertEqual(Int(month.entryCount), 3)
    }

    func testClosedAccountTakesNothingNew() throws {
        _ = try seeded()
        let old = try store.addAccount(account("SAVINGS", "Old"))
        try store.db.exec("UPDATE accounts SET closedDay = 20000 WHERE id = \(old)")
        let spending = try categoryId("Groceries", kind: "EXPENSE")
        let found = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 500, from: old, category: spending)) }
        XCTAssertEqual(found?.message, "Old is closed. Reopen it first to add to it.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testBoundsAndMissingInputsAreRefusedWithTheirField() throws {
        let ids = try seeded()
        let zero = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 0, from: ids.main, category: ids.groceries)) }
        XCTAssertEqual(zero?.field, "AMOUNT")
        let huge = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 10_000_000_000, from: ids.main, category: ids.groceries)) }
        XCTAssertEqual(huge?.field, "AMOUNT")
        let long = String(repeating: "x", count: 500)
        let note = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 100, from: ids.main, category: ids.groceries, note: long)) }
        XCTAssertEqual(note?.field, "NOTE")
        let noCategory = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 100, from: ids.main)) }
        XCTAssertEqual(noCategory?.message, "Pick a category.")
        let noAccount = refusal { _ = try store.addEntry(entry(kind: "EXPENSE", cents: 100, from: 0, category: ids.groceries)) }
        XCTAssertEqual(noAccount?.message, "Pick an account.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testUpdateChangesTheEntryAndDeleteRemovesIt() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(kind: "EXPENSE", cents: 1250, from: ids.main, category: ids.groceries, note: "Milk"))
        try store.updateEntry(entry(id: id, kind: "EXPENSE", cents: 2000, from: ids.main, category: ids.groceries, note: "Milk and eggs"))
        XCTAssertEqual(try rowText("Main"), "$980.00")
        XCTAssertEqual(try activity().spendingText, "$20.00")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 1)
        // The kind can change too: the same entry becomes income.
        try store.updateEntry(entry(id: id, kind: "INCOME", cents: 2000, from: ids.main, category: ids.salary, note: "Refund"))
        XCTAssertEqual(try rowText("Main"), "$1,020.00")
        try store.deleteEntry(id: id)
        XCTAssertEqual(try rowText("Main"), "$1,000.00")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertNotNil(refusal { try store.deleteEntry(id: id) })
        XCTAssertNotNil(refusal { try store.updateEntry(entry(id: id, kind: "EXPENSE", cents: 100, from: ids.main, category: ids.groceries)) })
    }

    func testFailureInsideTheTransactionRollsEverythingBack() throws {
        let ids = try seeded()
        struct Boom: Error {}
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.addEntry(entry(kind: "EXPENSE", cents: 1250, from: ids.main, category: ids.groceries)))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try rowText("Main"), "$1,000.00")
        // The store still works afterwards.
        _ = try store.addEntry(entry(kind: "EXPENSE", cents: 1250, from: ids.main, category: ids.groceries))
        XCTAssertEqual(try rowText("Main"), "$987.50")
    }

    func testOnlyPlainEntriesAreEditableOnTheRow() throws {
        let ids = try seeded()
        _ = try store.addEntry(entry(kind: "EXPENSE", cents: 1250, from: ids.main, category: ids.groceries))
        let month = try activity()
        let rows: [EntryRowData] = month.days.flatMap { $0.entries }
        XCTAssertEqual(rows.count, 1)
        XCTAssertTrue(rows[0].editable)
        XCTAssertNotNil(try ledger().entryEdit(id: rows[0].id))
    }
}
