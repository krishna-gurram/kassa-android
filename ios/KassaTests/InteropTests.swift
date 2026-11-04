import XCTest
import KassaShared
@testable import Kassa

/// A .kassa file this app writes must open on Android. The Gradle tests cannot read a file CI produces, so this checks what it can
/// here (the file opens again through the shared reader and means the same thing) and leaves the file for a person or a later job:
/// when KASSA_SHOTS_DIR is set the file is written there as demo-from-ios.kassa, and the workflow uploads that folder.
final class InteropTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-interop-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTheDemoBackupWrittenByIOSReadsBackTheSame() throws {
        let original = try DemoFixture.data()
        let store = try LedgerStore(directory: directory, keyStore: keys)
        try store.replace(with: original)
        let exported = try store.export()
        let json = BackupBridge.shared.write(data: exported)
        let bytes = try BackupFile.make(json: json, password: nil)

        // The file opens again and means what the original meant.
        let opened = try BackupFile.open(bytes, password: nil)
        let parsed = try BackupBridge.shared.parse(text: opened)
        XCTAssertEqual(BackupBridge.shared.digest(data: parsed), BackupBridge.shared.digest(data: original))
        XCTAssertEqual(parsed.transactions.count, original.transactions.count)
        XCTAssertEqual(parsed.accounts.count, original.accounts.count)

        // Writing what was read gives the same JSON structure (key order aside).
        let again = BackupBridge.shared.write(data: parsed)
        let first = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? NSDictionary
        let second = try JSONSerialization.jsonObject(with: Data(again.utf8)) as? NSDictionary
        XCTAssertNotNil(first)
        XCTAssertEqual(first, second)

        // The same structure as the demo file's own JSON.
        let demoJson = try BackupFile.open(try Data(contentsOf: try DemoFixture.url()), password: nil)
        let demoParsed = try BackupBridge.shared.parse(text: demoJson)
        XCTAssertEqual(BackupBridge.shared.digest(data: demoParsed), BackupBridge.shared.digest(data: parsed))

        guard let path = ProcessInfo.processInfo.environment["KASSA_SHOTS_DIR"], !path.isEmpty else { return }
        let target = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true, attributes: nil)
        try bytes.write(to: target.appendingPathComponent("demo-from-ios.kassa"))
    }
}
