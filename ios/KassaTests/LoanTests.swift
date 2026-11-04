import XCTest
import KassaShared
@testable import Kassa

/// Loan setup and balance corrections through the store (stage G1): one transaction for the account, its terms and the shared
/// categories, the loan page's figures read back through `LedgerView`, and the correction entries. The rules are :domain's (JVM-tested);
/// these check that the Swift side runs them in one transaction and stores what the screens then show. The suite runs as en-US.
final class LoanTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!

    /// 2026-03-10, at noon UTC.
    private let day: Int32 = 20522
    private let millis: Int64 = 20522 * 86_400_000 + 43_200_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-loan-" + UUID().uuidString, isDirectory: true)
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

    /// A new loan of 30,000.00 at 6% over 60 months starting on [day] (payment 579.98), with an optional escrow.
    private func newLoan(escrow: String = "") -> LoanInput {
        let base: LoanInput = LoanBridge.shared.starting(todayEpochDay: day, payFromId: 0)
        return LoanInput(
            stage: base.stage, locked: base.locked, kind: base.kind, contract: base.contract, amount: "30000", rate: "6",
            termMonths: "60", firstDay: base.firstDay, balance: base.balance, payment: base.payment, nextDay: base.nextDay,
            paymentsLeft: base.paymentsLeft, original: base.original, startDay: base.startDay, dueDay: base.dueDay,
            payFromId: base.payFromId, escrow: escrow, fee: base.fee, earlyCharge: base.earlyCharge,
            interestCategoryId: base.interestCategoryId, basis: base.basis
        )
    }

    private func page(_ id: Int64) throws -> LoanPageData {
        return try ledger().loanPage(accountId: id, hidden: false, todayEpochDay: day, timeZoneId: "UTC", monthsBack: 0, allRows: false)
    }

    private func rowText(_ name: String) throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == name { return row.amountText }
        }
        return nil
    }

    private func rowNote(_ name: String) throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == name { return row.noteAmountText }
        }
        return nil
    }

    private func categoryNames() throws -> [String] {
        let data = try store.export()
        return data.categories.map { $0.name }
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

    private func fresh() throws {
        try store.startFresh(currency: "USD", categories: ["Groceries"])
    }

    // MARK: Setup

    func testSetupSavesTheAccountItsTermsAndTheInterestCategoryTogether() throws {
        try fresh()
        let id = try store.addLoan(account: account("LOAN", "House"), loan: newLoan())
        let data = try store.export()
        XCTAssertEqual(data.loanTerms.count, 1)
        XCTAssertEqual(data.loanTerms[0].accountId, id)
        XCTAssertEqual(data.loanTerms[0].payment, 57_998)
        XCTAssertEqual(data.loanTerms[0].rate, 60_000)
        XCTAssertEqual(data.loanTerms[0].type, "REDUCING")
        XCTAssertEqual(try categoryNames().filter { $0 == "Loan interest" }.count, 1)
        XCTAssertFalse(try categoryNames().contains("Escrow"))
        let shown = try page(id)
        XCTAssertTrue(shown.hasTerms)
        XCTAssertEqual(shown.balanceText, "$30,000.00")
        XCTAssertEqual(shown.paymentText, "$579.98")
        // A loan with a payment due shows that payment as its row figure and Outstanding under the name.
        XCTAssertEqual(try rowText("House"), "$579.98")
        XCTAssertEqual(try rowNote("House"), "$30,000.00")
    }

    func testEscrowMakesTheEscrowCategoryToo() throws {
        try fresh()
        _ = try store.addLoan(account: account("LOAN", "House"), loan: newLoan(escrow: "50"))
        XCTAssertEqual(try categoryNames().filter { $0 == "Escrow" }.count, 1)
        XCTAssertEqual(try categoryNames().filter { $0 == "Loan interest" }.count, 1)
    }

    func testAnExistingInterestCategoryIsKeptNotMadeTwice() throws {
        try store.startFresh(currency: "USD", categories: ["Loan interest", "Groceries"])
        _ = try store.addLoan(account: account("LOAN", "House"), loan: newLoan())
        _ = try store.addLoan(account: account("LOAN", "Boat"), loan: newLoan())
        XCTAssertEqual(try categoryNames().filter { $0 == "Loan interest" }.count, 1)
        XCTAssertEqual(try store.rowCount(table: "loan_terms"), 2)
    }

    func testAFailureInTheMiddleLeavesNothingBehind() throws {
        try fresh()
        struct Boom: Error {}
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.addLoan(account: account("LOAN", "House"), loan: newLoan()))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "accounts"), 0)
        XCTAssertEqual(try store.rowCount(table: "loan_terms"), 0)
        XCTAssertEqual(try categoryNames(), ["Groceries"])
        // The store still works afterwards.
        _ = try store.addLoan(account: account("LOAN", "House"), loan: newLoan())
        XCTAssertEqual(try store.rowCount(table: "loan_terms"), 1)
    }

    func testARefusalIsSaidAndSavesNothing() throws {
        try fresh()
        let noName = refusal { _ = try store.addLoan(account: account("LOAN", ""), loan: newLoan()) }
        XCTAssertEqual(noName?.field, "NAME")
        let bad = LoanBridge.shared.starting(todayEpochDay: day, payFromId: 0)
        let noAmount = refusal { _ = try store.addLoan(account: account("LOAN", "House"), loan: bad) }
        XCTAssertEqual(noAmount?.field, "AMOUNT")
        XCTAssertEqual(try store.rowCount(table: "accounts"), 0)
        XCTAssertEqual(try store.rowCount(table: "loan_terms"), 0)
    }

    func testTermsForAnExistingLoanKeepItsBalanceAndComeOnlyOnce() throws {
        try fresh()
        let id = try store.addAccount(account("LOAN", "Old loan", balance: "5000"))
        XCTAssertFalse(try ledger().hasLoanTerms(accountId: id))
        let start: LoanInput = try ledger().loanTermsInput(accountId: id, todayEpochDay: day)
        XCTAssertEqual(start.stage, "PARTWAY")
        XCTAssertTrue(start.locked)
        let filled = LoanInput(
            stage: start.stage, locked: start.locked, kind: start.kind, contract: start.contract, amount: start.amount, rate: "5",
            termMonths: start.termMonths, firstDay: start.firstDay, balance: start.balance, payment: "250",
            nextDay: start.nextDay, paymentsLeft: start.paymentsLeft, original: start.original, startDay: start.startDay,
            dueDay: start.dueDay, payFromId: start.payFromId, escrow: start.escrow, fee: start.fee, earlyCharge: start.earlyCharge,
            interestCategoryId: start.interestCategoryId, basis: start.basis
        )
        try store.addLoanTerms(accountId: id, loan: filled)
        XCTAssertTrue(try ledger().hasLoanTerms(accountId: id))
        XCTAssertEqual(try page(id).balanceText, "$5,000.00")
        XCTAssertEqual(try page(id).paymentText, "$250.00")
        XCTAssertEqual(try categoryNames().filter { $0 == "Loan interest" }.count, 1)
        let again = refusal { try store.addLoanTerms(accountId: id, loan: filled) }
        XCTAssertEqual(again?.message, "This loan already has terms.")
        XCTAssertEqual(try store.rowCount(table: "loan_terms"), 1)
    }

    // MARK: Balance corrections

    func testACorrectionMovesTheBalanceAndIsStoredAsText() throws {
        try fresh()
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let up = try store.correctBalance(accountId: main, typedShown: 120_000, epochMillis: millis, editingId: 0)
        XCTAssertEqual(try rowText("Main"), "$1,200.00")
        var data = try store.export()
        var row = try XCTUnwrap(data.transactions.first { $0.id == up })
        XCTAssertEqual(row.kind, "ADJUST_IN")
        XCTAssertEqual(row.amount, 20_000)
        let down = try store.correctBalance(accountId: main, typedShown: 90_000, epochMillis: millis, editingId: 0)
        XCTAssertEqual(try rowText("Main"), "$900.00")
        data = try store.export()
        row = try XCTUnwrap(data.transactions.first { $0.id == down })
        XCTAssertEqual(row.kind, "ADJUST_OUT")
        XCTAssertEqual(row.amount, 30_000)
        // Not spending, not income.
        let month = try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(month.spendingText, "$0.00")
        XCTAssertEqual(month.incomeText, "$0.00")
    }

    func testACardCorrectionIsTypedAsWhatIsOwedAndIsNotSpending() throws {
        try fresh()
        let card = try store.addAccount(AccountInput(
            type: "CREDIT_CARD", name: "Travel", last4: "", balance: "500", statementDay: "5", dueDay: "25", limit: "", payFromId: 0
        ))
        let sheet: CorrectionSheetData = try ledger().correctionSheet(accountId: card, editingId: 0, hidden: false)
        XCTAssertTrue(sheet.debt)
        XCTAssertEqual(sheet.shownWithout, 50_000)
        // The issuer shows 420.00 owed: the balance goes up by 80.00 (owed goes down).
        let id = try store.correctBalance(accountId: card, typedShown: 42_000, epochMillis: millis, editingId: 0)
        let data = try store.export()
        let row = try XCTUnwrap(data.transactions.first { $0.id == id })
        XCTAssertEqual(row.kind, "ADJUST_IN")
        XCTAssertEqual(row.amount, 8_000)
        let month = try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(month.spendingText, "$0.00")
    }

    func testEditingACorrectionWorksFromTheBalanceWithoutItAndDeleteUndoesIt() throws {
        try fresh()
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let id = try store.correctBalance(accountId: main, typedShown: 120_000, epochMillis: millis, editingId: 0)
        let sheet: CorrectionSheetData = try ledger().correctionSheet(accountId: 0, editingId: id, hidden: false)
        XCTAssertTrue(sheet.found)
        XCTAssertEqual(sheet.accountId, main)
        XCTAssertEqual(sheet.shownWithout, 100_000)
        XCTAssertEqual(sheet.initialShown, 120_000)
        let same = refusal { _ = try store.correctBalance(accountId: main, typedShown: 100_000, epochMillis: millis, editingId: id) }
        XCTAssertEqual(same?.message, "That's the balance Kassa already shows.")
        _ = try store.correctBalance(accountId: main, typedShown: 90_000, epochMillis: millis, editingId: id)
        XCTAssertEqual(try rowText("Main"), "$900.00")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 1)
        try store.deleteCorrection(id: id)
        XCTAssertEqual(try rowText("Main"), "$1,000.00")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testADebtIsCorrectedAsWhatIsOwed() throws {
        try fresh()
        let id = try store.addLoan(account: account("LOAN", "House"), loan: newLoan())
        _ = try store.correctBalance(accountId: id, typedShown: 2_500_000, epochMillis: millis, editingId: 0)
        XCTAssertEqual(try page(id).balanceText, "$25,000.00")
        XCTAssertEqual(try rowNote("House"), "$25,000.00")
    }

    func testAClosedAccountTakesNoNewCorrection() throws {
        try fresh()
        let old = try store.addAccount(account("CHECKING", "Old"))
        try store.setAccountClosed(id: old, closedDay: day)
        let refused = refusal { _ = try store.correctBalance(accountId: old, typedShown: 5_000, epochMillis: millis, editingId: 0) }
        XCTAssertEqual(refused?.message, "Old is closed. Reopen it first to add to it.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testAnOrdinaryEntryIsNotACorrectionToEditOrDelete() throws {
        try fresh()
        let main = try store.addAccount(account("CHECKING", "Main", balance: "1000"))
        let income = try store.addAccount(account("CHECKING", "Other", balance: "10"))
        let entry = EntryInput(
            id: 0, kind: "TRANSFER", amount: 500, accountId: main, toAccountId: income, categoryId: 0, epochMillis: millis, note: ""
        )
        let id = try store.addEntry(entry)
        XCTAssertNotNil(refusal { try store.deleteCorrection(id: id) })
        XCTAssertNotNil(refusal { _ = try store.correctBalance(accountId: main, typedShown: 1, epochMillis: millis, editingId: id) })
        XCTAssertEqual(try store.rowCount(table: "transactions"), 1)
    }

    func testTheDatedChangeCanBeRemoved() throws {
        try fresh()
        let id = try store.addLoan(account: account("LOAN", "House"), loan: newLoan())
        XCTAssertNotNil(refusal { try store.deleteLoanChange(id: 99) })
        XCTAssertEqual(try page(id).changes.count, 0)
    }
}
