import Foundation
import KassaShared

/// What reading a picked file came to.
enum ImportRead {
    /// A Kassa backup (.kassa): the caller restores it the usual way.
    case kassa
    /// A Money Manager or Cashew backup, opened into a session that asks its questions and holds the plan.
    case session(ImportSession)
    /// A file Kassa can't read, with the sentence for the person (never SQLite's or Kotlin's own text).
    case refused(String)
}

/// Opens a file picked on the Import page. The kind of file is worked out from what is inside it (`PickedFileRules`, :importer), the
/// file is copied to a temporary place first and opened read-only without a key (it is a plain SQLite database from another app,
/// never written to), the queries :importer lists run into a `SqlResults`, and the copy is deleted again whatever happens.
/// All the reading rules (which column means what) are Kotlin's; this only runs SQL. Android's equivalents are `PickedFile`,
/// `AndroidMoneyManagerSource` and `AndroidCashewSource`.
enum ImportReader {

    /// [currency] is the phone's currency code or "". Throws for a file that could not be copied or a row that could not be read
    /// (the caller words it with `Failures.importFile`).
    static func read(_ url: URL, currency: String, zone: String) throws -> ImportRead {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-import-" + UUID().uuidString + ".db")
        defer { removeCopy(copy) }
        try FileManager.default.copyItem(at: url, to: copy)
        return try read(copy: copy, currency: currency, zone: zone)
    }

    /// Reads [copy], a file this app owns (so tests can hand over one they made).
    static func read(copy: URL, currency: String, zone: String) throws -> ImportRead {
        let size = (try? copy.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let head = try headText(copy)
        let kind = ImportBridge.shared.kindOfHead(head: head, size: Int64(size))
        if kind == "KASSA" || kind == "KASSA_PROTECTED" { return ImportRead.kassa }
        if kind != "SQLITE" { return ImportRead.refused(ImportBridge.shared.refusal(kind: kind)) }

        let db: SQLiteDB
        do {
            db = try SQLiteDB(readOnlyPath: copy.path)
        } catch {
            return ImportRead.refused(ImportBridge.shared.damaged())
        }
        defer { db.close() }
        let tables: [String]
        do {
            tables = try tableNames(db)
        } catch {
            return ImportRead.refused(ImportBridge.shared.damaged())
        }
        let found = ImportBridge.shared.kindOfTables(tables: tables)
        let results = SqlResults()
        if found == "MONEY_MANAGER" {
            let iphone = ImportBridge.shared.isIphoneLayout(tables: tables)
            try run(ImportBridge.shared.moneyManagerQueries(iphone: iphone), on: db, into: results)
            let opened = try ImportBridge.shared.openMoneyManager(results: results, iphone: iphone, zoneId: zone, currency: currency)
            return try outcome(opened)
        }
        if found == "CASHEW" {
            // A file cut short still opens; checking the pages catches it before any numbers are read.
            if try quickCheck(db) != "ok" { return ImportRead.refused(ImportBridge.shared.damaged()) }
            try run(ImportBridge.shared.cashewQueries(), on: db, into: results)
            let opened = try ImportBridge.shared.openCashew(results: results, zoneId: zone, currency: currency)
            return try outcome(opened)
        }
        return ImportRead.refused(ImportBridge.shared.refusal(kind: found))
    }

    private static func outcome(_ opened: ImportOpened) throws -> ImportRead {
        if let session = opened.session { return ImportRead.session(session) }
        return ImportRead.refused(opened.refusal)
    }

    // MARK: Pieces

    /// The first 16 bytes as Latin-1 text (each byte its own character), zero-padded by the Kotlin rule.
    static func headText(_ file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let bytes: Data = handle.readData(ofLength: 16)
        return String(data: bytes, encoding: String.Encoding.isoLatin1) ?? ""
    }

    static func tableNames(_ db: SQLiteDB) throws -> [String] {
        let statement = try db.prepare("SELECT name FROM sqlite_master WHERE type='table'")
        var names: [String] = []
        while try statement.step() {
            if let name = statement.columnText(0) { names.append(name) }
        }
        return names
    }

    static func quickCheck(_ db: SQLiteDB) throws -> String? {
        let statement = try db.prepare("PRAGMA quick_check")
        guard try statement.step() else { return nil }
        return statement.columnText(0)
    }

    /// Runs every query into [results]; an optional one that fails (an older backup lacks the table or column) is reported as skipped.
    static func run(_ queries: [SqlQuery], on db: SQLiteDB, into results: SqlResults) throws {
        for query in queries {
            do {
                try fill(query, db, results)
            } catch {
                if query.optional {
                    results.skip(name: query.name)
                } else {
                    throw error
                }
            }
        }
    }

    private static func fill(_ query: SqlQuery, _ db: SQLiteDB, _ results: SqlResults) throws {
        let statement = try db.prepare(query.sql)
        results.begin(name: query.name)
        let count: Int32 = statement.columnCount()
        var column: Int32 = 0
        while column < count {
            results.addColumn(name: statement.columnName(column))
            column += 1
        }
        while try statement.step() {
            var position: Int32 = 0
            while position < count {
                addCell(statement, position, results)
                position += 1
            }
            results.endRow()
        }
        results.end()
    }

    private static func addCell(_ statement: SQLiteStatement, _ column: Int32, _ results: SqlResults) {
        let kind = statement.columnValueKind(column)
        if kind == SQLiteValueKind.null {
            results.addNull()
        } else if kind == SQLiteValueKind.integer {
            results.addInteger(value: statement.columnInt(column) ?? 0)
        } else if kind == SQLiteValueKind.real {
            results.addReal(value: statement.columnDouble(column))
        } else {
            results.addText(value: statement.columnText(column) ?? "")
        }
    }

    private static func removeCopy(_ copy: URL) {
        let fileManager = FileManager.default
        try? fileManager.removeItem(at: copy)
        // SQLite may have made side files next to a copy that was in write-ahead mode.
        try? fileManager.removeItem(atPath: copy.path + "-wal")
        try? fileManager.removeItem(atPath: copy.path + "-shm")
        try? fileManager.removeItem(atPath: copy.path + "-journal")
    }
}
