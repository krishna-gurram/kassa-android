import Foundation
import KassaShared

/// Loans and balance corrections on the write path (stage G1). Every rule is :domain's (`LoanBridge`, `CorrectionBridge`, `LedgerView`);
/// this file only runs the SQL, each change in ONE transaction so a refusal or a failure leaves nothing behind, mirroring Android's
/// `LoanRepository.addLoan` / `addTerms` and `EntryRepository.correctBalance`.
extension LedgerStore {

    // MARK: Setup

    /// Adds a loan account with its terms: the account, the shared "Loan interest" category (and "Escrow" when there is escrow) if
    /// missing, and the `loan_terms` row, all in one transaction. Returns the account's id.
    @discardableResult
    func addLoan(account: AccountInput, loan: LoanInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let taken = try self.names(table: "accounts")
            let found = LoanBridge.shared.problems(account: account, loan: loan, takenNames: taken, currency: currency)
            if let first = found.first { throw WriteRefusal(field: first.field, message: first.message) }
            let data = try self.readAll()
            let refusal = LoanBridge.shared.saveProblem(loan: loan, accountId: 0, data: data, currency: currency)
            if !refusal.isEmpty { throw WriteRefusal(message: refusal) }
            // The account starts owing what the loan form says is owed, whatever balance the caller's account input holds.
            let owed = LoanBridge.shared.owedInput(loan: loan, currency: currency)
            let stored = AccountInput(
                type: "LOAN", name: account.name, last4: account.last4, balance: owed, statementDay: "", dueDay: "", limit: "", payFromId: 0
            )
            let order = try self.nextSortOrder(table: "accounts")
            let id = try self.insertRow(table: "accounts") { column in
                return AccountBridge.shared.cell(input: stored, column: column, sortOrder: Int32(order))
            }
            try self.afterInsertHook?()
            try self.writeLoanCategories(loan, data)
            try self.insertTerms(loan, accountId: id)
            return id
        }
    }

    /// Gives an existing loan account terms: refuses when it already has some, moves its starting balance so the balance typed shows
    /// now, writes the categories and the terms. One transaction.
    func addLoanTerms(accountId: Int64, loan: LoanInput) throws {
        try db.transaction { () throws -> Void in
            let currency = try self.currencyCode() ?? ""
            if try self.accountType(accountId) != "LOAN" {
                throw WriteRefusal(message: String(localized: "write.accountGone"))
            }
            if try self.exists("SELECT 1 FROM loan_terms WHERE accountId = ?", accountId) {
                throw WriteRefusal(message: String(localized: "write.loanHasTerms"))
            }
            let found = LoanBridge.shared.termsProblems(loan: loan, currency: currency)
            if let first = found.first { throw WriteRefusal(field: first.field, message: first.message) }
            let data = try self.readAll()
            let refusal = LoanBridge.shared.saveProblem(loan: loan, accountId: accountId, data: data, currency: currency)
            if !refusal.isEmpty { throw WriteRefusal(message: refusal) }
            let opening = LoanBridge.shared.openingBalanceAfter(loan: loan, accountId: accountId, data: data)
            let move = try self.db.prepare("UPDATE accounts SET openingBalance = ? WHERE id = ?")
            try move.bindInt(1, opening)
            try move.bindInt(2, accountId)
            _ = try move.step()
            try self.afterInsertHook?()
            try self.writeLoanCategories(loan, data)
            try self.insertTerms(loan, accountId: accountId)
        }
    }

    /// Makes sure "Loan interest" exists and is shown (always), and "Escrow" when the loan has escrow.
    private func writeLoanCategories(_ loan: LoanInput, _ data: BackupData) throws {
        try ensureCategory(data, which: "INTEREST")
        if LoanBridge.shared.hasEscrow(loan: loan) {
            try ensureCategory(data, which: "ESCROW")
        }
    }

    /// Makes sure the shared category [which] ("INTEREST" or "ESCROW") exists and is shown; returns its id.
    @discardableResult
    func ensureCategory(_ data: BackupData, which: String) throws -> Int64 {
        let step: LoanCategoryStep = LoanBridge.shared.categoryStep(data: data, which: which)
        if step.action == "UNHIDE" {
            let show = try db.prepare("UPDATE categories SET hidden = 0 WHERE id = ?")
            try show.bindInt(1, step.id)
            _ = try show.step()
            return step.id
        } else if step.action == "CREATE" {
            let order = try nextSortOrder(table: "categories")
            return try insertRow(table: "categories") { column in
                return LoanBridge.shared.categoryCell(data: data, which: which, column: column, sortOrder: Int32(order))
            }
        }
        return step.id
    }

    private func insertTerms(_ loan: LoanInput, accountId: Int64) throws {
        _ = try insertRow(table: "loan_terms") { column in
            return LoanBridge.shared.termsCell(loan: loan, accountId: accountId, column: column)
        }
    }

    // MARK: Balance corrections

    /// Saves a balance correction so [accountId] shows [typedShown] (a debt typed as what is owed). [editingId] is 0 for a new one,
    /// else the correction that is replaced (its account and its moment stay; [epochMillis] is used for a new one). A closed account
    /// takes no new correction. Returns the entry's id.
    @discardableResult
    func correctBalance(accountId: Int64, typedShown: Int64, epochMillis: Int64, editingId: Int64) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let data = try self.readAll()
            let made = LedgerView(data: data, fallbackCurrency: nil)
            var target = accountId
            if editingId > 0 {
                guard let account = try self.correctionAccount(editingId) else {
                    throw WriteRefusal(message: String(localized: "write.entryGone"))
                }
                target = account
            }
            if !(try self.exists("SELECT 1 FROM accounts WHERE id = ?", target)) {
                throw WriteRefusal(message: String(localized: "write.accountGone"))
            }
            let plan: CorrectionPlanData = made.correctionPlan(accountId: target, editingId: editingId, typedShown: typedShown)
            if !plan.problem.isEmpty { throw WriteRefusal(message: plan.problem) }
            if plan.kind.isEmpty { throw WriteRefusal(message: String(localized: "write.failed")) }
            if editingId > 0 {
                let update = try self.db.prepare("UPDATE transactions SET kind = ?, amount = ? WHERE id = ?")
                try update.bindText(1, plan.kind)
                try update.bindInt(2, plan.amount)
                try update.bindInt(3, editingId)
                _ = try update.step()
                return editingId
            }
            try self.requireOpen([String(target)])
            let id = try self.insertRow(table: "transactions") { column in
                return CorrectionBridge.shared.cell(
                    kind: plan.kind, amount: plan.amount, accountId: target, epochMillis: epochMillis, column: column
                )
            }
            try self.afterInsertHook?()
            return id
        }
    }

    /// The account of correction [id], or nil when [id] is not a balance correction.
    private func correctionAccount(_ id: Int64) throws -> Int64? {
        let statement = try db.prepare("SELECT kind, accountId FROM transactions WHERE id = ?")
        try statement.bindInt(1, id)
        guard try statement.step() else { return nil }
        let kind = statement.columnText(0) ?? ""
        if !CorrectionBridge.shared.isCorrection(kind: kind) { return nil }
        return statement.columnInt(1)
    }

    /// Deletes balance correction [id] (the ordinary delete refuses this kind).
    func deleteCorrection(id: Int64) throws {
        try db.transaction { () throws -> Void in
            if try self.correctionAccount(id) == nil {
                throw WriteRefusal(message: String(localized: "write.entryGone"))
            }
            let row = try self.db.prepare("DELETE FROM transactions WHERE id = ?")
            try row.bindInt(1, id)
            _ = try row.step()
        }
    }

    // MARK: Dated changes

    /// Removes the dated change [id] of a loan's terms (the loan page's tap to remove). Earlier payments keep their split.
    func deleteLoanChange(id: Int64) throws {
        try db.transaction { () throws -> Void in
            if !(try self.exists("SELECT 1 FROM loan_term_changes WHERE id = ?", id)) {
                throw WriteRefusal(message: String(localized: "write.entryGone"))
            }
            let row = try self.db.prepare("DELETE FROM loan_term_changes WHERE id = ?")
            try row.bindInt(1, id)
            _ = try row.step()
        }
    }
}
