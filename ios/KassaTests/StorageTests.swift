import XCTest
import KassaShared
@testable import Kassa

/// An in-memory key store, so tests never touch the Keychain.
final class MemoryKeyStore: KeyStore {
    var key: Data?
    var writes = 0

    func read() throws -> Data? { return key }

    func write(_ key: Data) throws {
        self.key = key
        writes += 1
    }

    func delete() throws { key = nil }
}

/// The encrypted store: restoring the golden backup, exporting it, what is on disk, and what happens to a wrong key or a
/// failed restore.
final class StorageTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-storage-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func goldenData() throws -> BackupData {
        let url = try XCTUnwrap(Bundle(for: StorageTests.self).url(forResource: "plain", withExtension: "kassa"), "missing plain.kassa")
        let json = try BackupFile.open(try Data(contentsOf: url), password: nil)
        return try BackupBridge.shared.parse(text: json)
    }

    private func openStore() throws -> LedgerStore {
        return try LedgerStore(directory: directory, keyStore: keys)
    }

    /// A tiny backup made through the same builder the store exports with.
    private func smallData(currency: String?, extraGroup: Bool = false) throws -> BackupData {
        let builder = BackupBuilder(createdAt: 5, monthStartDay: 15, currency: currency, accountArrangement: nil)
        try addRow(builder, "people", ["1", "Sam", "0"])
        if extraGroup {
            try addRow(builder, "account_groups", ["1", "G", "0"])
        }
        return builder.build()
    }

    private func addRow(_ builder: BackupBuilder, _ table: String, _ cells: [String?]) throws {
        for (index, value) in cells.enumerated() {
            try builder.setCell(table: table, column: Int32(index), value: value)
        }
        try builder.endRow(table: table)
    }

    private func assertSame(_ a: BackupData, _ b: BackupData, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(BackupBridge.shared.write(data: a), BackupBridge.shared.write(data: b), file: file, line: line)
        XCTAssertEqual(BackupBridge.shared.digest(data: a), BackupBridge.shared.digest(data: b), file: file, line: line)
    }

    // MARK: Restore and export

    func testRestoringTheGoldenBackupAndExportingGivesTheSameData() throws {
        let original = try goldenData()
        let store = try openStore()
        try store.replace(with: original)
        assertSame(try store.export(), original)
        XCTAssertEqual(try store.rowCount(table: "accounts"), 3)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 3)
        XCTAssertEqual(try store.rowCount(table: "icons"), 1)
    }

    func testTheSwiftTablesMatchTheKotlinCellApi() {
        XCTAssertEqual(BackupRows.shared.tableNames(), Schema.dataTables)
        for table in Schema.dataTables {
            XCTAssertEqual(BackupRows.shared.columns(table: table).count, BackupRows.shared.columnKinds(table: table).count, table)
        }
    }

    func testDataSurvivesClosingAndReopeningWithTheStoredKey() throws {
        let original = try goldenData()
        do {
            let store = try openStore()
            try store.replace(with: original)
        }
        XCTAssertEqual(keys.writes, 1, "one key, made once")
        let again = try openStore()
        XCTAssertEqual(keys.writes, 1)
        assertSame(try again.export(), original)
    }

    func testAnEmptyStoreExportsAnEmptyBackup() throws {
        let store = try openStore()
        let exported = try store.export()
        XCTAssertEqual(BackupBridge.shared.summary(data: exported), "0 entries, 0 accounts, 0 categories")
        XCTAssertEqual(BackupBridge.shared.digest(data: exported).components(separatedBy: "\n").filter { $0 == "monthStartDay=1" }.count, 1)
    }

    func testExportThenMakeOpenAndParseGivesTheSameDataAgain() throws {
        let original = try goldenData()
        let store = try openStore()
        try store.replace(with: original)
        let exported = try store.export()
        let bytes = try BackupFile.make(json: BackupBridge.shared.write(data: exported), password: "pw")
        let reopened = try BackupFile.open(bytes, password: "pw")
        let parsed = try BackupBridge.shared.parse(text: reopened)
        assertSame(parsed, original)
    }

    // MARK: Schema

    func testTheUserVersionIsTen() throws {
        let store = try openStore()
        XCTAssertEqual(Schema.version, 10)
        XCTAssertEqual(try store.schemaVersion(), 10)
        let again = try openStore()
        XCTAssertEqual(try again.schemaVersion(), 10)
    }

    // MARK: Encryption

    func testTheFileOnDiskIsNotPlaintext() throws {
        let store = try openStore()
        try store.replace(with: try goldenData())
        let bytes = try Data(contentsOf: store.databaseURL)
        XCTAssertGreaterThan(bytes.count, 4096)
        // Short strings are left out on purpose: a 3-byte word turns up by chance in about 1 of 100 random files of this size.
        for text in ["SQLite format 3", "Checking", "Car loan", "Salary"] {
            XCTAssertNil(bytes.range(of: Data(text.utf8)), "found \(text) in the file")
        }
    }

    func testTheRightKeyOpensAndAWrongKeyDoesNot() throws {
        let store = try openStore()
        try store.replace(with: try goldenData())
        let path = store.databaseURL.path
        let key = try XCTUnwrap(keys.key)

        let right = try SQLiteDB(path: path, key: key)
        XCTAssertEqual(try right.userVersion(), 10)
        right.close()

        var wrong = key
        wrong[0] ^= 0x01
        XCTAssertThrowsError(try SQLiteDB(path: path, key: wrong)) { error in
            guard let failure = error as? SQLiteError else { return XCTFail("unexpected \(error)") }
            XCTAssertTrue(failure.isNotADatabase, "code \(failure.code)")
        }
        XCTAssertThrowsError(try SQLiteDB(path: path, key: Data(repeating: 7, count: 31)))

        // The same through the store: its key store holds the wrong key.
        let other = MemoryKeyStore()
        other.key = wrong
        XCTAssertThrowsError(try LedgerStore(directory: directory, keyStore: other))
        XCTAssertEqual(other.key, wrong, "a wrong key is never replaced")
    }

    func testALostKeyNeverMakesANewOneOverExistingData() throws {
        let store = try openStore()
        try store.replace(with: try goldenData())
        let empty = MemoryKeyStore()
        XCTAssertThrowsError(try LedgerStore(directory: directory, keyStore: empty)) { error in
            XCTAssertTrue(error is KeyUnreadable, "\(error)")
        }
        XCTAssertNil(empty.key)
        XCTAssertEqual(empty.writes, 0)
        // A key of the wrong length counts as unusable too.
        let short = MemoryKeyStore()
        short.key = Data(repeating: 1, count: 5)
        XCTAssertThrowsError(try LedgerStore(directory: directory, keyStore: short)) { error in
            XCTAssertTrue(error is KeyUnreadable, "\(error)")
        }
        XCTAssertEqual(short.key, Data(repeating: 1, count: 5))
    }

    func testTheKeyPolicyIsAndroids() {
        XCTAssertEqual(KeyPolicy.decide(databaseExists: true, keyStored: true, storedKeyUsable: true), .useStored)
        XCTAssertEqual(KeyPolicy.decide(databaseExists: false, keyStored: true, storedKeyUsable: true), .useStored)
        XCTAssertEqual(KeyPolicy.decide(databaseExists: true, keyStored: false, storedKeyUsable: false), .cannotOpen)
        XCTAssertEqual(KeyPolicy.decide(databaseExists: true, keyStored: true, storedKeyUsable: false), .cannotOpen)
        XCTAssertEqual(KeyPolicy.decide(databaseExists: false, keyStored: false, storedKeyUsable: false), .createNew)
        XCTAssertEqual(KeyPolicy.decide(databaseExists: false, keyStored: true, storedKeyUsable: false), .createNew)
    }

    func testANewKeyIs32BytesAndDifferentEachTime() throws {
        let a = try DatabaseKey.get(store: MemoryKeyStore(), databaseExists: false)
        let b = try DatabaseKey.get(store: MemoryKeyStore(), databaseExists: false)
        XCTAssertEqual(a.count, 32)
        XCTAssertNotEqual(a, b)
    }

    func testTheStoreIsKeptOutOfBackups() throws {
        let store = try openStore()
        let file = try store.databaseURL.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(file.isExcludedFromBackup, true)
        let folder = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(folder.isExcludedFromBackup, true)
    }

    // MARK: Replacing

    func testReplacingTwiceLeavesNothingBehind() throws {
        let store = try openStore()
        try store.replace(with: try goldenData())
        let second = try smallData(currency: nil)
        try store.replace(with: second)
        assertSame(try store.export(), second)
        for table in Schema.dataTables where table != "people" {
            XCTAssertEqual(try store.rowCount(table: table), 0, table)
        }
        XCTAssertEqual(try store.rowCount(table: "people"), 1)
        XCTAssertEqual(try store.rowCount(table: "icons"), 0)
        XCTAssertEqual(try store.rowCount(table: "settings"), 2, "createdAt and monthStartDay; no currency, no arrangement")

        // And the same data again changes nothing.
        try store.replace(with: second)
        assertSame(try store.export(), second)
        XCTAssertEqual(try store.rowCount(table: "people"), 1)
    }

    func testAFailingReplaceRollsBackAndKeepsTheOldData() throws {
        let original = try goldenData()
        let store = try openStore()
        try store.replace(with: original)

        // Two groups with the same id: the second insert breaks the primary key, after everything was deleted.
        let builder = BackupBuilder(createdAt: 99, monthStartDay: 7, currency: "EUR", accountArrangement: "G:x")
        try addRow(builder, "account_groups", ["1", "G", "0"])
        try addRow(builder, "account_groups", ["1", "H", "1"])
        let bad = builder.build()

        XCTAssertThrowsError(try store.replace(with: bad)) { error in
            XCTAssertTrue(error is SQLiteError, "\(error)")
        }
        assertSame(try store.export(), original)
        XCTAssertEqual(try store.rowCount(table: "accounts"), 3)
        XCTAssertEqual(try store.rowCount(table: "icons"), 1)

        // The store still works afterwards.
        let fine = try smallData(currency: "EUR", extraGroup: true)
        try store.replace(with: fine)
        assertSame(try store.export(), fine)
    }

    func testAValueThatDoesNotFitItsColumnIsRefusedBeforeAnythingIsStored() throws {
        let builder = BackupBuilder(createdAt: 1, monthStartDay: 1, currency: nil, accountArrangement: nil)
        try addRow(builder, "people", ["1", "Sam", "0"])
        XCTAssertThrowsError(try addRow(builder, "people", ["not a number", "Sam", "0"]))
    }
}
