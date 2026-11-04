import Foundation
import KassaShared

/// Recurring bills on iOS (batch I): save, delete, mark a month handled, approve, and clear what waits on the Notifications page. Every rule
/// is :domain's (`BillRules` through `BillBridge`, which mirror Android's `BillRepository` and `LedgerViewModel.approveBill`); this file
/// only runs the SQL, each change in ONE transaction so a refusal or a failure leaves nothing behind. `afterInsertHook` (run inside
/// `writeEntry`, after the entry is written) lets a test prove that approving a bill is all or nothing.
extension LedgerStore {

    /// What approving needs of a stored bill.
    private struct StoredBill {
        let name: String
        let categoryId: Int64
        let accountId: Int64
    }

    private func storedBill(_ id: Int64) throws -> StoredBill? {
        let statement = try db.prepare("SELECT name, categoryId, accountId FROM recurring WHERE id = ?")
        try statement.bindInt(1, id)
        guard try statement.step() else { return nil }
        let name: String = statement.columnText(0) ?? ""
        let category: Int64 = statement.columnInt(1) ?? 0
        let account: Int64 = statement.columnInt(2) ?? 0
        return StoredBill(name: name, categoryId: category, accountId: account)
    }

    /// Adds a bill ([input] id 0) or saves an edit. Refuses what `BillRules` refuses. An edit rewrites the columns the form owns and leaves
    /// the handled month alone. Returns the bill's id.
    @discardableResult
    func saveBill(_ input: BillInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let problems: [FieldProblemData] = BillBridge.shared.problems(input: input, currency: currency)
            if let first = problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            if input.id == 0 {
                return try self.insertRow(table: "recurring") { column in
                    return BillBridge.shared.cell(input: input, column: column)
                }
            }
            if !(try self.exists("SELECT 1 FROM recurring WHERE id = ?", input.id)) {
                throw WriteRefusal(message: String(localized: "write.billGone"))
            }
            try self.updateBill(input)
            return input.id
        }
    }

    private func updateBill(_ input: BillInput) throws {
        let rows = BackupRows.shared
        let columns = rows.columns(table: "recurring")
        let kinds = rows.columnKinds(table: "recurring")
        var sets: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where BillBridge.shared.ownsColumn(column: Int32(index)) {
            sets.append("\"\(name)\" = ?")
            positions.append(index)
        }
        let statement = try db.prepare("UPDATE recurring SET \(sets.joined(separator: ", ")) WHERE id = ?")
        for (slot, index) in positions.enumerated() {
            let value: String? = BillBridge.shared.cell(input: input, column: Int32(index))
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        try statement.bindInt(Int32(positions.count + 1), input.id)
        _ = try statement.step()
    }

    /// Deletes a bill. Entries already added for it stay.
    func deleteBill(id: Int64) throws {
        try db.transaction { () throws -> Void in
            let statement = try self.db.prepare("DELETE FROM recurring WHERE id = ?")
            try statement.bindInt(1, id)
            _ = try statement.step()
        }
    }

    /// Marks the bill handled for [month] (yyyyMM): only that one field changes, and a bill that is gone is left gone.
    func markBillHandled(id: Int64, month: Int32) throws {
        try db.transaction { () throws -> Void in
            try self.setHandled(id: id, month: month)
        }
    }

    @discardableResult
    private func setHandled(id: Int64, month: Int32) throws -> Int {
        let statement = try db.prepare("UPDATE recurring SET handledMonth = ? WHERE id = ?")
        try statement.bindInt(1, Int64(month))
        try statement.bindInt(2, id)
        _ = try statement.step()
        return db.changes()
    }

    /// Approves bill [billId] due on [dueDay]: saves its expense for [amount] (the entry's checks run with it) and marks the month handled,
    /// in one transaction, so a failure of either leaves neither. A bill deleted meanwhile is refused with a sentence and nothing is added.
    /// Returns the entry's id.
    @discardableResult
    func approveBill(billId: Int64, amount: Int64, dueDay: Int32, todayDay: Int32, zoneId: String) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            guard let bill = try self.storedBill(billId) else {
                throw WriteRefusal(message: String(localized: "write.billGone"))
            }
            let entry: EntryInput = BillBridge.shared.approveEntry(
                name: bill.name, categoryId: bill.categoryId, accountId: bill.accountId, amount: amount, dueDay: dueDay,
                today: todayDay, zoneId: zoneId
            )
            let id = try self.writeEntry(entry)
            let month: Int32 = BillBridge.shared.handledMonth(dueDay: dueDay)
            if try self.setHandled(id: billId, month: month) == 0 {
                throw WriteRefusal(message: String(localized: "write.billGone"))
            }
            return id
        }
    }

    /// Clear on the Notifications page for what lives in the database: each bill in [bills] (id and the month to mark handled) skips that
    /// month, each transfer in [transfers] stops asking. Nothing is added or paid. All or nothing.
    func clearNotices(bills: [BillMonth], transfers: [Int64]) throws {
        try db.transaction { () throws -> Void in
            for bill in bills {
                try self.setHandled(id: bill.id, month: bill.month)
            }
            for id in transfers {
                let statement = try self.db.prepare("DELETE FROM investment_transfers WHERE id = ?")
                try statement.bindInt(1, id)
                _ = try statement.step()
            }
        }
    }
}

/// A bill and the month (yyyyMM) clearing it marks handled.
struct BillMonth: Equatable {
    let id: Int64
    let month: Int32
}
