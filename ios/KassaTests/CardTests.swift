import XCTest
import KassaShared
@testable import Kassa

/// Paying a card, money between you and a person, and closing an account, through the store: every rule is :domain's (JVM-tested,
/// against the demo backup); these tests check the Swift side runs it in one transaction and that the screens' figures follow.
/// The suite runs as en-US, so amounts read "$1,137.50".
final class CardTests: XCTestCase {

    private var directory: URL!
    private var store: LedgerStore!

    /// 2026-03-10, at noon UTC.
    private let day: Int32 = 20522
    private let millis: Int64 = 20522 * 86_400_000 + 43_200_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-card-" + UUID().uuidString, isDirectory: true)
        store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
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

    private func card(_ name: String) -> AccountInput {
        return AccountInput(type: "CREDIT_CARD", name: name, last4: "", balance: "", statementDay: "5", dueDay: "25", limit: "", payFromId: 0)
    }

    private func transfer(cents: Int64, from: Int64, to: Int64) -> EntryInput {
        return EntryInput(
            id: 0, kind: "TRANSFER", amount: cents, accountId: from, toAccountId: to, categoryId: 0, epochMillis: millis,
            note: CardPaymentBridge.shared.note()
        )
    }

    private func payment(
        id: Int64 = 0, person: Int64, kind: String, cents: Int64, account: Int64
    ) -> PersonPaymentInput {
        return PersonPaymentInput(
            id: id, personId: person, kind: kind, amount: cents, accountId: account, epochMillis: millis, note: ""
        )
    }

    private func rowText(_ name: String) throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == name { return row.amountText }
        }
        return nil
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

    // MARK: Pay card

    func testPayingACardMovesMoneyFromTheAccountToTheCard() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let visa = try store.addAccount(card("Visa"))
        _ = try store.addEntry(EntryInput(
            id: 0, kind: "EXPENSE", amount: 20_000, accountId: visa, toAccountId: 0, categoryId: 0, epochMillis: millis, note: "Shoes"
        ))
        let before = try ledger().accountPage(accountId: visa, hidden: false, todayEpochDay: day, timeZoneId: "UTC", monthsBack: 0)
        XCTAssertEqual(before.balanceText, "$200.00")
        _ = try store.addEntry(transfer(cents: 7_500, from: main, to: visa))
        XCTAssertEqual(try rowText("Main"), "$925.00")
        let after = try ledger().accountPage(accountId: visa, hidden: false, todayEpochDay: day, timeZoneId: "UTC", monthsBack: 0)
        XCTAssertEqual(after.balanceText, "$125.00")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 2)
    }

    func testPayCardNeedsAnAccountAndAnAmount() throws {
        XCTAssertNotNil(CardPaymentBridge.shared.problem(cents: 5_000, payFromId: 0, currency: "USD"))
        XCTAssertNotNil(CardPaymentBridge.shared.problem(cents: 0, payFromId: 1, currency: "USD"))
        XCTAssertNil(CardPaymentBridge.shared.problem(cents: 5_000, payFromId: 1, currency: "USD"))
    }

    func testAClosedAccountCannotPayACard() throws {
        let main = try store.addAccount(account("CHECKING", "Main"))
        let visa = try store.addAccount(card("Visa"))
        try store.setAccountClosed(id: main, closedDay: day)
        let refused = refusal { _ = try store.addEntry(transfer(cents: 1_000, from: main, to: visa)) }
        XCTAssertEqual(refused?.message, "Main is closed. Reopen it first to add to it.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testAFailedCardPaymentLeavesNothingBehind() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "100"))
        let visa = try store.addAccount(card("Visa"))
        struct Boom: Error {}
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.addEntry(transfer(cents: 1_000, from: main, to: visa)))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try rowText("Main"), "$100.00")
    }

    // MARK: Person payments

    func testAPersonPaymentSettlesTheBalanceAndCanBeEditedAndDeleted() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "500"))
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        let id = try store.addPersonPayment(payment(person: sam, kind: "PERSON_IN", cents: 1_000, account: main))
        XCTAssertEqual(try rowText("Main"), "$510.00")
        XCTAssertEqual(try ledger().personPage(personId: sam, hidden: false, timeZoneId: "UTC").balanceText, "$15.00")
        try store.updatePersonPayment(payment(id: id, person: sam, kind: "PERSON_IN", cents: 2_500, account: main))
        XCTAssertEqual(try rowText("Main"), "$525.00")
        XCTAssertEqual(try ledger().personPage(personId: sam, hidden: false, timeZoneId: "UTC").side.name, "Settled")
        try store.deletePersonPayment(id: id)
        XCTAssertEqual(try rowText("Main"), "$500.00")
        XCTAssertEqual(try ledger().personPage(personId: sam, hidden: false, timeZoneId: "UTC").balanceText, "$25.00")
    }

    func testAPersonPaymentNeedsAnOpenAccountAndAnAmount() throws {
        let main = try store.addAccount(account("CHECKING", "Main"))
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "25")
        XCTAssertNotNil(refusal { _ = try store.addPersonPayment(payment(person: sam, kind: "PERSON_IN", cents: 0, account: main)) })
        XCTAssertNotNil(refusal { _ = try store.addPersonPayment(payment(person: sam, kind: "PERSON_IN", cents: 100, account: 0)) })
        try store.setAccountClosed(id: main, closedDay: day)
        let refused = refusal { _ = try store.addPersonPayment(payment(person: sam, kind: "PERSON_IN", cents: 100, account: main)) }
        XCTAssertEqual(refused?.message, "Main is closed. Reopen it first to add to it.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    // MARK: Close and reopen

    func testCloseNeedsAZeroBalanceAndReopenUndoesIt() throws {
        let main = try store.addAccount(account("CHECKING", "Main", balance: "10"))
        let spare = try store.addAccount(account("CASH", "Spare"))
        XCTAssertNotNil(refusal { try store.setAccountClosed(id: main, closedDay: day) })
        try store.setAccountClosed(id: spare, closedDay: day)
        XCTAssertEqual(try ledger().accountPage(accountId: spare, hidden: false, todayEpochDay: day, timeZoneId: "UTC", monthsBack: 0).closedDay, day)
        try store.setAccountClosed(id: spare, closedDay: 0)
        XCTAssertEqual(try ledger().accountPage(accountId: spare, hidden: false, todayEpochDay: day, timeZoneId: "UTC", monthsBack: 0).closedDay, 0)
    }
}
