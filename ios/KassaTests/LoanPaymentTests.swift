import XCTest
import KassaShared
@testable import Kassa

/// Loans part two (stage G2) through the store, on the demo backup: its Car loan (account 5) pays 350.00 a month from Checking (account 1).
/// A payment, its edit and its delete are each one transaction; a refusal or a failure leaves nothing behind. Every rule is :domain's
/// (JVM-tested in `LoanPaymentsTest`); these check that the Swift side runs it in one transaction and stores what the screens show.
final class LoanPaymentTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!
    private var today: Int32 = 0

    private let loan: Int64 = 5
    private let checking: Int64 = 1

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-loan2-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        store = try LedgerStore(directory: directory, keyStore: keys)
        try store.replace(with: try DemoFixture.data())
        today = try DemoFixture.expected().today
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private var moment: Int64 { return Int64(today) * 86_400_000 + 43_200_000 }

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func sheet(_ paymentId: Int64 = 0) throws -> LoanPaymentSheetData {
        return try ledger().loanPaymentSheet(accountId: loan, paymentId: paymentId, todayEpochDay: today, hidden: false)
    }

    /// The scheduled payment from Checking.
    private func scheduled() throws -> LoanPaymentInput {
        let opened: LoanPaymentSheetData = try sheet()
        return try fromChecking(opened.input)
    }

    private func fromChecking(_ i: LoanPaymentInput) throws -> LoanPaymentInput {
        return LoanPaymentInput(
            amount: i.amount, day: i.day, principal: i.principal, interest: i.interest, edit: i.edit, fromId: checking,
            categoryId: i.categoryId, estimate: i.estimate, principalCategoryId: i.principalCategoryId,
            countOverride: i.countOverride, escrowCategoryId: i.escrowCategoryId
        )
    }

    private func nextDay() throws -> Int32 {
        let data = try store.export()
        for terms in data.loanTerms where terms.accountId == loan { return terms.nextPaymentDay }
        return -1
    }

    private func outstanding() throws -> String {
        let page: LoanPageData = try ledger().loanPage(
            accountId: loan, hidden: false, todayEpochDay: today, timeZoneId: "UTC", monthsBack: 0, allRows: false
        )
        return page.balanceText
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

    // MARK: Record

    func testAPaymentSavesItsEntriesItsLinkAndMovesTheDateTogether() throws {
        let entries = try store.rowCount(table: "transactions")
        let links = try store.rowCount(table: "loan_payments")
        let before = try nextDay()
        let owedBefore = try outstanding()
        let id = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: try scheduled(), epochMillis: moment)
        XCTAssertGreaterThan(id, 0)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries + 2)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links + 1)
        XCTAssertGreaterThan(try nextDay(), before)
        XCTAssertNotEqual(try outstanding(), owedBefore)
    }

    func testAFailureInTheMiddleLeavesNothingBehind() throws {
        struct Boom: Error {}
        let entries = try store.rowCount(table: "transactions")
        let links = try store.rowCount(table: "loan_payments")
        let before = try nextDay()
        let owedBefore = try outstanding()
        let input = try scheduled()
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.recordLoanPayment(accountId: loan, paymentId: 0, input: input, epochMillis: moment))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links)
        XCTAssertEqual(try nextDay(), before)
        XCTAssertEqual(try outstanding(), owedBefore)
        // The store still works afterwards.
        _ = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: input, epochMillis: moment)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links + 1)
    }

    func testNoAccountIsRefusedAndSavesNothing() throws {
        let entries = try store.rowCount(table: "transactions")
        let opened: LoanPaymentSheetData = try sheet()
        let none = LoanPaymentInput(
            amount: opened.input.amount, day: opened.input.day, principal: opened.input.principal, interest: opened.input.interest,
            edit: opened.input.edit, fromId: 0, categoryId: opened.input.categoryId, estimate: opened.input.estimate,
            principalCategoryId: opened.input.principalCategoryId, countOverride: opened.input.countOverride,
            escrowCategoryId: opened.input.escrowCategoryId
        )
        let found = refusal { _ = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: none, epochMillis: moment) }
        XCTAssertEqual(found?.message, "Pick the account it's paid from.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testAClosedAccountTakesNoNewPayment() throws {
        let close = try store.db.prepare("UPDATE accounts SET closedDay = ? WHERE id = ?")
        try close.bindInt(1, Int64(today) - 1)
        try close.bindInt(2, checking)
        _ = try close.step()
        let entries = try store.rowCount(table: "transactions")
        let input = try scheduled()
        let found = refusal { _ = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: input, epochMillis: moment) }
        XCTAssertTrue(found?.message.hasSuffix("is closed. Reopen it first to add to it.") ?? false)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    // MARK: Edit and delete

    func testEditingAndDeletingActOnTheWholePayment() throws {
        let entries = try store.rowCount(table: "transactions")
        let links = try store.rowCount(table: "loan_payments")
        let before = try nextDay()
        let owedBefore = try outstanding()
        let id = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: try scheduled(), epochMillis: moment)
        let owedAfter = try outstanding()
        // Open it again and make it 400.00: the extra goes to principal, still two entries and one link.
        let opened: LoanPaymentSheetData = try sheet(id)
        XCTAssertTrue(opened.found)
        XCTAssertEqual(opened.editingId, id)
        let bigger: LoanPaymentInput = try ledger().loanPaymentApply(
            accountId: loan, paymentId: id, input: opened.input, action: "AMOUNT", text: "400.00"
        )
        let same = try store.recordLoanPayment(accountId: loan, paymentId: id, input: bigger, epochMillis: moment)
        XCTAssertEqual(same, id)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries + 2)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links + 1)
        XCTAssertNotEqual(try outstanding(), owedAfter)
        // The sheet opens from any entry of the payment.
        let view = try ledger()
        XCTAssertGreaterThan(view.loanPaymentIdOfEntry(entryId: try firstEntryOf(id)), 0)
        // Delete it: everything is as it was, and the date moves back.
        try store.deleteLoanPayment(id: id)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links)
        XCTAssertEqual(try nextDay(), before)
        XCTAssertEqual(try outstanding(), owedBefore)
    }

    private func firstEntryOf(_ paymentId: Int64) throws -> Int64 {
        let data = try store.export()
        for row in data.loanPayments where row.id == paymentId { return row.principalEntryId?.int64Value ?? 0 }
        return 0
    }

    func testAScheduledPaymentMovesTheNextDateAMonthAndAnExtraOneDoesNot() throws {
        let before = try nextDay()
        // Less than the scheduled payment does not count as the scheduled one.
        let opened: LoanPaymentSheetData = try sheet()
        let small: LoanPaymentInput = try ledger().loanPaymentApply(
            accountId: loan, paymentId: 0, input: try fromChecking(opened.input), action: "AMOUNT", text: "100.00"
        )
        _ = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: small, epochMillis: moment)
        XCTAssertEqual(try nextDay(), before)
        _ = try store.recordLoanPayment(accountId: loan, paymentId: 0, input: try scheduled(), epochMillis: moment)
        XCTAssertGreaterThan(try nextDay(), before)
    }

    // MARK: Change terms and details

    func testAChangeOfTermsIsOneRowAndNothingBeforeTheStartIsAccepted() throws {
        let changes = try store.rowCount(table: "loan_term_changes")
        let opened: LoanChangeSheetData = try ledger().loanChangeSheet(accountId: loan, todayEpochDay: today)
        XCTAssertTrue(opened.found)
        let rate = LoanChangeInput(
            kind: "RATE", day: today, rate: "7", payment: "", termMonths: "", type: "", kept: "PAYMENT", basis: opened.input.basis
        )
        _ = try store.addLoanChange(accountId: loan, input: rate)
        XCTAssertEqual(try store.rowCount(table: "loan_term_changes"), changes + 1)
        let early = LoanChangeInput(
            kind: "RATE", day: 1000, rate: "7", payment: "", termMonths: "", type: "", kept: "PAYMENT", basis: opened.input.basis
        )
        let found = refusal { _ = try store.addLoanChange(accountId: loan, input: early) }
        XCTAssertEqual(found?.message, "The change can't start before the loan did.")
        XCTAssertEqual(try store.rowCount(table: "loan_term_changes"), changes + 1)
    }

    func testDetailsRenameTheLoanAndKeepThePaymentsAndTheCountSwitchChangesSpending() throws {
        let links = try store.rowCount(table: "loan_payments")
        let opened: LoanDetailsSheetData = try ledger().loanDetailsSheet(accountId: loan)
        XCTAssertTrue(opened.found)
        let i: LoanDetailsInput = opened.input
        let spendingBefore = try octoberSpending()
        let on = LoanDetailsInput(
            name: "Truck loan", dueDay: i.dueDay, payFromId: i.payFromId, interestCategoryId: i.interestCategoryId, counts: true,
            principalCategoryId: 0, escrow: i.escrow, fee: i.fee, earlyCharge: i.earlyCharge, original: i.original, startDay: i.startDay
        )
        try store.updateLoanDetails(accountId: loan, input: on)
        XCTAssertEqual(try store.rowCount(table: "loan_payments"), links)
        let page: LoanPageData = try ledger().loanPage(
            accountId: loan, hidden: false, todayEpochDay: today, timeZoneId: "UTC", monthsBack: 0, allRows: false
        )
        XCTAssertEqual(page.name, "Truck loan")
        // Every spending figure counts the principal now ...
        XCTAssertNotEqual(try octoberSpending(), spendingBefore)
        // ... and turning it off restores them.
        let off = LoanDetailsInput(
            name: "Truck loan", dueDay: i.dueDay, payFromId: i.payFromId, interestCategoryId: i.interestCategoryId, counts: false,
            principalCategoryId: 0, escrow: i.escrow, fee: i.fee, earlyCharge: i.earlyCharge, original: i.original, startDay: i.startDay
        )
        try store.updateLoanDetails(accountId: loan, input: off)
        XCTAssertEqual(try octoberSpending(), spendingBefore)
    }

    func testDetailsRefuseANameAnotherAccountHas() throws {
        let opened: LoanDetailsSheetData = try ledger().loanDetailsSheet(accountId: loan)
        let i: LoanDetailsInput = opened.input
        let clash = LoanDetailsInput(
            name: "Wallet", dueDay: i.dueDay, payFromId: i.payFromId, interestCategoryId: i.interestCategoryId, counts: i.counts,
            principalCategoryId: i.principalCategoryId, escrow: i.escrow, fee: i.fee, earlyCharge: i.earlyCharge, original: i.original,
            startDay: i.startDay
        )
        let found = refusal { try store.updateLoanDetails(accountId: loan, input: clash) }
        XCTAssertEqual(found?.message, "Wallet is already used.")
    }

    private func octoberSpending() throws -> String {
        let shown = try ledger().insights(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", monthsBack: 0)
        return shown.totalText
    }
}
