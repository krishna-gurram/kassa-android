import Foundation
import KassaShared

/// Budgets on iOS (batch J): save and remove. The `budgets` table holds one row per category and month, where month 0 is every month
/// and yyyymm is an override for that month (what Android's `BudgetRepository` writes). Every rule (what a budget can be, which rows a
/// typed pair of amounts changes) is :domain's (`BudgetRules`, reached through `LedgerView.budgetPlan`); this file only runs the SQL,
/// all the changes of one save in ONE transaction, so a refusal or a failure leaves every row as it was. `afterInsertHook` runs after
/// the last write so a test can prove the rollback.
extension LedgerStore {

    /// Runs the writes of one saved budget editor together. Each amount is checked again by the domain (`ActivityBridge.budgetProblem`)
    /// and refused as a whole, so nothing is half written. A change that removes a row that is not there does nothing.
    func applyBudgetChanges(_ changes: [BudgetChangeData]) throws {
        try db.transaction { () throws -> Void in
            try self.writeBudgetRows(changes, forCategory: nil)
            try self.afterInsertHook?()
        }
    }

    /// The writes of [changes] inside the caller's transaction (the budget editor's, and the category editor's, which saves its category
    /// and its budget rows together). [forCategory], when given, replaces the category of a change that names 0: the category being made.
    func writeBudgetRows(_ changes: [BudgetChangeData], forCategory id: Int64?) throws {
        for change in changes {
            let target: Int64 = (change.categoryId == 0 ? id : nil) ?? change.categoryId
            if change.isRemoval {
                try removeBudgetRow(categoryId: target, month: change.month)
            } else {
                if let problem = ActivityBridge.shared.budgetProblem(cents: change.amount) {
                    throw WriteRefusal(message: problem)
                }
                try setBudgetRow(categoryId: target, month: change.month, amount: change.amount)
            }
        }
    }

    private func setBudgetRow(categoryId: Int64, month: Int32, amount: Int64) throws {
        let statement = try db.prepare("INSERT OR REPLACE INTO budgets (categoryId, month, amount) VALUES (?, ?, ?)")
        try statement.bindInt(1, categoryId)
        try statement.bindInt(2, Int64(month))
        try statement.bindInt(3, amount)
        _ = try statement.step()
    }

    private func removeBudgetRow(categoryId: Int64, month: Int32) throws {
        let statement = try db.prepare("DELETE FROM budgets WHERE categoryId = ? AND month = ?")
        try statement.bindInt(1, categoryId)
        try statement.bindInt(2, Int64(month))
        _ = try statement.step()
    }

    /// Removes every budget of a category (the editor's "Remove budget"), in one transaction.
    func removeBudgets(categoryId: Int64) throws {
        try db.transaction { () throws -> Void in
            let statement = try self.db.prepare("DELETE FROM budgets WHERE categoryId = ?")
            try statement.bindInt(1, categoryId)
            _ = try statement.step()
            try self.afterInsertHook?()
        }
    }
}
