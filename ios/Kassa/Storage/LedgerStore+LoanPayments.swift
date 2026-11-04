import Foundation
import KassaShared

/// Loans, stage G2: recording, editing and deleting a payment, a dated change of terms and a loan's details. Every check and every
/// figure is :domain's (`LoanPaymentBridge`, `LoanChangeBridge`, `LoanDetailsBridge`, which mirror Android's `LoanRepository`); this file
/// only runs the SQL, each change in ONE transaction so a refusal or a failure leaves nothing behind.
extension LedgerStore {

    // MARK: Payments

    /// Records a payment on loan [accountId], or saves an edit of payment [paymentId] (0 for a new one): the principal TRANSFER, the interest
    /// EXPENSE, the escrow and charge EXPENSE, the `loan_payments` row and the loan's next payment date, all or nothing. A closed account
    /// is refused for a new payment (an edit keeps the accounts it has). Returns the payment's id.
    @discardableResult
    func recordLoanPayment(accountId: Int64, paymentId: Int64, input: LoanPaymentInput, epochMillis: Int64) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: LoanPaymentPlanData = LoanPaymentBridge.shared.plan(
                data: data, accountId: accountId, paymentId: paymentId, input: input, epochMillis: epochMillis, currency: currency
            )
            if !plan.problem.isEmpty { throw WriteRefusal(message: plan.problem) }
            var interestCategory: Int64 = plan.interestCategoryId
            if plan.ensureInterest {
                interestCategory = try self.ensureCategory(data, which: "INTEREST")
            }
            var costCategory: Int64 = plan.costCategoryId
            if plan.ensureCost {
                costCategory = try self.ensureCategory(data, which: "ESCROW")
            }
            // The payment being edited comes out first.
            for text in plan.oldEntryIds {
                try self.deleteTransaction(text)
            }
            var principalId: Int64 = 0
            if plan.hasPrincipal {
                principalId = try self.insertPaymentEntry(plan, "PRINCIPAL", interestCategory, costCategory)
            }
            try self.afterInsertHook?()
            var interestId: Int64 = 0
            if plan.hasInterest {
                interestId = try self.insertPaymentEntry(plan, "INTEREST", interestCategory, costCategory)
            }
            var otherId: Int64 = 0
            if plan.hasOther {
                otherId = try self.insertPaymentEntry(plan, "OTHER", interestCategory, costCategory)
            }
            let move = try self.db.prepare("UPDATE loan_terms SET nextPaymentDay = ? WHERE accountId = ?")
            try move.bindInt(1, Int64(plan.nextPaymentDay))
            try move.bindInt(2, accountId)
            _ = try move.step()
            if plan.linkId > 0 {
                try self.updateLink(plan, principalId, interestId, otherId)
                return plan.linkId
            }
            return try self.insertRow(table: "loan_payments") { column in
                return LoanPaymentBridge.shared.linkCell(
                    plan: plan, principalId: principalId, interestId: interestId, otherId: otherId, column: column
                )
            }
        }
    }

    private func deleteTransaction(_ idText: String) throws {
        guard let id = Int64(idText) else { return }
        let row = try db.prepare("DELETE FROM transactions WHERE id = ?")
        try row.bindInt(1, id)
        _ = try row.step()
    }

    private func insertPaymentEntry(_ plan: LoanPaymentPlanData, _ which: String, _ interest: Int64, _ cost: Int64) throws -> Int64 {
        return try insertRow(table: "transactions") { column in
            return LoanPaymentBridge.shared.entryCell(plan: plan, which: which, interestCategory: interest, costCategory: cost, column: column)
        }
    }

    private func updateLink(_ plan: LoanPaymentPlanData, _ principalId: Int64, _ interestId: Int64, _ otherId: Int64) throws {
        let rows = BackupRows.shared
        let columns = rows.columns(table: "loan_payments")
        let kinds = rows.columnKinds(table: "loan_payments")
        var sets: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where name != "id" {
            sets.append("\"\(name)\" = ?")
            positions.append(index)
        }
        let statement = try db.prepare("UPDATE loan_payments SET \(sets.joined(separator: ", ")) WHERE id = ?")
        for (slot, index) in positions.enumerated() {
            let value = LoanPaymentBridge.shared.linkCell(
                plan: plan, principalId: principalId, interestId: interestId, otherId: otherId, column: Int32(index)
            )
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        try statement.bindInt(Int32(positions.count + 1), plan.linkId)
        _ = try statement.step()
    }

    /// Deletes payment [id] with all its entries, and moves the next payment date back when the payment had moved it on. A payment that
    /// is already gone changes nothing (as Android's).
    func deleteLoanPayment(id: Int64) throws {
        try db.transaction { () throws -> Void in
            let data = try self.readAll()
            let gone: LoanPaymentDeleteData = LoanPaymentBridge.shared.deletePlan(data: data, paymentId: id)
            if !gone.found { return }
            if gone.nextPaymentDay > 0 {
                let move = try self.db.prepare("UPDATE loan_terms SET nextPaymentDay = ? WHERE accountId = ?")
                try move.bindInt(1, Int64(gone.nextPaymentDay))
                try move.bindInt(2, gone.accountId)
                _ = try move.step()
            }
            for text in gone.entryIds {
                try self.deleteTransaction(text)
            }
            let link = try self.db.prepare("DELETE FROM loan_payments WHERE id = ?")
            try link.bindInt(1, id)
            _ = try link.step()
        }
    }

    // MARK: Change terms and details

    /// Saves a dated change of loan [accountId]'s terms (one `loan_term_changes` row). Payments before its date are not touched.
    @discardableResult
    func addLoanChange(accountId: Int64, input: LoanChangeInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: LoanChangePlanData = LoanChangeBridge.shared.plan(data: data, accountId: accountId, input: input, currency: currency)
            if !plan.problem.isEmpty { throw WriteRefusal(message: plan.problem) }
            let id = try self.insertRow(table: "loan_term_changes") { column in
                return LoanChangeBridge.shared.cell(plan: plan, column: column)
            }
            try self.afterInsertHook?()
            return id
        }
    }

    /// Saves a loan's details: the name goes to the account, the rest replaces the `loan_terms` row; "Loan interest" is made when missing
    /// (and "Escrow" with escrow). Payments recorded so far keep what they hold.
    func updateLoanDetails(accountId: Int64, input: LoanDetailsInput) throws {
        try db.transaction { () throws -> Void in
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: LoanDetailsPlanData = LoanDetailsBridge.shared.plan(data: data, accountId: accountId, input: input, currency: currency)
            if !plan.problem.isEmpty { throw WriteRefusal(message: plan.problem) }
            let rename = try self.db.prepare("UPDATE accounts SET name = ? WHERE id = ?")
            try rename.bindText(1, plan.name)
            try rename.bindInt(2, accountId)
            _ = try rename.step()
            try self.ensureCategory(data, which: "INTEREST")
            if plan.needsEscrowCategory {
                try self.ensureCategory(data, which: "ESCROW")
            }
            let clear = try self.db.prepare("DELETE FROM loan_terms WHERE accountId = ?")
            try clear.bindInt(1, accountId)
            _ = try clear.step()
            _ = try self.insertRow(table: "loan_terms") { column in
                return LoanDetailsBridge.shared.termsCell(plan: plan, column: column)
            }
            try self.afterInsertHook?()
        }
    }
}
