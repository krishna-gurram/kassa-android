import Foundation
import KassaShared

/// Editing what already exists: an account, a card, a person. Every rule (the fields, uniqueness, a closed account's balance, a
/// card's own pay-from, the starting balance) is :domain's, asked through `LedgerView` and `EditBridge`; this file only runs the
/// SQL, each change in ONE transaction, as Android's `AccountRepository.update` / `setClosed` and `PeopleRepository.update` do.
extension LedgerStore {

    /// The ledger as the rules read it, inside the transaction that is about to write.
    private func ledgerNow() throws -> LedgerView {
        return LedgerView(data: try readAll(), fallbackCurrency: nil)
    }

    // MARK: Accounts

    /// Saves an edited account or card. Refuses (with `WriteRefusal`) the first problem `AccountForm` and `EditRules` find. The balance the
    /// person typed moves the starting balance by the difference, so entries stay as they are; the group, order, archived and closed
    /// state are not touched.
    func updateAccount(id: Int64, input: AccountInput) throws {
        try db.transaction { () throws -> Void in
            let made = try self.ledgerNow()
            if !made.accountEdit(accountId: id).found {
                throw WriteRefusal(message: String(localized: "write.accountGone"))
            }
            let problems: [FieldProblemData] = made.accountEditProblems(accountId: id, input: input)
            if let first = problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            let rows = BackupRows.shared
            let columns = rows.columns(table: "accounts")
            let kinds = rows.columnKinds(table: "accounts")
            var sets: [String] = []
            var positions: [Int] = []
            for (index, name) in columns.enumerated() where EditBridge.shared.ownsAccountColumn(column: Int32(index)) {
                sets.append("\"\(name)\" = ?")
                positions.append(index)
            }
            let statement = try self.db.prepare("UPDATE accounts SET \(sets.joined(separator: ", ")) WHERE id = ?")
            for (slot, index) in positions.enumerated() {
                let value: String? = made.editedAccountCell(accountId: id, input: input, column: Int32(index))
                try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
            }
            try statement.bindInt(Int32(positions.count + 1), id)
            _ = try statement.step()
        }
    }

    /// Archives an account (it leaves lists and totals, its entries stay) or restores it. Nothing is deleted.
    func setAccountArchived(id: Int64, archived: Bool) throws {
        try db.transaction { () throws -> Void in
            if !(try self.exists("SELECT 1 FROM accounts WHERE id = ?", id)) {
                throw WriteRefusal(message: String(localized: "write.accountGone"))
            }
            let statement = try self.db.prepare("UPDATE accounts SET archived = ? WHERE id = ?")
            try statement.bindInt(1, archived ? 1 : 0)
            try statement.bindInt(2, id)
            _ = try statement.step()
        }
    }

    // MARK: People

    /// Renames a person and, when it changed, replaces what was owed when they were added (their lines not tied to an entry become
    /// one line, none when zero; the first line's date is kept). Splits and payments stay. Archiving is `setPersonArchived`.
    func updatePerson(id: Int64, name: String, theyOwe: Bool, amount: String) throws {
        try db.transaction { () throws -> Void in
            let made = try self.ledgerNow()
            if !made.personEdit(personId: id, hidden: false).found {
                throw WriteRefusal(message: String(localized: "write.personGone"))
            }
            let currency = made.currencyCode()
            let taken: [String] = made.otherPersonNames(personId: id)
            let problems = PersonBridge.shared.problems(name: name, theyOwe: theyOwe, amount: amount, takenNames: taken, currency: currency)
            if let first = problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            let rename = try self.db.prepare("UPDATE people SET name = ? WHERE id = ?")
            try rename.bindText(1, PersonBridge.shared.cleanName(name: name))
            try rename.bindInt(2, id)
            _ = try rename.step()
            let typed: Int64 = PersonBridge.shared.opening(name: name, theyOwe: theyOwe, amount: amount)
            let old: Int64 = made.startingBalanceOf(personId: id)
            if EditBridge.shared.startingChanged(old: old, typed: typed) {
                try self.writeStartingBalance(personId: id, amount: typed)
            }
        }
    }

    /// Replaces the lines not tied to an entry with one "Starting balance" line (none when [amount] is 0), dated like the first old line.
    private func writeStartingBalance(personId: Int64, amount: Int64) throws {
        var millis = Int64(Date().timeIntervalSince1970 * 1000)
        let first = try db.prepare("SELECT epochMillis FROM loan_entries WHERE personId = ? AND txId IS NULL ORDER BY epochMillis LIMIT 1")
        try first.bindInt(1, personId)
        if try first.step() {
            millis = first.columnInt(0) ?? millis
        }
        let clear = try db.prepare("DELETE FROM loan_entries WHERE personId = ? AND txId IS NULL")
        try clear.bindInt(1, personId)
        _ = try clear.step()
        if amount != 0 {
            let line = try db.prepare("INSERT INTO loan_entries (personId, amount, epochMillis, note, txId) VALUES (?, ?, ?, ?, NULL)")
            try line.bindInt(1, personId)
            try line.bindInt(2, amount)
            try line.bindInt(3, millis)
            try line.bindText(4, PersonBridge.shared.startingNote())
            _ = try line.step()
        }
    }

    /// Archives a person who is square (archived people leave Receivable, Payable and the pickers; the history stays) or restores one.
    func setPersonArchived(id: Int64, archived: Bool) throws {
        try db.transaction { () throws -> Void in
            let made = try self.ledgerNow()
            if !made.personEdit(personId: id, hidden: false).found {
                throw WriteRefusal(message: String(localized: "write.personGone"))
            }
            if archived && made.personArchiveBlocked(personId: id) {
                throw WriteRefusal(message: String(localized: "write.personNotSquare"))
            }
            let statement = try self.db.prepare("UPDATE people SET archived = ? WHERE id = ?")
            try statement.bindInt(1, archived ? 1 : 0)
            try statement.bindInt(2, id)
            _ = try statement.step()
        }
    }
}
