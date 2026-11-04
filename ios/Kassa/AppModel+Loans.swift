import Foundation
import KassaShared

/// The balance correction sheet that is open. [data] is what it opens on (`LedgerView.correctionSheet`).
struct CorrectionRequest: Identifiable {
    let id = UUID()
    let data: CorrectionSheetData
}

/// The "Add terms" sheet of loan account [accountId]: [start] is the form the domain makes for it (`LedgerView.loanTermsInput`).
struct TermsRequest: Identifiable {
    let id = UUID()
    let accountId: Int64
    let name: String
    let start: LoanInput
}

/// Loans and balance corrections (stage G1): the sheets, the live checks of the loan form and the writes. Every rule is :domain's,
/// asked through `LoanBridge`, `CorrectionBridge` and `LedgerView` on the one queue.
extension AppModel {

    // MARK: Loan form

    /// Every field's problem of a loan being added (the account's own first); empty when it can be saved.
    func loanProblems(account: AccountInput, loan: LoanInput, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = LoanBridge.shared.problems(
                    account: account, loan: loan, takenNames: made.accountNames(), currency: made.currencyCode()
                )
            }
            self.publish { done(found) }
        }
    }

    /// The problems of the terms alone (the "Add terms" sheet); empty when they can be saved.
    func termsProblems(loan: LoanInput, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = LoanBridge.shared.termsProblems(loan: loan, currency: made.currencyCode())
            }
            self.publish { done(found) }
        }
    }

    /// The calculation under the form.
    func loanPreview(loan: LoanInput, done: @escaping (LoanPreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            var code = ""
            if let made = self.view { code = made.currencyCode() }
            let data: LoanPreviewData = LoanBridge.shared.preview(loan: loan, currency: code, hidden: hidden)
            self.publish { done(data) }
        }
    }

    /// Adds the loan account [name] with the terms [loan] describes: the account, its terms and the shared categories in one transaction
    /// (the account starts owing what the form says is owed).
    func addLoan(name: String, last4: String, loan: LoanInput, done: @escaping (WriteOutcome) -> Void) {
        let account = AccountInput(
            type: "LOAN", name: name, last4: last4, balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0
        )
        perform({ opened in try opened.addLoan(account: account, loan: loan) }, done: done)
    }

    func addLoanTerms(accountId: Int64, loan: LoanInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.addLoanTerms(accountId: accountId, loan: loan)
            return accountId
        }, done: done)
    }

    // MARK: Add terms

    /// Opens "Add terms" for loan [id] (a loan page without terms offers it).
    func openAddTerms(accountId: Int64, name: String) {
        let day = Int32(KassaDates.todayEpochDay())
        queue.async {
            guard let made = self.view else { return }
            let start: LoanInput = made.loanTermsInput(accountId: accountId, todayEpochDay: day)
            self.publish { self.termsSheet = TermsRequest(accountId: accountId, name: name, start: start) }
        }
    }

    func closeTermsSheet() {
        termsSheet = nil
    }

    // MARK: Loan page

    /// Shows the whole payment schedule, or the next few again.
    func setLoanAll(_ all: Bool) {
        loanAll = all
        refreshFigures()
    }

    /// Removes the dated change [id] of a loan's terms.
    func deleteLoanChange(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteLoanChange(id: id)
            return id
        }, done: done)
    }

    // MARK: Balance corrections

    /// Opens the correction sheet for account [accountId] (a new correction), or for correction entry [editingId] (its account is read
    /// from it; pass 0 for [accountId]). Does nothing when either is gone.
    func openCorrection(accountId: Int64, editingId: Int64 = 0) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let data: CorrectionSheetData = made.correctionSheet(accountId: accountId, editingId: editingId, hidden: hidden)
            if !data.found { return }
            self.publish { self.correctionSheet = CorrectionRequest(data: data) }
        }
    }

    func closeCorrectionSheet() {
        correctionSheet = nil
    }

    /// The difference line and the problem under the figure typed on the correction sheet.
    func correctionPreview(data: CorrectionSheetData, typedShown: Int64, typed: Bool, done: @escaping (CorrectionPreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            var code = ""
            if let made = self.view { code = made.currencyCode() }
            let preview: CorrectionPreviewData = CorrectionBridge.shared.preview(
                shownWithout: data.shownWithout, typedShown: typedShown, typed: typed, debt: data.debt, currency: code, hidden: hidden
            )
            self.publish { done(preview) }
        }
    }

    /// Saves the correction: the account then shows [typedShown] (a debt as what is owed). [epochMillis] is the moment of a new one.
    func saveCorrection(data: CorrectionSheetData, typedShown: Int64, epochMillis: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.correctBalance(
                accountId: data.accountId, typedShown: typedShown, epochMillis: epochMillis, editingId: data.editingId
            )
        }, done: done)
    }

    func deleteCorrection(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteCorrection(id: id)
            return id
        }, done: done)
    }
}
