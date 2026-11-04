import XCTest
import KassaShared
@testable import Kassa

/// Opens the golden files of app/src/test/resources/backup-golden (made from docs/BACKUP_FORMAT.md by
/// tools/make_golden_backups.py, the same files Android's BackupGoldenTest opens) and checks what this app writes.
final class BackupFileTests: XCTestCase {

    private let password = "golden test password"

    private func golden(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: BackupFileTests.self).url(forResource: name, withExtension: "kassa"), "missing \(name).kassa")
        return try Data(contentsOf: url)
    }

    /// The same values as Android's BackupGoldenTest, read through the shared Kotlin parser.
    private func checkGolden(_ json: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let data = try BackupBridge.shared.parse(text: json)
        XCTAssertEqual(data.createdAt, 1767225600000, file: file, line: line)
        XCTAssertEqual(data.currency, "USD", file: file, line: line)
        XCTAssertEqual(data.accountArrangement, "G:cash,card", file: file, line: line)
        let facts = BackupBridge.shared.digest(data: data).components(separatedBy: "\n")
        for expected in [
            "monthStartDay=1",
            "accounts=Checking|Card|Car loan",
            "closedDays=20500",
            "categories=Café ☕|Salary",
            "amounts=25000|450|99999999",
            "notes=|Latte ₹1,00,000|Loan principal",
            "loanRates=61250",
            "icons=photo.png:8",
        ] {
            XCTAssertTrue(facts.contains(expected), "missing \(expected) in \(facts)", file: file, line: line)
        }
        XCTAssertEqual(BackupBridge.shared.summary(data: data), "3 entries, 3 accounts, 2 categories", file: file, line: line)
    }

    private func assertError(_ expected: BackupError, file: StaticString = #filePath, line: UInt = #line, _ body: () throws -> Any) {
        do {
            _ = try body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch let error as BackupError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("unexpected error \(error)", file: file, line: line)
        }
    }

    // MARK: Golden files

    func testOpensThePlainGoldenFile() throws {
        try checkGolden(BackupFile.open(try golden("plain"), password: nil))
    }

    func testOpensTheProtectedGoldenFile() throws {
        try checkGolden(BackupFile.open(try golden("protected"), password: password))
    }

    func testProtectedGoldenFileChecksThePassword() throws {
        let bytes = try golden("protected")
        assertError(.needsPassword) { try BackupFile.open(bytes, password: nil) }
        assertError(.needsPassword) { try BackupFile.open(bytes, password: "") }
        assertError(.wrongPassword) { try BackupFile.open(bytes, password: "nope") }
    }

    func testANewerGoldenFileOpensButTheKotlinParserRefusesIt() throws {
        let json = try BackupFile.open(try golden("newer"), password: nil)
        XCTAssertThrowsError(try BackupBridge.shared.parse(text: json))
        XCTAssertEqual(BackupBridge.shared.refusal(text: json), "This backup is from a newer version of Kassa. Update Kassa to open it.")
    }

    // MARK: Envelope

    private let sample = #"{"version":1,"createdAt":5,"monthStartDay":1,"currency":"USD","people":[{"id":1,"name":"Café ☕ ₹ 😀","archived":false}]}"#

    func testMakeThenOpenRoundTripsWithoutAPassword() throws {
        let bytes = try BackupFile.make(json: sample, password: nil)
        XCTAssertEqual(Array(bytes.prefix(6)), Array("KASSA0".utf8))
        XCTAssertEqual(try BackupFile.open(bytes, password: nil), sample)
        XCTAssertEqual(try BackupFile.open(bytes, password: "ignored"), sample)
        XCTAssertEqual(Array(try BackupFile.make(json: sample, password: "").prefix(6)), Array("KASSA0".utf8))
    }

    func testMakeThenOpenRoundTripsWithAPassword() throws {
        let bytes = try BackupFile.make(json: sample, password: "pässwörd ₹")
        XCTAssertEqual(Array(bytes.prefix(6)), Array("KASSA1".utf8))
        XCTAssertEqual(try BackupFile.open(bytes, password: "pässwörd ₹"), sample)
        assertError(.needsPassword) { try BackupFile.open(bytes, password: nil) }
        assertError(.wrongPassword) { try BackupFile.open(bytes, password: "other") }
    }

    func testAProtectedFileIsDifferentEachTime() throws {
        let a = try BackupFile.make(json: sample, password: "pw")
        let b = try BackupFile.make(json: sample, password: "pw")
        XCTAssertNotEqual(a, b)
        XCTAssertNotEqual(a.subdata(in: 6..<22), b.subdata(in: 6..<22), "salt")
        XCTAssertNotEqual(a.subdata(in: 22..<34), b.subdata(in: 22..<34), "IV")
    }

    func testAFlippedByteFails() throws {
        var protected = try BackupFile.make(json: sample, password: "pw")
        protected[protected.count - 1] ^= 0x01 // the tag
        assertError(.wrongPassword) { try BackupFile.open(protected, password: "pw") }
        var middle = try BackupFile.make(json: sample, password: "pw")
        middle[40] ^= 0x01 // the ciphertext
        assertError(.wrongPassword) { try BackupFile.open(middle, password: "pw") }

        let big = String(repeating: #"{"a":"some text to pack"},"#, count: 400)
        var plain = try BackupFile.make(json: "[" + big + "0]", password: nil)
        plain[plain.count / 2] ^= 0x01
        assertError(.notABackup) { try BackupFile.open(plain, password: nil) }
    }

    func testShortAndForeignFilesAreNotBackups() throws {
        assertError(.notABackup) { try BackupFile.open(Data(), password: nil) }
        assertError(.notABackup) { try BackupFile.open(Data("KASS".utf8), password: nil) }
        assertError(.notABackup) { try BackupFile.open(Data("PK\u{3}\u{4}0000000000000000000000".utf8), password: nil) }
        assertError(.notABackup) { try BackupFile.open(Data("KASSA0".utf8), password: nil) }
        assertError(.notABackup) { try BackupFile.open(Data("KASSA1abc".utf8), password: "pw") }
        assertError(.wrongPassword) { try BackupFile.open(Data("KASSA1".utf8) + Data(repeating: 1, count: 16 + 12 + 5), password: "pw") }
    }

    func testTheGzipPartAcceptsOptionalHeaderFields() throws {
        let plain = try BackupFile.make(json: sample, password: nil)
        let body = [UInt8](plain.dropFirst(6))
        // FEXTRA (2 bytes), FNAME, FCOMMENT, FHCRC, in the order RFC 1952 gives.
        var header = Array(body[0..<10])
        header[3] = 0x04 | 0x08 | 0x10 | 0x02
        var fields: [UInt8] = [2, 0, 0xAA, 0xBB]
        fields.append(contentsOf: Array("a.json".utf8))
        fields.append(0)
        fields.append(contentsOf: Array("note".utf8))
        fields.append(0)
        fields.append(contentsOf: [0x12, 0x34])
        let rest = Array(body[10...])
        var packed: [UInt8] = header
        packed.append(contentsOf: fields)
        packed.append(contentsOf: rest)
        let wrapped = Data("KASSA0".utf8) + Data(packed)
        XCTAssertEqual(try BackupFile.open(wrapped, password: nil), sample)
    }

    func testAnUnpackedSizeOverTheLimitIsTooLarge() throws {
        var bytes = [UInt8](try BackupFile.make(json: sample, password: nil))
        let size: UInt32 = 300 * 1024 * 1024
        bytes[bytes.count - 4] = UInt8(size & 0xff)
        bytes[bytes.count - 3] = UInt8((size >> 8) & 0xff)
        bytes[bytes.count - 2] = UInt8((size >> 16) & 0xff)
        bytes[bytes.count - 1] = UInt8((size >> 24) & 0xff)
        assertError(.tooLarge) { try BackupFile.open(Data(bytes), password: nil) }
    }

    func testMessagesAreAndroidsWords() {
        XCTAssertEqual(BackupError.notABackup.message, "This file isn't a Kassa backup.")
        XCTAssertEqual(BackupError.needsPassword.message, "This backup has a password. Enter it, then pick the file again.")
        XCTAssertEqual(BackupError.wrongPassword.message, "That password doesn't open this backup.")
        XCTAssertEqual(BackupError.tooLarge.message, "This backup is too large to open on this phone.")
    }

    // MARK: With the Kotlin JSON

    func testParseWriteWrapAndReadBack() throws {
        let data = try BackupBridge.shared.parse(text: sample)
        XCTAssertEqual(BackupBridge.shared.summary(data: data), "0 entries, 0 accounts, 0 categories")
        let written = BackupBridge.shared.write(data: data)
        let bytes = try BackupFile.make(json: written, password: "pw")
        let opened = try BackupFile.open(bytes, password: "pw")
        XCTAssertEqual(opened, written)
        let again = try BackupBridge.shared.parse(text: opened)
        XCTAssertEqual(BackupBridge.shared.digest(data: again), BackupBridge.shared.digest(data: data))
        XCTAssertEqual(BackupBridge.shared.write(data: again), written)
    }

    func testAGoldenBackupSurvivesBeingWrittenAgain() throws {
        let json = try BackupFile.open(try golden("plain"), password: nil)
        let data = try BackupBridge.shared.parse(text: json)
        let rewritten = try BackupFile.make(json: BackupBridge.shared.write(data: data), password: password)
        try checkGolden(BackupFile.open(rewritten, password: password))
    }
}
