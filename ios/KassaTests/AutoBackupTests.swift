import XCTest
import KassaShared
@testable import Kassa

/// Automatic backups to a folder: the file work, the retention, the failed-day streak and the status, on a temporary folder instead of a
/// bookmark. What is due, which names are Kassa's and the streak are :domain's and JVM-tested; here the Swift side must do as they say.
final class AutoBackupTests: XCTestCase {

    private var folder: URL!
    private var defaults: UserDefaults!
    private var suite = ""
    private var settings: AutoBackupSettings!

    /// A moment in late October 2026, after every name the tests make for January.
    private let now = Date(timeIntervalSince1970: 1_793_000_000)
    private let dayLength: TimeInterval = 86_400

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-auto-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        suite = "kassa-auto-tests-" + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)
        settings = AutoBackupSettings(defaults: defaults, secret: MemorySecret())
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: folder)
    }

    // MARK: Helpers

    private struct Broken: Error {}

    private func runner(_ bytes: Data = Data("backup".utf8), fail: Bool = false) -> AutoBackupRunner {
        return AutoBackupRunner(settings: settings, makeBytes: { _ in
            if fail { throw Broken() }
            return bytes
        })
    }

    private func open() -> () throws -> AutoBackupAccess {
        let url: URL = folder
        return { AutoBackupAccess(url: url, stop: {}) }
    }

    private func names() throws -> [String] {
        return try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
    }

    private func put(_ name: String) throws {
        try Data("x".utf8).write(to: folder.appendingPathComponent(name))
    }

    // MARK: Names

    func testTheFileNameIsOneOfKassasOwn() {
        let name = BackupNames.fileName(for: now)
        XCTAssertTrue(AutoBackupBridge.shared.isOurs(name: name))
        XCTAssertFalse(AutoBackupBridge.shared.isOurs(name: "Kassa_20261105_093007 (1).kassa"))
    }

    // MARK: A backup that works

    func testASuccessfulRunWritesTheFileExactlyAndRecordsIt() throws {
        let payload = Data("KASSA0 bytes".utf8)
        let report = runner(payload).run(mode: .scheduled, now: now, open: open())
        XCTAssertTrue(report.saved)
        XCTAssertNil(report.message)
        let found = try names()
        XCTAssertEqual(found, [BackupNames.fileName(for: now)])
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent(found[0])), payload)
        XCTAssertEqual(settings.lastSuccessMillis, AutoBackupSettings.millis(now))
        XCTAssertNil(settings.lastError)
        XCTAssertFalse(settings.due(now: now.addingTimeInterval(3_600)))
        XCTAssertTrue(settings.due(now: now.addingTimeInterval(dayLength + 1)))
    }

    func testThePasswordIsHandedToTheWriter() {
        settings.setPassword("secret")
        var seen: String?
        let made = AutoBackupRunner(settings: settings, makeBytes: { password in
            seen = password
            return Data("x".utf8)
        })
        _ = made.run(mode: .scheduled, now: now, open: open())
        XCTAssertEqual(seen, "secret")
        settings.setPassword("")
        XCTAssertFalse(settings.hasPassword)
    }

    // MARK: Keeping 30

    func testOnlyKassasOwnFilesBeyondTheNewest30AreDeleted() throws {
        var ours: [String] = []
        for index in 1...35 {
            let name = AutoBackupBridge.shared.nameFor(year: 2026, month: 1, day: Int32(index), hour: 8, minute: 0, second: 0)
            if AutoBackupBridge.shared.isOurs(name: name) {
                ours.append(name)
                try put(name)
            }
        }
        // January has 31 days, so 31 names; add some more in February.
        for index in 1...4 {
            let name = AutoBackupBridge.shared.nameFor(year: 2026, month: 2, day: Int32(index), hour: 8, minute: 0, second: 0)
            ours.append(name)
            try put(name)
        }
        let others = ["holiday.jpg", "Kassa_20260105_080000 (1).kassa", "Kassa_copy.kassa", "notes.txt"]
        for other in others { try put(other) }

        let report = runner().run(mode: .scheduled, now: now, open: open())
        XCTAssertTrue(report.saved)

        let left = try names()
        let leftOurs = left.filter { AutoBackupBridge.shared.isOurs(name: $0) }
        XCTAssertEqual(leftOurs.count, 30)
        XCTAssertTrue(leftOurs.contains(BackupNames.fileName(for: now)))
        // The oldest were the ones deleted.
        XCTAssertFalse(left.contains(ours.sorted()[0]))
        for other in others { XCTAssertTrue(left.contains(other), other) }
    }

    // MARK: Failures

    func testAFailedWriteLeavesNoFileAndAPlainStatus() throws {
        let report = runner(fail: true).run(mode: .scheduled, now: now, open: open())
        XCTAssertFalse(report.saved)
        XCTAssertEqual(report.message, String(localized: "failure.backupNotSaved"))
        XCTAssertEqual(try names(), [])
        XCTAssertEqual(settings.lastError, report.message)
        XCTAssertEqual(settings.lastSuccessMillis, 0)
        XCTAssertEqual(settings.failedDays, 1)
    }

    func testAWriteIntoAMissingFolderLeavesNothingBehind() throws {
        let gone = folder.appendingPathComponent("nope", isDirectory: true)
        XCTAssertThrowsError(try AutoBackupFiles.save(Data("x".utf8), named: "Kassa_20260105_080000.kassa", in: gone))
        XCTAssertFalse(FileManager.default.fileExists(atPath: gone.appendingPathComponent("Kassa_20260105_080000.kassa").path))
        XCTAssertEqual(try names(), [])
    }

    func testAFolderThatIsGoneIsAPlainMessage() {
        let report = runner().run(mode: .scheduled, now: now, open: { throw AutoBackupError.folderGone })
        XCTAssertFalse(report.saved)
        XCTAssertEqual(report.message, String(localized: "failure.locationGone"))
    }

    func testNoFolderChosenSaysSo() {
        let report = runner().run(mode: .manual, now: now, open: { throw AutoBackupError.noFolder })
        XCTAssertEqual(report.message, String(localized: "autoBackup.pickFirst"))
    }

    func testTheThirdFailedDayInARowNotifiesOnceAndASuccessEndsTheStreak() throws {
        let broken = runner(fail: true)
        let first = broken.run(mode: .scheduled, now: now, open: open())
        let second = broken.run(mode: .scheduled, now: now.addingTimeInterval(dayLength), open: open())
        let third = broken.run(mode: .scheduled, now: now.addingTimeInterval(2 * dayLength), open: open())
        XCTAssertFalse(first.notifyNow)
        XCTAssertFalse(second.notifyNow)
        XCTAssertTrue(third.notifyNow)
        XCTAssertEqual(settings.failedDays, 3)

        // The notification was really shown: no second one for the same streak.
        settings.markAlerted()
        let fourth = broken.run(mode: .scheduled, now: now.addingTimeInterval(3 * dayLength), open: open())
        XCTAssertFalse(fourth.notifyNow)

        let good = runner().run(mode: .scheduled, now: now.addingTimeInterval(4 * dayLength), open: open())
        XCTAssertTrue(good.saved)
        XCTAssertEqual(settings.failedDays, 0)
        XCTAssertFalse(settings.alerted)
    }

    func testAnAlertThatWasNotShownIsOfferedAgain() {
        let broken = runner(fail: true)
        for step in 0..<3 { _ = broken.run(mode: .scheduled, now: now.addingTimeInterval(Double(step) * dayLength), open: open()) }
        // markAlerted was never called (notifications off), so the next failed day asks again.
        let next = broken.run(mode: .scheduled, now: now.addingTimeInterval(3 * dayLength), open: open())
        XCTAssertTrue(next.notifyNow)
    }

    func testAManualFailureDoesNotCountAndABackgroundFailureIsSilent() {
        let broken = runner(fail: true)
        let manual = broken.run(mode: .manual, now: now, open: open())
        XCTAssertNotNil(manual.message)
        XCTAssertEqual(settings.failedDays, 0)

        let defaultsBefore = settings.lastAttemptMillis
        let quiet = broken.run(mode: .background, now: now.addingTimeInterval(dayLength), open: open())
        XCTAssertNil(quiet.message)
        XCTAssertFalse(quiet.notifyNow)
        XCTAssertEqual(settings.failedDays, 0)
        XCTAssertEqual(settings.lastAttemptMillis, defaultsBefore)
    }

    func testAFailedTryWaitsAnHourBeforeAnother() {
        _ = runner(fail: true).run(mode: .scheduled, now: now, open: open())
        XCTAssertFalse(settings.due(now: now.addingTimeInterval(600)))
        XCTAssertTrue(settings.due(now: now.addingTimeInterval(3_700)))
    }

    // MARK: Settings

    func testForgettingTheFolderKeepsThePasswordAndNote() {
        settings.bookmark = Data([1, 2, 3])
        settings.folderName = "Backups"
        settings.setPassword("secret")
        settings.noteDismissed = true
        settings.forgetFolder()
        XCTAssertNil(settings.bookmark)
        XCTAssertNil(settings.folderName)
        XCTAssertTrue(settings.hasPassword)
        XCTAssertTrue(settings.noteDismissed)
    }

    func testTheAlertSaysNothingAboutMoney() {
        let notice = AutoBackupAlert.notice()
        XCTAssertEqual(notice.title, String(localized: "autoBackup.alertTitle"))
        XCTAssertEqual(notice.kind, "BACKUP")
        XCTAssertFalse(notice.body.contains("$"))
    }

    func testTheEpochDayFollowsThePhonesZone() {
        let utc = TimeZone(identifier: "UTC")!
        let ahead = TimeZone(secondsFromGMT: 14 * 3_600)!
        let late = Date(timeIntervalSince1970: 20_000 * 86_400 + 86_400 - 60)
        XCTAssertEqual(AutoBackupSettings.epochDay(late, zone: utc), 20_000)
        XCTAssertEqual(AutoBackupSettings.epochDay(late, zone: ahead), 20_001)
    }
}
