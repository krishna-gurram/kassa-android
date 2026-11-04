import Foundation
import KassaShared

/// The loan payment sheet that is open: what it opens on (`LedgerView.loanPaymentSheet`), new or editing.
struct LoanPaymentRequest: Identifiable {
    let id = UUID()
    let data: LoanPaymentSheetData
}

/// The Change terms page that is open (`LedgerView.loanChangeSheet`).
struct LoanChangeRequest: Identifiable {
    let id = UUID()
    let data: LoanChangeSheetData
}

/// The Edit details sheet that is open (`LedgerView.loanDetailsSheet`).
struct LoanDetailsRequest: Identifiable {
    let id = UUID()
    let data: LoanDetailsSheetData
}

/// Loans, stage G2: the sheets of Record a payment, Change terms and Edit details, their live checks and their writes. Every rule is
/// :domain's, asked through `LedgerView` and the bridges on the one queue; the writes are `LedgerStore+LoanPayments.swift`.
extension AppModel {

    // MARK: Record a payment

    /// Opens the payment sheet: a new payment on loan [accountId] ([paymentId] 0), or payment [paymentId] to edit or delete (pass
    /// [accountId] 0 to read the loan from it). Does nothing when the loan, its terms or the payment are gone.
    func openLoanPayment(paymentId: Int64, accountId: Int64) {
        let hidden = hideAmounts
        let day = Int32(KassaDates.todayEpochDay())
        queue.async {
            guard let made = self.view else { return }
            let data: LoanPaymentSheetData = made.loanPaymentSheet(accountId: accountId, paymentId: paymentId, todayEpochDay: day, hidden: hidden)
            if !data.found { return }
            self.publish { self.loanPaymentSheet = LoanPaymentRequest(data: data) }
        }
    }

    func openNewLoanPayment(accountId: Int64) {
        openLoanPayment(paymentId: 0, accountId: accountId)
    }

    func closeLoanPaymentSheet() {
        loanPaymentSheet = nil
    }

    /// One edit of the sheet (`LedgerView.loanPaymentApply`): the form as the rules leave it after [action] ("AMOUNT", "DAY", "PRINCIPAL",
    /// "INTEREST" or "COUNTS") with [text].
    func loanPaymentApply(
        data: LoanPaymentSheetData, input: LoanPaymentInput, action: String, text: String, done: @escaping (LoanPaymentInput) -> Void
    ) {
        queue.async {
            guard let made = self.view else { return }
            let next: LoanPaymentInput = made.loanPaymentApply(
                accountId: data.accountId, paymentId: data.editingId, input: input, action: action, text: text
            )
            self.publish { done(next) }
        }
    }

    /// The figures and words under the sheet's fields.
    func loanPaymentPreview(data: LoanPaymentSheetData, input: LoanPaymentInput, done: @escaping (LoanPaymentPreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let preview: LoanPaymentPreviewData = made.loanPaymentPreview(
                accountId: data.accountId, paymentId: data.editingId, input: input, hidden: hidden
            )
            self.publish { done(preview) }
        }
    }

    /// Records the payment (or saves the edit) as one transaction. [epochDay] becomes the entries' moment (now for today).
    func saveLoanPayment(data: LoanPaymentSheetData, input: LoanPaymentInput, done: @escaping (WriteOutcome) -> Void) {
        let zone = TimeZone.current.identifier
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        perform({ opened in
            let moment: Int64 = EntryBridge.shared.savedMillis(epochDay: input.day, zoneId: zone, nowMillis: now, originalMillis: 0)
            return try opened.recordLoanPayment(accountId: data.accountId, paymentId: data.editingId, input: input, epochMillis: moment)
        }, done: done)
    }

    func deleteLoanPayment(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteLoanPayment(id: id)
            return id
        }, done: done)
    }

    // MARK: Change terms

    func openLoanChange(accountId: Int64) {
        let day = Int32(KassaDates.todayEpochDay())
        queue.async {
            guard let made = self.view else { return }
            let data: LoanChangeSheetData = made.loanChangeSheet(accountId: accountId, todayEpochDay: day)
            if !data.found { return }
            self.publish { self.loanChangeSheet = LoanChangeRequest(data: data) }
        }
    }

    func closeLoanChangeSheet() {
        loanChangeSheet = nil
    }

    func loanChangePreview(accountId: Int64, input: LoanChangeInput, done: @escaping (LoanChangePreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let preview: LoanChangePreviewData = made.loanChangePreview(accountId: accountId, input: input, hidden: hidden)
            self.publish { done(preview) }
        }
    }

    func saveLoanChange(accountId: Int64, input: LoanChangeInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.addLoanChange(accountId: accountId, input: input) }, done: done)
    }

    // MARK: Edit details

    func openLoanDetails(accountId: Int64) {
        queue.async {
            guard let made = self.view else { return }
            let data: LoanDetailsSheetData = made.loanDetailsSheet(accountId: accountId)
            if !data.found { return }
            self.publish { self.loanDetailsSheet = LoanDetailsRequest(data: data) }
        }
    }

    func closeLoanDetailsSheet() {
        loanDetailsSheet = nil
    }

    /// Every field's problem of the details as typed, and what "Same as interest" stands for.
    func loanDetailsChecks(accountId: Int64, input: LoanDetailsInput, done: @escaping ([FieldProblemData], String) -> Void) {
        queue.async {
            guard let made = self.view else { return }
            let found: [FieldProblemData] = made.loanDetailsProblems(accountId: accountId, input: input)
            let name: String = made.loanDetailsPrincipalDefault(accountId: accountId, input: input)
            self.publish { done(found, name) }
        }
    }

    func saveLoanDetails(accountId: Int64, input: LoanDetailsInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.updateLoanDetails(accountId: accountId, input: input)
            return accountId
        }, done: done)
    }
}
