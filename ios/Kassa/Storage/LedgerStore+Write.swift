import Foundation
import KassaShared

/// A refusal the person is told in a sentence: a rule of :domain ("Pick a category.", "Old savings is closed. Reopen it first to add
/// to it.") or the store's own plain line. Raw SQLite text never reaches the screen; anything else a write throws is shown as
/// "Couldn't save. Try again." (see `AppModel`).
struct WriteRefusal: Error, Equatable {
    /// The input the sentence is about: an `EntryField`, `AccountField` or `PersonField` name, or "" when it is about the whole save.
    let field: String
    let message: String

    init(field: String = "", message: String) {
        self.field = field
        self.message = message
    }
}

/// The write path of the iOS app: first-run setup, accounts, people and entries. Every rule (what is allowed, what a refusal says,
/// what gets stored) is :domain's, asked through `EntryBridge`, `AccountBridge`, `PersonBridge` and `StartBridge` in :shared; this file
/// only runs the SQL, each change in ONE transaction so a refusal or a failure leaves nothing behind, and mirrors what Android's
/// repositories write (`EntryRepository`, `AccountRepository`, `PeopleRepository`, `CategoryRepository`).
extension LedgerStore {

    // MARK: Settings

    /// The currency code the ledger shows ("USD"), or nil when none was ever chosen or restored.
    func currencyCode() throws -> String? {
        let statement = try db.prepare("SELECT value FROM settings WHERE key = 'currency'")
        guard try statement.step() else { return nil }
        let text = statement.columnText(0) ?? ""
        return text.isEmpty ? nil : text
    }

    /// Remembers the currency (a code Kassa lists). Amounts are never converted, so changing it later changes only the symbol.
    func setCurrency(_ code: String) throws {
        try db.transaction { () throws -> Void in
            try self.writeCurrency(code)
        }
    }

    private func writeCurrency(_ code: String) throws {
        let statement = try db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('currency', ?)")
        try statement.bindText(1, code)
        _ = try statement.step()
    }

    // MARK: First run

    /// "Start fresh": the currency (when given) and the starter categories the person ticked, in one transaction. Nothing else is
    /// created; accounts come from Add account.
    func startFresh(currency: String?, categories names: [String]) throws {
        try db.transaction { () throws -> Void in
            if let code = currency { try self.writeCurrency(code) }
            let first = try self.nextSortOrder(table: "categories")
            let count = Int(StartBridge.shared.categoryCount(names: names))
            for row in 0..<count {
                _ = try self.insertRow(table: "categories") { column in
                    return StartBridge.shared.categoryCell(names: names, row: Int32(row), column: column, firstSortOrder: Int32(first))
                }
            }
        }
    }

    /// One more than the largest `sortOrder` of [table] (0 for none), as Android's `maxSortOrder() + 1`.
    private func nextSortOrder(table: String) throws -> Int {
        let statement = try db.prepare("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM \(table)")
        guard try statement.step() else { return 0 }
        return Int(statement.columnInt(0) ?? 0)
    }

    // MARK: Rows

    /// Inserts one row into [table] with every column but `id` (the database numbers it), each cell from [cell] (by the column's
    /// position in `BackupRows`) and bound by the column's kind. Returns the new id.
    private func insertRow(table: String, cell: (Int32) -> String?) throws -> Int64 {
        let rows = BackupRows.shared
        let columns = rows.columns(table: table)
        let kinds = rows.columnKinds(table: table)
        var names: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where name != "id" {
            names.append("\"\(name)\"")
            positions.append(index)
        }
        let marks = Array(repeating: "?", count: names.count).joined(separator: ", ")
        let statement = try db.prepare("INSERT INTO \(table) (\(names.joined(separator: ", "))) VALUES (\(marks))")
        for (slot, index) in positions.enumerated() {
            let value = cell(Int32(index))
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        _ = try statement.step()
        return db.lastInsertRowId()
    }

    // MARK: Accounts and people

    /// Adds an account the form describes. Refuses (with `WriteRefusal`) what `AccountForm` refuses, then writes it last in the order.
    /// Returns its id.
    @discardableResult
    func addAccount(_ input: AccountInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let taken = try self.names(table: "accounts")
            let problems = AccountBridge.shared.problems(input: input, takenNames: taken, currency: currency)
            if let first = problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            let order = try self.nextSortOrder(table: "accounts")
            return try self.insertRow(table: "accounts") { column in
                return AccountBridge.shared.cell(input: input, column: column, sortOrder: Int32(order))
            }
        }
    }

    /// Adds a person, with money already open between you when [theyOwe] and [amount] say so (one "Starting balance" line, as
    /// Android's `PeopleRepository.add`). Returns the person's id.
    @discardableResult
    func addPerson(name: String, theyOwe: Bool, amount: String) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let currency = try self.currencyCode() ?? ""
            let taken = try self.names(table: "people")
            let problems = PersonBridge.shared.problems(name: name, theyOwe: theyOwe, amount: amount, takenNames: taken, currency: currency)
            if let first = problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            let opening = PersonBridge.shared.opening(name: name, theyOwe: theyOwe, amount: amount)
            let insert = try self.db.prepare("INSERT INTO people (name, archived) VALUES (?, 0)")
            try insert.bindText(1, PersonBridge.shared.cleanName(name: name))
            _ = try insert.step()
            let id = self.db.lastInsertRowId()
            if opening != 0 {
                let line = try self.db.prepare("INSERT INTO loan_entries (personId, amount, epochMillis, note, txId) VALUES (?, ?, ?, ?, NULL)")
                try line.bindInt(1, id)
                try line.bindInt(2, opening)
                try line.bindInt(3, Int64(Date().timeIntervalSince1970 * 1000))
                try line.bindText(4, PersonBridge.shared.startingNote())
                _ = try line.step()
            }
            return id
        }
    }

    private func names(table: String) throws -> [String] {
        var result: [String] = []
        let statement = try db.prepare("SELECT name FROM \(table)")
        while try statement.step() {
            if let name = statement.columnText(0) { result.append(name) }
        }
        return result
    }

    // MARK: Entries

    /// What the store knows of a stored entry, to check an edit against.
    private struct StoredEntry {
        let kind: String
        let accountId: Int64
        let toAccountId: Int64
        let editable: Bool
    }

    private func accountType(_ id: Int64) throws -> String {
        if id <= 0 { return "" }
        let statement = try db.prepare("SELECT type FROM accounts WHERE id = ?")
        try statement.bindInt(1, id)
        guard try statement.step() else { return "" }
        return statement.columnText(0) ?? ""
    }

    private func exists(_ sql: String, _ id: Int64) throws -> Bool {
        let statement = try db.prepare(sql)
        try statement.bindInt(1, id)
        return try statement.step()
    }

    private func storedEntry(_ id: Int64) throws -> StoredEntry? {
        let statement = try db.prepare("SELECT kind, accountId, toAccountId, personId, myShare FROM transactions WHERE id = ?")
        try statement.bindInt(1, id)
        guard try statement.step() else { return nil }
        let kind = statement.columnText(0) ?? ""
        let account = statement.columnInt(1) ?? 0
        let to = statement.columnInt(2) ?? 0
        let hasPerson = statement.columnInt(3) != nil
        let hasShare = statement.columnInt(4) != nil
        var isSplit = hasShare
        if !isSplit { isSplit = try exists("SELECT 1 FROM loan_entries WHERE txId = ?", id) }
        let inLoanPayment = try exists(
            "SELECT 1 FROM loan_payments WHERE principalEntryId = ?1 OR interestEntryId = ?1 OR otherEntryId = ?1", id
        )
        let toType = try accountType(to)
        let editable = EntryBridge.shared.isEditable(
            kind: kind, toAccountType: toType, belongsToLoanPayment: inLoanPayment, hasPerson: hasPerson, isSplit: isSplit
        )
        return StoredEntry(kind: kind, accountId: account, toAccountId: to, editable: editable)
    }

    /// Refuses what `EntryRules` refuses: the first problem of the entry, then an account that is closed and newly touched.
    private func check(_ input: EntryInput, before: StoredEntry?) throws {
        let currency = try currencyCode() ?? ""
        if let problem = EntryBridge.shared.problem(input: input, currency: currency) {
            throw WriteRefusal(field: problem.field, message: problem.message)
        }
        let beforeAccount = before?.accountId ?? 0
        let beforeTo = before?.toAccountId ?? 0
        let wanted = EntryBridge.shared.mustBeOpen(input: input, beforeAccountId: beforeAccount, beforeToAccountId: beforeTo)
        for text in wanted {
            guard let id = Int64(text) else { continue }
            let statement = try db.prepare("SELECT name FROM accounts WHERE id = ? AND closedDay IS NOT NULL")
            try statement.bindInt(1, id)
            if try statement.step() {
                let name = statement.columnText(0) ?? ""
                throw WriteRefusal(message: EntryBridge.shared.closedMessage(name: name))
            }
        }
    }

    /// Adds an expense, income or transfer. Checks first (`EntryRules`), writes the row, and for a transfer into an investment
    /// account also the waiting row Overview will ask about; all in one transaction. Returns the new entry's id.
    @discardableResult
    func addEntry(_ input: EntryInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            try self.check(input, before: nil)
            let id = try self.insertRow(table: "transactions") { column in
                return EntryBridge.shared.cell(input: input, column: column)
            }
            try self.afterInsertHook?()
            let toType = try self.accountType(input.toAccountId)
            if EntryBridge.shared.waitsForExplanation(kind: input.kind, toAccountType: toType) {
                let wait = try self.db.prepare(
                    "INSERT INTO investment_transfers (transactionId, accountId, amount, epochMillis) VALUES (?, ?, ?, ?)"
                )
                try wait.bindInt(1, id)
                try wait.bindInt(2, input.toAccountId)
                try wait.bindInt(3, input.amount)
                try wait.bindInt(4, input.epochMillis)
                _ = try wait.step()
            }
            return id
        }
    }

    /// Saves an edit of entry `input.id`: only the columns the page owns change (kind, amount, accounts, category, date, note), so
    /// an imported entry keeps its external id. A transfer into investments that still waits to be explained follows the entry.
    func updateEntry(_ input: EntryInput) throws {
        try db.transaction { () throws -> Void in
            guard let before = try self.storedEntry(input.id) else {
                throw WriteRefusal(message: String(localized: "write.entryGone"))
            }
            if !before.editable { throw WriteRefusal(message: String(localized: "write.entryNotHere")) }
            try self.check(input, before: before)
            try self.updateRow(input)
            let toType = try self.accountType(input.toAccountId)
            if EntryBridge.shared.waitsForExplanation(kind: input.kind, toAccountType: toType) {
                let wait = try self.db.prepare(
                    "UPDATE investment_transfers SET accountId = ?, amount = ?, epochMillis = ? WHERE transactionId = ?"
                )
                try wait.bindInt(1, input.toAccountId)
                try wait.bindInt(2, input.amount)
                try wait.bindInt(3, input.epochMillis)
                try wait.bindInt(4, input.id)
                _ = try wait.step()
            } else {
                try self.deleteWaiting(input.id)
            }
        }
    }

    private func updateRow(_ input: EntryInput) throws {
        let rows = BackupRows.shared
        let columns = rows.columns(table: "transactions")
        let kinds = rows.columnKinds(table: "transactions")
        var sets: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where EntryBridge.shared.ownsColumn(column: Int32(index)) {
            sets.append("\"\(name)\" = ?")
            positions.append(index)
        }
        let statement = try db.prepare("UPDATE transactions SET \(sets.joined(separator: ", ")) WHERE id = ?")
        for (slot, index) in positions.enumerated() {
            let value = EntryBridge.shared.cell(input: input, column: Int32(index))
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        try statement.bindInt(Int32(positions.count + 1), input.id)
        _ = try statement.step()
    }

    private func deleteWaiting(_ id: Int64) throws {
        let statement = try db.prepare("DELETE FROM investment_transfers WHERE transactionId = ?")
        try statement.bindInt(1, id)
        _ = try statement.step()
    }

    /// Deletes an entry the page may edit, with its waiting investment row (and any split line, as Android's delete does).
    func deleteEntry(id: Int64) throws {
        try db.transaction { () throws -> Void in
            guard let stored = try self.storedEntry(id) else {
                throw WriteRefusal(message: String(localized: "write.entryGone"))
            }
            if !stored.editable { throw WriteRefusal(message: String(localized: "write.entryNotHere")) }
            let lines = try self.db.prepare("DELETE FROM loan_entries WHERE txId = ?")
            try lines.bindInt(1, id)
            _ = try lines.step()
            try self.deleteWaiting(id)
            let row = try self.db.prepare("DELETE FROM transactions WHERE id = ?")
            try row.bindInt(1, id)
            _ = try row.step()
        }
    }
}
