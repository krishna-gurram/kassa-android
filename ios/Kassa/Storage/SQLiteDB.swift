import Foundation
import SQLCipher

/// A failed SQLite call. `message` is SQLite's own text: it is for logs and tests, never for the screen (people are told
/// in Kassa's words, as on Android's `Failures`).
struct SQLiteError: Error {
    let code: Int32
    let message: String

    /// SQLCipher's answer to a wrong key (or a file that isn't a database): "file is not a database", SQLITE_NOTADB (26).
    var isNotADatabase: Bool { code == SQLITE_NOTADB }
}

/// A problem with what was asked of the wrapper itself (not SQLite's).
enum SQLiteUsageError: Error {
    case badKey
}

/// A small wrapper over the SQLCipher C API (`import SQLCipher`): open with a raw 256-bit key, run SQL, prepared statements
/// with bound values, and transactions. Not thread-safe by itself; `LedgerStore` owns one and uses it from one place at a time.
/// The Room schema declares no foreign keys, so none are switched on.
final class SQLiteDB {

    // Not `private`: `SQLiteStatement` below reads it.
    fileprivate var handle: OpaquePointer?

    /// Opens (or creates) the database at `path` and unlocks it with `key`, which must be 32 bytes. The key goes in as
    /// `x'<64 hex digits>'`, a raw key, so SQLCipher runs no key derivation on it.
    /// A wrong key does not fail at `PRAGMA key`; it fails on the first read, which is done here, so a wrong key (or a file
    /// that isn't a database) throws from `init` with `isNotADatabase` set.
    init(path: String, key: Data) throws {
        guard key.count == 32 else { throw SQLiteUsageError.badKey }
        var opened: OpaquePointer?
        let flags: Int32 = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let rc = sqlite3_open_v2(path, &opened, flags, nil)
        if rc != SQLITE_OK {
            let message = opened.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let opened = opened { _ = sqlite3_close_v2(opened) }
            throw SQLiteError(code: rc, message: message)
        }
        handle = opened
        do {
            let hex = key.map { String(format: "%02x", $0) }.joined()
            try exec("PRAGMA key = \"x'\(hex)'\"")
            try exec("SELECT count(*) FROM sqlite_master")
        } catch {
            close()
            throw error
        }
    }

    deinit {
        close()
    }

    /// Closes the connection now. Safe to call twice; later calls on this object throw.
    func close() {
        if let open = handle {
            _ = sqlite3_close_v2(open)
            handle = nil
        }
    }

    fileprivate func failure(_ code: Int32) -> SQLiteError {
        guard let open = handle else { return SQLiteError(code: code, message: "closed") }
        return SQLiteError(code: code, message: String(cString: sqlite3_errmsg(open)))
    }

    /// Runs one or more statements that return nothing you need.
    func exec(_ sql: String) throws {
        guard let open = handle else { throw SQLiteError(code: SQLITE_MISUSE, message: "closed") }
        let rc = sqlite3_exec(open, sql, nil, nil, nil)
        if rc != SQLITE_OK { throw failure(rc) }
    }

    func prepare(_ sql: String) throws -> SQLiteStatement {
        return try SQLiteStatement(db: self, sql: sql)
    }

    /// `BEGIN IMMEDIATE`, then `body`, then `COMMIT`. Anything thrown rolls back and is rethrown unchanged.
    func transaction<T>(_ body: () throws -> T) throws -> T {
        try exec("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try exec("COMMIT")
            return result
        } catch {
            _ = try? exec("ROLLBACK")
            throw error
        }
    }

    /// `PRAGMA user_version`.
    func userVersion() throws -> Int {
        let statement = try prepare("PRAGMA user_version")
        guard try statement.step() else { return 0 }
        return Int(statement.columnInt(0) ?? 0)
    }

    func setUserVersion(_ version: Int) throws {
        // PRAGMA takes no bound values; the number is ours.
        try exec("PRAGMA user_version = \(version)")
    }
}

/// One prepared statement. Binding positions and column positions are what SQLite uses: binds start at 1, columns at 0.
final class SQLiteStatement {

    private let db: SQLiteDB
    private var statement: OpaquePointer?

    // sqlite3_bind_text and _blob copy the bytes when given this (SQLITE_TRANSIENT is a macro Swift can't import).
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    fileprivate init(db: SQLiteDB, sql: String) throws {
        self.db = db
        guard let open = db.handle else { throw SQLiteError(code: SQLITE_MISUSE, message: "closed") }
        var prepared: OpaquePointer?
        let rc = sqlite3_prepare_v2(open, sql, -1, &prepared, nil)
        if rc != SQLITE_OK {
            if let prepared = prepared { sqlite3_finalize(prepared) }
            throw db.failure(rc)
        }
        statement = prepared
    }

    deinit {
        if let statement = statement { sqlite3_finalize(statement) }
    }

    // MARK: Binding

    func bindNull(_ index: Int32) throws {
        try check(sqlite3_bind_null(statement, index))
    }

    /// A nil value binds NULL.
    func bindInt(_ index: Int32, _ value: Int64?) throws {
        guard let value = value else { return try bindNull(index) }
        try check(sqlite3_bind_int64(statement, index, value))
    }

    /// A nil value binds NULL.
    func bindText(_ index: Int32, _ value: String?) throws {
        guard let value = value else { return try bindNull(index) }
        try check(sqlite3_bind_text(statement, index, value, -1, SQLiteStatement.transient))
    }

    /// 1 or 0.
    func bindBool(_ index: Int32, _ value: Bool) throws {
        try check(sqlite3_bind_int64(statement, index, value ? 1 : 0))
    }

    func bindBlob(_ index: Int32, _ value: Data) throws {
        if value.isEmpty {
            try check(sqlite3_bind_zeroblob(statement, index, 0))
            return
        }
        let rc: Int32 = value.withUnsafeBytes { raw in
            sqlite3_bind_blob(statement, index, raw.baseAddress, Int32(value.count), SQLiteStatement.transient)
        }
        try check(rc)
    }

    // MARK: Running

    /// Runs to the next row: true when there is one to read, false when the statement is done.
    func step() throws -> Bool {
        let rc = sqlite3_step(statement)
        if rc == SQLITE_ROW { return true }
        if rc == SQLITE_DONE { return false }
        throw db.failure(rc)
    }

    /// Makes the statement ready to run again and forgets the bound values.
    func reset() throws {
        _ = sqlite3_reset(statement)
        try check(sqlite3_clear_bindings(statement))
    }

    // MARK: Reading the current row

    func columnCount() -> Int32 {
        return sqlite3_column_count(statement)
    }

    func isNull(_ column: Int32) -> Bool {
        return sqlite3_column_type(statement, column) == SQLITE_NULL
    }

    /// nil for NULL.
    func columnInt(_ column: Int32) -> Int64? {
        if isNull(column) { return nil }
        return sqlite3_column_int64(statement, column)
    }

    /// nil for NULL.
    func columnText(_ column: Int32) -> String? {
        if isNull(column) { return nil }
        guard let pointer = sqlite3_column_text(statement, column) else { return "" }
        return String(cString: pointer)
    }

    /// nil for NULL; an empty blob is empty `Data`.
    func columnBlob(_ column: Int32) -> Data? {
        if isNull(column) { return nil }
        let count = Int(sqlite3_column_bytes(statement, column))
        guard count > 0, let pointer = sqlite3_column_blob(statement, column) else { return Data() }
        return Data(bytes: pointer, count: count)
    }

    private func check(_ rc: Int32) throws {
        if rc != SQLITE_OK { throw db.failure(rc) }
    }
}
