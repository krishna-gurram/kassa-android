import Foundation
import KassaShared

/// Why the store could not be opened or filled, apart from SQLite's own errors (`SQLiteError`) and the key's (`KeyUnreadable`).
enum LedgerStoreError: Error, Equatable {
    /// The database says it is from a newer Kassa (its `user_version` is above `Schema.version`).
    case newerDatabase
    /// An older database: there are no upgrade steps on iOS yet, so this never happens until the schema moves past 10.
    case unsupportedDatabase
    /// A cell from the shared Kotlin code or a stored value that does not fit its column.
    case badValue
}

/// The encrypted on-device database of the iOS app: SQLCipher, one file in Application Support, tables identical to
/// Android's Room schema 10 (`Schema`), plus two Swift-only tables: `settings` (key/value text: `createdAt`, `monthStartDay`,
/// `currency`, `accountArrangement`, the values a backup carries outside the Room tables) and `icons` (category pictures,
/// name and bytes). It restores from a `BackupData` and exports one, and `LedgerStore+Write.swift` adds the write path (start fresh,
/// accounts, people, entries) whose rules come from :domain through :shared.
///
/// The data crosses from and to Kotlin as flat text cells (`BackupRows`, `BackupBuilder` in :shared), read by the column
/// kinds that `BackupRows` reports, so no Kotlin class is walked from Swift.
final class LedgerStore {

    static let fileName = "kassa.db"

    let databaseURL: URL

    /// For tests only: runs inside `addEntry`'s transaction right after the row is written; a throw there must roll the row back.
    var afterInsertHook: (() throws -> Void)?
    // Not `private`: `LedgerStore+Write.swift` (the write path) is an extension in its own file.
    let db: SQLiteDB

    /// Opens the database in `directory`, making it (and the key, when there is no data yet) if needed.
    /// Throws `KeyUnreadable` when a database is there and its key is not, `SQLiteError` (with `isNotADatabase`) when the key
    /// is wrong, `LedgerStoreError` for a database from another version.
    init(directory: URL, keyStore: KeyStore) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        let url = directory.appendingPathComponent(LedgerStore.fileName)
        // SQLite makes an empty file when it opens a new path, so "there is data" means a file with something in it.
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let key = try DatabaseKey.get(store: keyStore, databaseExists: size > 0)
        let opened = try SQLiteDB(path: url.path, key: key)
        try LedgerStore.prepareSchema(opened)
        databaseURL = url
        db = opened
        LedgerStore.protect(directory: directory, file: url)
    }

    /// The store the app uses: Application Support/Kassa, with the key in the Keychain.
    static func openDefault() throws -> LedgerStore {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try LedgerStore(directory: support.appendingPathComponent("Kassa", isDirectory: true), keyStore: KeychainKeyStore())
    }

    // MARK: Opening

    private static func prepareSchema(_ db: SQLiteDB) throws {
        let version = try db.userVersion()
        if version == Schema.version { return }
        if version > Schema.version { throw LedgerStoreError.newerDatabase }
        if version != 0 { throw LedgerStoreError.unsupportedDatabase }
        try db.transaction { () throws -> Void in
            for sql in Schema.statements {
                try db.exec(sql)
            }
            try db.setUserVersion(Schema.version)
        }
    }

    /// Not in iCloud or device backups (the file is useless without this phone's key; a .kassa backup is how data moves), and
    /// readable once the phone has been unlocked after a restart. Best effort: the data is encrypted either way, and a
    /// failure here must not stop the app from opening it.
    private static func protect(directory: URL, file: URL) {
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var directoryURL = directory
        var fileURL = file
        _ = try? directoryURL.setResourceValues(excluded)
        _ = try? fileURL.setResourceValues(excluded)
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        _ = try? FileManager.default.setAttributes(attributes, ofItemAtPath: directory.path)
        _ = try? FileManager.default.setAttributes(attributes, ofItemAtPath: file.path)
    }

    // MARK: Facts

    /// `PRAGMA user_version`: 10 for this code.
    func schemaVersion() throws -> Int {
        return try db.userVersion()
    }

    /// Rows in one of the tables of `Schema`.
    func rowCount(table: String) throws -> Int {
        let statement = try db.prepare("SELECT count(*) FROM \(table)")
        guard try statement.step() else { return 0 }
        return Int(statement.columnInt(0) ?? 0)
    }

    // MARK: Restore

    /// Replaces everything with `data`, in one transaction: all rows of every table, the settings and the pictures are
    /// deleted, then `data` is inserted. If anything fails (a duplicate id, a value that does not fit) nothing changes.
    /// [currency] (a code Kassa lists) is stored instead of the backup's own, which is how a backup without a currency gets the one
    /// the person picked; nil keeps the backup's.
    func replace(with data: BackupData, currency: String? = nil) throws {
        try db.transaction { () throws -> Void in
            try clearAll()
            try insertRows(data)
            try insertSettings(data, currency: currency)
            try insertIcons(data)
        }
    }

    private func clearAll() throws {
        for table in Schema.dataTables.reversed() {
            try db.exec("DELETE FROM \(table)")
        }
        // AUTOINCREMENT counters, so a restored database numbers new rows from its own largest id like a new one.
        try db.exec("DELETE FROM sqlite_sequence")
        try db.exec("DELETE FROM settings")
        try db.exec("DELETE FROM icons")
    }

    private func insertRows(_ data: BackupData) throws {
        let rows = BackupRows.shared
        for table in rows.tableNames() {
            let count = Int(rows.rowCount(data: data, table: table))
            if count == 0 { continue }
            let columns = rows.columns(table: table)
            let kinds = rows.columnKinds(table: table)
            let names = columns.map { "\"\($0)\"" }.joined(separator: ", ")
            let marks = Array(repeating: "?", count: columns.count).joined(separator: ", ")
            let statement = try db.prepare("INSERT INTO \(table) (\(names)) VALUES (\(marks))")
            for row in 0..<count {
                try statement.reset()
                for column in 0..<columns.count {
                    let value = rows.cell(data: data, table: table, row: Int32(row), column: Int32(column))
                    try LedgerStore.bind(statement, Int32(column + 1), kind: kinds[column], value: value)
                }
                _ = try statement.step()
            }
        }
    }

    static func bind(_ statement: SQLiteStatement, _ index: Int32, kind: String, value: String?) throws {
        guard let text = value else {
            try statement.bindNull(index)
            return
        }
        if kind == "int" {
            guard let number = Int64(text) else { throw LedgerStoreError.badValue }
            try statement.bindInt(index, number)
        } else {
            try statement.bindText(index, text)
        }
    }

    private func insertSettings(_ data: BackupData, currency chosen: String?) throws {
        let statement = try db.prepare("INSERT INTO settings (key, value) VALUES (?, ?)")
        var pairs: [(String, String)] = [
            ("createdAt", String(data.createdAt)),
            ("monthStartDay", String(data.monthStartDay)),
        ]
        if let currency = chosen ?? data.currency { pairs.append(("currency", currency)) }
        if let arrangement = data.accountArrangement { pairs.append(("accountArrangement", arrangement)) }
        for pair in pairs {
            try statement.reset()
            try statement.bindText(1, pair.0)
            try statement.bindText(2, pair.1)
            _ = try statement.step()
        }
    }

    private func insertIcons(_ data: BackupData) throws {
        let rows = BackupRows.shared
        let statement = try db.prepare("INSERT INTO icons (name, bytes) VALUES (?, ?)")
        for name in rows.iconNames(data: data) {
            guard let text = rows.icon(data: data, name: name) else { continue }
            var bytes = Data()
            if !text.isEmpty {
                guard let decoded = Data(base64Encoded: text) else { throw LedgerStoreError.badValue }
                bytes = decoded
            }
            try statement.reset()
            try statement.bindText(1, name)
            try statement.bindBlob(2, bytes)
            _ = try statement.step()
        }
    }

    // MARK: Export

    /// Everything in the store as a `BackupData`, in one read transaction. Rows come back in id order, pictures by name.
    func export() throws -> BackupData {
        return try db.transaction { () throws -> BackupData in
            return try self.readAll()
        }
    }

    private func readSettings() throws -> [String: String] {
        var result: [String: String] = [:]
        let statement = try db.prepare("SELECT key, value FROM settings")
        while try statement.step() {
            if let key = statement.columnText(0), let value = statement.columnText(1) {
                result[key] = value
            }
        }
        return result
    }

    /// Everything as a `BackupData`, without opening a transaction (the caller is inside one, or `export()` is).
    func readAll() throws -> BackupData {
        let rows = BackupRows.shared
        let settings = try readSettings()
        let createdAt = Int64(settings["createdAt"] ?? "") ?? Int64(Date().timeIntervalSince1970 * 1000)
        let monthStartDay = Int32(settings["monthStartDay"] ?? "") ?? 1
        let builder = BackupBuilder(
            createdAt: createdAt,
            monthStartDay: monthStartDay,
            currency: settings["currency"],
            accountArrangement: settings["accountArrangement"]
        )
        for table in rows.tableNames() {
            let columns = rows.columns(table: table)
            let kinds = rows.columnKinds(table: table)
            let names = columns.map { "\"\($0)\"" }.joined(separator: ", ")
            let statement = try db.prepare("SELECT \(names) FROM \(table) ORDER BY rowid")
            while try statement.step() {
                for column in 0..<columns.count {
                    let position = Int32(column)
                    var value: String?
                    if kinds[column] == "int" {
                        value = statement.columnInt(position).map { String($0) }
                    } else {
                        value = statement.columnText(position)
                    }
                    try builder.setCell(table: table, column: position, value: value)
                }
                try builder.endRow(table: table)
            }
        }
        let icons = try db.prepare("SELECT name, bytes FROM icons ORDER BY name")
        while try icons.step() {
            if let name = icons.columnText(0), let bytes = icons.columnBlob(1) {
                try builder.addIcon(name: name, base64: bytes.base64EncodedString())
            }
        }
        return builder.build()
    }
}
