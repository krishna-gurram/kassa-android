import Foundation
import SwiftUI
import UIKit
import LocalAuthentication
import KassaShared

/// How an unlock attempt ended.
enum LockAttempt: Equatable {
    case success
    /// The phone has no passcode any more: the lock can't work.
    case noScreenLock
    /// Cancelled, failed, locked out, or a passing problem: Kassa stays locked.
    case failed
}

/// The system side of App lock: whether the phone can ask, and asking. Tests use a stand-in.
protocol LockAuthenticator: AnyObject {
    /// "READY", "NO_SCREEN_LOCK" or "UNAVAILABLE" (`LockBridge.availability`).
    func availability() -> String
    func authenticate(reason: String, done: @escaping (LockAttempt) -> Void)
}

/// LocalAuthentication with the device-owner policy: fingerprint or face, or else the phone's passcode. No passcode set means no lock.
final class SystemLockAuthenticator: LockAuthenticator {

    func availability() -> String {
        let context = LAContext()
        var problem: NSError?
        let can: Bool = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &problem)
        let code: Int = problem?.code ?? 0
        let noPasscode: Bool = code == LAError.passcodeNotSet.rawValue
        return LockBridge.shared.availability(canAuthenticate: can, noScreenLock: noPasscode)
    }

    func authenticate(reason: String, done: @escaping (LockAttempt) -> Void) {
        let context = LAContext()
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, error in
            let attempt: LockAttempt = SystemLockAuthenticator.attempt(success: ok, errorCode: (error as NSError?)?.code)
            DispatchQueue.main.async { done(attempt) }
        }
    }

    /// Maps the system's answer: only a missing passcode turns the lock off; every other error leaves Kassa locked.
    static func attempt(success: Bool, errorCode: Int?) -> LockAttempt {
        if success { return .success }
        if errorCode == LAError.passcodeNotSet.rawValue { return .noScreenLock }
        return .failed
    }
}

/// App lock and the privacy cover. The decisions (minute rule, availability outcomes, when the cover shows) are `LockBridge`
/// (:domain `AppLockRules`, JVM-tested); this only keeps the state, remembers the switch in UserDefaults (not in the .kassa backup,
/// as on Android) and calls LocalAuthentication.
///
/// The model lives in the app, not in a scene, so closing and reopening a scene (an iPad window, a scene disconnect) keeps the locked
/// state; a cold start starts locked whenever the switch is on. Hiding the app starts the clock; coming back after more than a
/// minute locks.
final class AppLockModel: ObservableObject {

    static let enabledKey = "lockEnabled"
    static let noticeKey = "lockNoticePending"

    @Published private(set) var enabled: Bool
    @Published private(set) var locked: Bool
    /// What Settings says under the switch after a refused try ("Set a screen lock on this phone first."), or nil.
    @Published var message: String?
    /// True once after Kassa turned the lock off by itself because the phone has no passcode any more; the shell tells the person.
    @Published var turnedOffNotice: Bool

    private let defaults: UserDefaults
    private let authenticator: LockAuthenticator
    private let clock: () -> Int64
    private var hiddenAt: Int64 = 0
    private var asking = false

    init(defaults: UserDefaults = UserDefaults.standard, authenticator: LockAuthenticator = SystemLockAuthenticator(), clock: @escaping () -> Int64 = AppLockModel.nowMillis) {
        self.defaults = defaults
        self.authenticator = authenticator
        self.clock = clock
        let on: Bool = defaults.bool(forKey: AppLockModel.enabledKey)
        self.enabled = on
        self.locked = LockBridge.shared.startsLocked(lockOn: on)
        self.turnedOffNotice = defaults.bool(forKey: AppLockModel.noticeKey)
    }

    static func nowMillis() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// The lock screen is up: locked, and the switch is on.
    var lockShown: Bool {
        return LockBridge.shared.lockShown(locked: locked, lockOn: enabled)
    }

    // MARK: Settings

    /// The switch. Turning it on needs a passcode on the phone; without one the switch stays off and [message] says why.
    func setEnabled(_ on: Bool) {
        message = nil
        if !on {
            store(false)
            locked = false
            return
        }
        let result: String = LockBridge.shared.turnOnResult(availability: authenticator.availability())
        if result == "ALLOW" {
            store(true)
        } else if result == "NEEDS_SCREEN_LOCK" {
            message = String(localized: "lock.setScreenLock")
        } else {
            message = String(localized: "lock.unavailable")
        }
    }

    // MARK: Scene changes

    /// The app went to the background (not just inactive): the minute starts.
    func didEnterBackground() {
        hiddenAt = clock()
    }

    /// The app is active again: more than a minute away locks, and a locked app asks at once.
    func didBecomeActive() {
        let now: Bool = LockBridge.shared.lockedAfterAway(locked: locked, lockOn: enabled, hiddenAtMillis: hiddenAt, nowMillis: clock())
        locked = now
        hiddenAt = 0
        unlockIfNeeded()
    }

    // MARK: Unlocking

    /// Asks for the passcode or biometrics when the lock screen is up. One ask at a time.
    func unlockIfNeeded() {
        if !lockShown || asking { return }
        let step: String = LockBridge.shared.unlockStep(availability: authenticator.availability())
        if step == "TURN_OFF" {
            turnOffBecauseImpossible()
            return
        }
        asking = true
        authenticator.authenticate(reason: String(localized: "lock.reason")) { attempt in
            self.asking = false
            self.finish(attempt)
        }
    }

    private func finish(_ attempt: LockAttempt) {
        switch attempt {
        case .success:
            locked = false
        case .noScreenLock:
            turnOffBecauseImpossible()
        case .failed:
            break
        }
    }

    private func turnOffBecauseImpossible() {
        store(false)
        locked = false
        defaults.set(true, forKey: AppLockModel.noticeKey)
        turnedOffNotice = true
    }

    /// The person saw the notice.
    func noticeShown() {
        turnedOffNotice = false
        defaults.set(false, forKey: AppLockModel.noticeKey)
    }

    private func store(_ on: Bool) {
        enabled = on
        defaults.set(on, forKey: AppLockModel.enabledKey)
    }
}

/// A plain screen with nothing of the person's data on it: over the app while it is not active (the app switcher's snapshot) and as
/// the lock screen.
struct NeutralCover: View {

    var body: some View {
        ZStack {
            Palette.ground.ignoresSafeArea()
            Text("lock.coverName")
                .font(.title2.weight(.semibold))
                .foregroundColor(Palette.ink)
        }
        .accessibilityHidden(true)
    }
}

struct LockScreen: View {
    let onUnlock: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text("lock.locked")
                .font(.title2.weight(.bold))
                .foregroundColor(Palette.ink)
            Button("lock.unlock", action: onUnlock)
                .buttonStyle(InkButtonStyle(filled: true))
                .frame(maxWidth: 280)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// What the cover window shows: the lock screen while the app is locked, otherwise the neutral screen.
struct CoverRoot: View {
    @ObservedObject var lock: AppLockModel
    let neutral: Bool

    var body: some View {
        if lock.lockShown && !neutral {
            LockScreen(onUnlock: { lock.unlockIfNeeded() })
        } else {
            NeutralCover()
        }
    }
}

/// A window above everything the app shows, sheets and full-screen covers included, so neither the app switcher's snapshot nor an
/// open sheet can show balances while App lock or Hide balances is on. It is shown while the app is not active, and as the lock screen.
final class CoverWindow {

    static let shared = CoverWindow()

    private var window: UIWindow?

    var isShown: Bool {
        return window != nil
    }

    func show(_ content: CoverRoot) {
        if let shown = window {
            if let host = shown.rootViewController as? UIHostingController<CoverRoot> {
                host.rootView = content
            }
            return
        }
        let scenes: [UIWindowScene] = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first else { return }
        let made = UIWindow(windowScene: scene)
        made.windowLevel = UIWindow.Level.alert + 1
        let host = UIHostingController(rootView: content)
        host.view.accessibilityViewIsModal = true
        made.rootViewController = host
        made.makeKeyAndVisible()
        window = made
    }

    func hide() {
        guard let shown = window else { return }
        shown.isHidden = true
        shown.rootViewController = nil
        window = nil
    }
}
