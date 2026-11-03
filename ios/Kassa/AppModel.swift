import Foundation
import SwiftUI
import UIKit
import KassaShared

/// Where the app is in its start.
enum AppPhase {
    case opening
    /// The database is empty and nothing was chosen yet: restore a backup or start fresh.
    case firstRun
    case ready
    /// The database could not be opened; the text is already worded for the person.
    case failed(String)
}

/// The app's state: it opens the encrypted `LedgerStore`, builds the shared `LedgerView` from it on a background queue, and
/// publishes the figures the screens lay out. Hide balances (the eye) lives here and is remembered in UserDefaults.
///
/// All store and `LedgerView` work runs on ONE serial queue (the shared rules keep a few globals while they format, so two
/// calls at once must not overlap); results come back to the main thread. Properties marked "queue only" are touched
/// nowhere else. Failures reach the screen only as plain sentences (`BackupError`, `BackupBridge.refusal`, Localizable.strings).
///
/// There is no currency picker yet: the currency is the backup's, else the phone's region currency when Kassa lists it, else
/// US dollars (`LedgerView`).
final class AppModel: ObservableObject {

    static let hideKey = "hideAmounts"
    static let startedFreshKey = "startedFresh"

    @Published private(set) var phase: AppPhase = .opening
    @Published private(set) var accounts: AccountsData?
    @Published private(set) var activity: ActivityData?
    @Published private(set) var shownYear: Int
    @Published private(set) var shownMonth: Int
    @Published private(set) var today: Int
    @Published private(set) var hideAmounts: Bool
    /// The sentence to show after a restore failed (or a wrong password), or nil.
    @Published private(set) var restoreMessage: String?
    @Published private(set) var working = false
    /// True while the password prompt is up for a protected backup.
    @Published var asksPassword = false

    private let queue = DispatchQueue(label: "app.kassa.ledger", qos: .userInitiated)
    private let defaults: UserDefaults
    private let openStore: () throws -> LedgerStore
    private var started = false
    private var store: LedgerStore?      // queue only
    private var view: LedgerView?        // queue only
    private var pendingFile: Data?       // queue only

    init(defaults: UserDefaults = UserDefaults.standard, openStore: @escaping () throws -> LedgerStore = { try LedgerStore.openDefault() }) {
        self.defaults = defaults
        self.openStore = openStore
        self.hideAmounts = defaults.bool(forKey: AppModel.hideKey)
        let now = KassaDates.todayEpochDay()
        let current = KassaDates.yearMonth(epochDay: now)
        self.today = now
        self.shownYear = current.year
        self.shownMonth = current.month
    }

    var isFirstRun: Bool {
        if case .firstRun = phase { return true }
        return false
    }

    var isReady: Bool {
        if case .ready = phase { return true }
        return false
    }

    // MARK: Inputs

    /// What the figures depend on, read on the main thread and handed to the queue.
    private struct Inputs {
        let hidden: Bool
        let year: Int
        let month: Int
        let today: Int
        let zone: String
    }

    private func inputs() -> Inputs {
        return Inputs(
            hidden: hideAmounts, year: shownYear, month: shownMonth,
            today: KassaDates.todayEpochDay(), zone: TimeZone.current.identifier
        )
    }

    private func publish(_ block: @escaping () -> Void) {
        DispatchQueue.main.async(execute: block)
    }

    // MARK: Start

    /// Opens the database and decides between the first-run screen and the app. Safe to call again; it runs once.
    func start() {
        if started { return }
        started = true
        open(inputs())
    }

    /// Tries again after a failed start.
    func retry() {
        started = true
        phase = .opening
        open(inputs())
    }

    private func open(_ given: Inputs) {
        queue.async {
            do {
                let opened = try self.openStore()
                self.store = opened
                let empty = try self.isEmpty(opened)
                let fresh = self.defaults.bool(forKey: AppModel.startedFreshKey)
                if empty && !fresh {
                    self.publish { self.phase = .firstRun }
                    return
                }
                try self.load(opened, given)
                self.publish { self.phase = .ready }
            } catch {
                let text = AppModel.openFailure(error)
                self.publish { self.phase = .failed(text) }
            }
        }
    }

    private func isEmpty(_ opened: LedgerStore) throws -> Bool {
        let accounts = try opened.rowCount(table: "accounts")
        let entries = try opened.rowCount(table: "transactions")
        let people = try opened.rowCount(table: "people")
        return accounts == 0 && entries == 0 && people == 0
    }

    private static func openFailure(_ error: Error) -> String {
        if error is KeyUnreadable { return String(localized: "start.keyMissing") }
        return String(localized: "start.cantOpen")
    }

    /// "Start fresh": the app opens empty. There is no currency picker yet.
    func startFresh() {
        defaults.set(true, forKey: AppModel.startedFreshKey)
        let given = inputs()
        queue.async {
            guard let opened = self.store else { return }
            do {
                try self.load(opened, given)
                self.publish { self.phase = .ready }
            } catch {
                let text = AppModel.openFailure(error)
                self.publish { self.phase = .failed(text) }
            }
        }
    }

    // MARK: Figures

    /// Reads the whole store into a new `LedgerView` and publishes its figures. Queue only.
    private func load(_ opened: LedgerStore, _ given: Inputs) throws {
        let data = try opened.export()
        let made = LedgerView(data: data, fallbackCurrency: AppModel.phoneCurrency())
        view = made
        compute(made, given)
    }

    private static func phoneCurrency() -> String? {
        return Locale.current.currency?.identifier
    }

    /// Works the screens' figures out of [made] and publishes them. Queue only.
    private func compute(_ made: LedgerView, _ given: Inputs) {
        let day = Int32(given.today)
        let a = made.accounts(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone)
        let m = made.activity(
            year: Int32(given.year), month: Int32(given.month), hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone
        )
        publish {
            self.accounts = a
            self.activity = m
            self.today = given.today
        }
    }

    /// Works the figures out again for the current switch, month and day (no new read of the store).
    func refreshFigures() {
        let given = inputs()
        queue.async {
            guard let made = self.view else { return }
            self.compute(made, given)
        }
    }

    func toggleHidden() {
        hideAmounts.toggle()
        defaults.set(hideAmounts, forKey: AppModel.hideKey)
        refreshFigures()
    }

    /// Moves Activity by [months]; never past the current month.
    func shiftMonth(_ months: Int) {
        let next = KassaDates.shifted(year: shownYear, month: shownMonth, by: months)
        let now = KassaDates.yearMonth(epochDay: KassaDates.todayEpochDay())
        if next.year * 12 + next.month > now.year * 12 + now.month { return }
        shownYear = next.year
        shownMonth = next.month
        refreshFigures()
    }

    // MARK: Restore

    /// A file chosen in the file picker. Reads it, asks for a password when it has one, and replaces the (empty) store.
    func chooseBackup(_ url: URL) {
        working = true
        restoreMessage = nil
        let given = inputs()
        queue.async {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            do {
                let bytes = try Data(contentsOf: url)
                self.restore(bytes, password: nil, given)
            } catch {
                self.fail(String(localized: "restore.cannotRead"))
            }
        }
    }

    func submitPassword(_ password: String) {
        asksPassword = false
        working = true
        restoreMessage = nil
        let given = inputs()
        queue.async {
            guard let bytes = self.pendingFile else {
                self.fail(String(localized: "restore.cannotRead"))
                return
            }
            self.restore(bytes, password: password, given)
        }
    }

    func cancelPassword() {
        asksPassword = false
        queue.async {
            self.pendingFile = nil
        }
    }

    func dismissMessage() {
        restoreMessage = nil
    }

    /// Opens [bytes] and replaces everything with them. Queue only. Every failure ends as a plain sentence.
    private func restore(_ bytes: Data, password: String?, _ given: Inputs) {
        guard let opened = store else {
            fail(String(localized: "restore.failed"))
            return
        }
        do {
            let json = try BackupFile.open(bytes, password: password)
            if let refusal = BackupBridge.shared.refusal(text: json) {
                fail(refusal)
                return
            }
            let parsed = try BackupBridge.shared.parse(text: json)
            try opened.replace(with: parsed)
            pendingFile = nil
            try load(opened, given)
            publish {
                self.working = false
                self.asksPassword = false
                self.restoreMessage = nil
                self.phase = .ready
            }
        } catch let problem as BackupError {
            if problem == .needsPassword || problem == .wrongPassword {
                pendingFile = bytes
                let text: String? = problem == .wrongPassword ? problem.message : nil
                publish {
                    self.working = false
                    self.restoreMessage = text
                    self.asksPassword = true
                }
            } else {
                fail(problem.message)
            }
        } catch {
            fail(String(localized: "restore.failed"))
        }
    }

    private func fail(_ text: String) {
        publish {
            self.working = false
            self.asksPassword = false
            self.restoreMessage = text
        }
    }
}
