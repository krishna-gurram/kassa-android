import XCTest
import LocalAuthentication
import KassaShared
@testable import Kassa

/// A stand-in for LocalAuthentication: what the phone says and how the next prompt ends.
private final class FakeAuthenticator: LockAuthenticator {
    var state = "READY"
    var next = LockAttempt.success
    var asked = 0

    func availability() -> String {
        return state
    }

    func authenticate(reason: String, done: @escaping (LockAttempt) -> Void) {
        asked += 1
        done(next)
    }
}

/// App lock and the privacy cover: the decisions are :domain's (JVM-tested); these check the model keeps them, remembers the switch
/// and never opens silently. Also the can't-open erase and the big-numeral scale cap.
final class AppLockTests: XCTestCase {

    private var suite = ""
    private var defaults: UserDefaults!
    private var phone: FakeAuthenticator!
    private var now: Int64 = 1_000_000

    override func setUp() {
        suite = "kassa-lock-tests-" + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)
        phone = FakeAuthenticator()
        now = 1_000_000
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    private func makeModel() -> AppLockModel {
        return AppLockModel(defaults: defaults, authenticator: phone, clock: { self.now })
    }

    // MARK: The switch

    func testTurnsOnWhenThePhoneHasAScreenLock() {
        let model = makeModel()
        model.setEnabled(true)
        XCTAssertTrue(model.enabled)
        XCTAssertFalse(model.locked)
        XCTAssertTrue(defaults.bool(forKey: AppLockModel.enabledKey))
        XCTAssertNil(model.message)
    }

    func testWithoutAScreenLockTheSwitchStaysOffAndSaysWhy() {
        phone.state = "NO_SCREEN_LOCK"
        let model = makeModel()
        model.setEnabled(true)
        XCTAssertFalse(model.enabled)
        XCTAssertEqual(model.message, "Set a screen lock on this phone first.")
        XCTAssertFalse(defaults.bool(forKey: AppLockModel.enabledKey))
    }

    func testAPassingProblemKeepsTheSwitchOffWithAnotherSentence() {
        phone.state = "UNAVAILABLE"
        let model = makeModel()
        model.setEnabled(true)
        XCTAssertFalse(model.enabled)
        XCTAssertEqual(model.message, String(localized: "lock.unavailable"))
    }

    func testTurningItOffUnlocks() {
        let model = makeModel()
        model.setEnabled(true)
        model.setEnabled(false)
        XCTAssertFalse(model.enabled)
        XCTAssertFalse(model.lockShown)
    }

    // MARK: Locking

    func testColdStartWithTheSwitchOnIsLockedAndOffIsNot() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        XCTAssertTrue(makeModel().lockShown)
        defaults.set(false, forKey: AppLockModel.enabledKey)
        XCTAssertFalse(makeModel().lockShown)
    }

    func testUnlockingNeedsTheSystemToSayYes() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        let model = makeModel()
        phone.next = .failed
        model.unlockIfNeeded()
        XCTAssertEqual(phone.asked, 1)
        XCTAssertTrue(model.lockShown, "a failed or cancelled prompt never opens the app")
        phone.next = .success
        model.unlockIfNeeded()
        XCTAssertFalse(model.lockShown)
    }

    func testBackAfterMoreThanAMinuteLocksAgainAndAMinuteDoesNot() {
        let model = makeModel()
        model.setEnabled(true)
        phone.next = .failed
        model.didEnterBackground()
        now += 60_000
        model.didBecomeActive()
        XCTAssertFalse(model.locked)
        model.didEnterBackground()
        now += 60_001
        model.didBecomeActive()
        XCTAssertTrue(model.locked)
        XCTAssertEqual(phone.asked, 1, "coming back locked asks at once")
    }

    func testAShortInactiveSpellAfterUnlockingDoesNotRelock() {
        let model = makeModel()
        model.setEnabled(true)
        model.didEnterBackground()
        now += 120_000
        phone.next = .success
        model.didBecomeActive()
        XCTAssertFalse(model.locked)
        now += 500_000
        model.didBecomeActive()
        XCTAssertFalse(model.locked, "the clock starts only when the app is hidden")
    }

    func testLockStaysLockedAcrossActiveChangesUntilUnlocked() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        let model = makeModel()
        phone.next = .failed
        model.didBecomeActive()
        model.didBecomeActive()
        XCTAssertTrue(model.locked)
    }

    // MARK: Impossible lock

    func testNoPasscodeAnyMoreTurnsTheLockOffOnceWithANotice() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        let model = makeModel()
        phone.state = "NO_SCREEN_LOCK"
        model.unlockIfNeeded()
        XCTAssertFalse(model.enabled)
        XCTAssertFalse(model.lockShown)
        XCTAssertTrue(model.turnedOffNotice)
        XCTAssertEqual(phone.asked, 0)
        XCTAssertFalse(defaults.bool(forKey: AppLockModel.enabledKey))
        model.noticeShown()
        XCTAssertFalse(model.turnedOffNotice)
        XCTAssertFalse(makeModel().turnedOffNotice)
    }

    func testThePromptReportingNoPasscodeTurnsItOffToo() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        let model = makeModel()
        phone.next = .noScreenLock
        model.unlockIfNeeded()
        XCTAssertFalse(model.enabled)
        XCTAssertTrue(model.turnedOffNotice)
    }

    func testTheNoticeSurvivesARestartUntilSeen() {
        defaults.set(true, forKey: AppLockModel.enabledKey)
        let model = makeModel()
        phone.state = "NO_SCREEN_LOCK"
        model.unlockIfNeeded()
        XCTAssertTrue(makeModel().turnedOffNotice)
    }

    func testOnlyAMissingPasscodeCountsAsNoScreenLock() {
        XCTAssertEqual(SystemLockAuthenticator.attempt(success: true, errorCode: nil), .success)
        XCTAssertEqual(SystemLockAuthenticator.attempt(success: false, errorCode: LAError.passcodeNotSet.rawValue), .noScreenLock)
        XCTAssertEqual(SystemLockAuthenticator.attempt(success: false, errorCode: LAError.userCancel.rawValue), .failed)
        XCTAssertEqual(SystemLockAuthenticator.attempt(success: false, errorCode: LAError.authenticationFailed.rawValue), .failed)
        XCTAssertEqual(SystemLockAuthenticator.attempt(success: false, errorCode: nil), .failed)
    }

    // MARK: Privacy cover

    func testCoverWhileEitherSwitchIsOnAndTheAppIsNotActive() {
        XCTAssertTrue(LockBridge.shared.privacyCover(lockOn: true, hideAmounts: false, appActive: false))
        XCTAssertTrue(LockBridge.shared.privacyCover(lockOn: false, hideAmounts: true, appActive: false))
        XCTAssertFalse(LockBridge.shared.privacyCover(lockOn: true, hideAmounts: true, appActive: true))
        XCTAssertFalse(LockBridge.shared.privacyCover(lockOn: false, hideAmounts: false, appActive: false))
    }

    // MARK: Can't-open screen

    func testEraseRemovesTheFolderAndTheKey() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-erase-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let keys = MemoryKeyStore()
        let first = try LedgerStore(directory: folder, keyStore: keys)
        try first.startFresh(currency: "USD", categories: ["Groceries"])
        XCTAssertNotNil(keys.key)
        try LedgerStore.erase(directory: folder, keyStore: keys)
        XCTAssertNil(keys.key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.path))
        try LedgerStore.erase(directory: folder, keyStore: keys)
        let again = try LedgerStore(directory: folder, keyStore: keys)
        XCTAssertEqual(try again.rowCount(table: "categories"), 0)
    }

    func testEraseAndRestartLeadsToTheFirstRunScreen() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-erase-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let keys = MemoryKeyStore()
        do {
            let first = try LedgerStore(directory: folder, keyStore: keys)
            try first.startFresh(currency: "USD", categories: ["Groceries"])
        }
        keys.key = nil // the key was lost: the data can't be opened
        let model = AppModel(
            defaults: defaults, openStore: { try LedgerStore(directory: folder, keyStore: keys) },
            eraseStore: { try LedgerStore.erase(directory: folder, keyStore: keys) }
        )
        model.start()
        XCTAssertTrue(waitFor { if case .failed = model.phase { return true } else { return false } })
        defaults.set(true, forKey: AppModel.startedFreshKey)
        model.eraseAndRestart()
        XCTAssertTrue(waitFor { model.isFirstRun })
        XCTAssertFalse(defaults.bool(forKey: AppModel.startedFreshKey))
    }

    func testAFailedEraseBringsTheCantOpenScreenBack() {
        let model = AppModel(
            defaults: defaults, openStore: { throw KeyUnreadable() },
            eraseStore: { throw KeychainError(status: -1) }
        )
        model.start()
        XCTAssertTrue(waitFor { if case .failed = model.phase { return true } else { return false } })
        model.eraseAndRestart()
        XCTAssertTrue(waitFor {
            if case .failed(let text) = model.phase { return text == String(localized: "start.eraseFailed") }
            return false
        })
    }

    // MARK: Big numerals

    func testNumeralsGrowWithTheSettingButStopAtOneThirty() {
        XCTAssertEqual(ScaledNumeral.factor(0.8), 1)
        XCTAssertEqual(ScaledNumeral.factor(1), 1)
        XCTAssertEqual(ScaledNumeral.factor(1.2), 1.2, accuracy: 0.0001)
        XCTAssertEqual(ScaledNumeral.factor(2.5), 1.3, accuracy: 0.0001)
    }

    private func waitFor(_ condition: @escaping () -> Bool, timeout: TimeInterval = 30) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }
}
