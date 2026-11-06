import Foundation
import KassaShared

/// The small settings that ride in a backup (the arrangement of Accounts, the budget month's first day) and Settings > Account to
/// person. Rules are :domain's, asked through `PhoneChoicesBridge` and `LedgerView`; this file only runs the SQL, each change in ONE
/// transaction.
extension LedgerStore {

    // MARK: Settings rows

    /// Sets (or, for nil, removes) one row of the key/value `settings` table. Call inside a transaction.
    private func writeSetting(_ key: String, _ value: String?) throws {
        if let text = value {
            let put = try db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)")
            try put.bindText(1, key)
            try put.bindText(2, text)
            _ = try put.step()
        } else {
            let drop = try db.prepare("DELETE FROM settings WHERE key = ?")
            try drop.bindText(1, key)
            _ = try drop.step()
        }
    }

    /// The saved arrangement of Accounts (`AccountArrangement.encode()` text), or nil.
    func accountArrangementText() throws -> String? {
        let statement = try db.prepare("SELECT value FROM settings WHERE key = 'accountArrangement'")
        guard try statement.step() else { return nil }
        let text = statement.columnText(0) ?? ""
        return text.isEmpty ? nil : text
    }

    /// Saves the arrangement of Accounts (a backup carries it); nil drops it.
    func setAccountArrangement(_ text: String?) throws {
        try db.transaction { () throws -> Void in
            try self.writeSetting("accountArrangement", text)
        }
    }

    /// The first day of the budget month, kept to 1 to 28 by the shared rule.
    func setMonthStartDay(_ day: Int) throws {
        let kept: Int32 = PhoneChoicesBridge.shared.clampMonthStart(day: Int32(day))
        try db.transaction { () throws -> Void in
            try self.writeSetting("monthStartDay", String(kept))
        }
    }

    // MARK: Account to person

    /// Turns the plain account [accountId] into a person named [name] with the same balance (one "Balance from ..." line when it is not
    /// zero) and archives the account with its history, as Android's `PeopleRepository.fromAccount`. Refuses (with `WriteRefusal`) what
    /// `LedgerView.convertProblem` refuses. Returns the person's id.
    @discardableResult
    func convertAccountToPerson(accountId: Int64, name: String) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let made = LedgerView(data: try self.readAll(), fallbackCurrency: nil)
            if let problem = made.convertProblem(accountId: accountId, name: name) {
                throw WriteRefusal(field: "NAME", message: problem)
            }
            let balance: Int64 = made.convertBalance(accountId: accountId)
            let accountName = try self.accountName(accountId)
            let insert = try self.db.prepare("INSERT INTO people (name, archived) VALUES (?, 0)")
            try insert.bindText(1, PhoneChoicesBridge.shared.convertName(name: name))
            _ = try insert.step()
            let personId = self.db.lastInsertRowId()
            if balance != 0 {
                let line = try self.db.prepare("INSERT INTO loan_entries (personId, amount, epochMillis, note, txId) VALUES (?, ?, ?, ?, NULL)")
                try line.bindInt(1, personId)
                try line.bindInt(2, balance)
                try line.bindInt(3, Int64(Date().timeIntervalSince1970 * 1000))
                try line.bindText(4, PhoneChoicesBridge.shared.convertNote(accountName: accountName))
                _ = try line.step()
            }
            let archive = try self.db.prepare("UPDATE accounts SET archived = 1 WHERE id = ?")
            try archive.bindInt(1, accountId)
            _ = try archive.step()
            return personId
        }
    }

    private func accountName(_ id: Int64) throws -> String {
        let statement = try db.prepare("SELECT name FROM accounts WHERE id = ?")
        try statement.bindInt(1, id)
        guard try statement.step() else { return "" }
        return statement.columnText(0) ?? ""
    }
}
