import XCTest
import KassaShared
@testable import Kassa

/// Editing an account, a card and a person through the store, read back through the same `LedgerView` the screens use. Every rule is
/// :domain's (JVM-tested); these tests check that the Swift side runs it in one transaction and stores what the screens then show.
/// The suite runs as en-US, so amounts read "$1,137.50".
final class EditTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!

    private let day: Int32 = 20522

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-edit-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        store = try LedgerStore(directory: directory, keyStore: keys)
        try store.startFresh(currency: "USD", categories: [])
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

    private func card(_ name: String, statement: String, due: String, balance: String = "", payFrom: Int64 = 0) -> AccountInput {
        return AccountInput(
            type: "CREDIT_CARD", name: name, last4: "", balance: balance, statementDay: statement, dueDay: due, limit: "", payFromId: payFrom
        )
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

    private func rowText(_ name: String) throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups where group.key != "ARCHIVED" {
            for row in group.rows where row.name == name { return row.amountText }
        }
        return nil
    }

    private func archivedNames() throws -> [String] {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        var names: [String] = []
        for group in data.groups where group.key == "ARCHIVED" {
            for row in group.rows { names.append(row.name) }
        }
        return names
    }

    private func groupKeys() throws -> [String] {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        return data.groups.map { $0.key }
    }

    // MARK: Accounts

    func testRenamingAndChangingTheBalanceMovesTheStartingBalance() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let open: AccountEditData = try ledger().accountEdit(accountId: main)
        XCTAssertEqual(open.input.balance, "1000")
        try store.updateAccount(id: main, input: account("CHECKING", "Everyday", balance: "1250.50"))
        XCTAssertEqual(try rowText("Everyday"), "$1,250.50")
        XCTAssertNil(try rowText("Main"))
    }

    func testACardKeepsItsDaysAndPayFromThroughAnEdit() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let visa = try store.addAccount(card("Visa", statement: "10", due: "28", payFrom: main))
        let open: AccountEditData = try ledger().accountEdit(accountId: visa)
        XCTAssertEqual(open.input.statementDay, "10")
        XCTAssertEqual(open.input.payFromId, main)
        try store.updateAccount(id: visa, input: card("Visa Gold", statement: "12", due: "30", balance: "200", payFrom: main))
        let after: AccountEditData = try ledger().accountEdit(accountId: visa)
        XCTAssertEqual(after.input.name, "Visa Gold")
        XCTAssertEqual(after.input.statementDay, "12")
        XCTAssertEqual(after.input.dueDay, "30")
        XCTAssertEqual(after.input.balance, "200")
        XCTAssertEqual(after.input.payFromId, main)
    }

    func testAnEditRefusesWhatTheFormRefuses() throws {
        _ = try store.addAccount(account("CHECKING", "Main"))
        let savings = try store.addAccount(account("SAVINGS", "Rainy day"))
        let taken = refusal { try store.updateAccount(id: savings, input: account("SAVINGS", "main")) }
        XCTAssertEqual(taken?.field, "NAME")
        XCTAssertEqual(taken?.message, "main is already used.")
        // Keeping its own name is fine.
        XCTAssertNil(refusal { try store.updateAccount(id: savings, input: account("SAVINGS", "RAINY DAY")) })
        // A card needs both days.
        let noDays = refusal { try store.updateAccount(id: savings, input: card("Rainy day", statement: "", due: "")) }
        XCTAssertEqual(noDays?.field, "STATEMENT_DAY")
    }

    func testACardCantBePaidFromItself() throws {
        let visa = try store.addAccount(card("Visa", statement: "10", due: "28"))
        let found = refusal { try store.updateAccount(id: visa, input: card("Visa", statement: "10", due: "28", payFrom: visa)) }
        XCTAssertEqual(found?.field, "PAY_FROM")
    }

    func testAClosedAccountRefusesANewBalanceButCanBeRenamed() throws {
        let pot = try store.addAccount(account("SAVINGS", "Old pot"))
        try store.setAccountClosed(id: pot, closedDay: day)
        let found = refusal { try store.updateAccount(id: pot, input: account("SAVINGS", "Old pot", balance: "10")) }
        XCTAssertEqual(found?.field, "BALANCE")
        XCTAssertEqual(found?.message, "Old pot is closed. Reopen it first to add to it.")
        XCTAssertNil(refusal { try store.updateAccount(id: pot, input: account("SAVINGS", "Old pot 2")) })
        let after: AccountEditData = try ledger().accountEdit(accountId: pot)
        XCTAssertTrue(after.closed)
        XCTAssertEqual(after.input.name, "Old pot 2")
    }

    func testAGoneAccountIsRefusedInAPlainSentence() throws {
        let found = refusal { try store.updateAccount(id: 999, input: account("CHECKING", "Ghost")) }
        XCTAssertEqual(found?.message, "This account is gone.")
    }

    func testArchivingTakesAnAccountOutOfTheListsAndRestoringBringsItBack() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        try store.setAccountArchived(id: main, archived: true)
        XCTAssertNil(try rowText("Main"))
        XCTAssertTrue(try groupKeys().contains("ARCHIVED"))
        XCTAssertEqual(try archivedNames(), ["Main"])
        let open: AccountEditData = try ledger().accountEdit(accountId: main)
        XCTAssertTrue(open.archived)
        try store.setAccountArchived(id: main, archived: false)
        XCTAssertEqual(try rowText("Main"), "$1,000.00")
        XCTAssertFalse(try groupKeys().contains("ARCHIVED"))
    }

    // MARK: People

    func testRenamingAPersonAndChangingWhatWasOwed() throws {
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        let open: PersonEditData = try ledger().personEdit(personId: sam, hidden: false)
        XCTAssertEqual(open.name, "Sam")
        XCTAssertTrue(open.theyOwe)
        XCTAssertEqual(open.amount, "25")
        XCTAssertTrue(open.archiveBlocked)
        try store.updatePerson(id: sam, name: "Samuel", theyOwe: false, amount: "10")
        let after: PersonEditData = try ledger().personEdit(personId: sam, hidden: false)
        XCTAssertEqual(after.name, "Samuel")
        XCTAssertFalse(after.theyOwe)
        XCTAssertEqual(after.amount, "10")
        XCTAssertEqual(try ledger().startingBalanceOf(personId: sam), -1000)
        // One starting line only.
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 1)
    }

    func testClearingWhatWasOwedRemovesTheStartingLine() throws {
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        try store.updatePerson(id: sam, name: "Sam", theyOwe: true, amount: "")
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        let after: PersonEditData = try ledger().personEdit(personId: sam, hidden: false)
        XCTAssertFalse(after.archiveBlocked)
    }

    func testAPersonsNameMustBeUniqueButKeepsItsOwn() throws {
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "")
        _ = try store.addPerson(name: "Ann", theyOwe: true, amount: "")
        let found = refusal { try store.updatePerson(id: sam, name: "ann", theyOwe: true, amount: "") }
        XCTAssertEqual(found?.field, "NAME")
        XCTAssertNil(refusal { try store.updatePerson(id: sam, name: "SAM", theyOwe: true, amount: "") })
    }

    func testOnlyASquarePersonArchivesAndRestoreAlwaysWorks() throws {
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        let blocked = refusal { try store.setPersonArchived(id: sam, archived: true) }
        XCTAssertEqual(blocked?.message, "Settle up first. Only a person with nothing open can be archived.")
        try store.updatePerson(id: sam, name: "Sam", theyOwe: true, amount: "")
        try store.setPersonArchived(id: sam, archived: true)
        XCTAssertTrue(try groupKeys().contains("ARCHIVED_PEOPLE"))
        let page: PersonPageData = try ledger().personPage(personId: sam, hidden: false, timeZoneId: "UTC")
        XCTAssertTrue(page.archived)
        try store.setPersonArchived(id: sam, archived: false)
        XCTAssertFalse(try groupKeys().contains("ARCHIVED_PEOPLE"))
    }
}
