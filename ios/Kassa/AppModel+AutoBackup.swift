import Foundation
import SwiftUI
import BackgroundTasks
import KassaShared

/// What Settings > Automatic backups shows, read from the phone's settings.
struct AutoBackupInfo: Equatable {
    var folderName: String? = nil
    var lastSuccess: Date? = nil
    var message: String? = nil
    var hasPassword = false
    var noteDismissed = false
}

/// The best-effort background refresh. iOS decides if and when it runs (often not at all for an app that is rarely opened), so nothing
/// depends on it: the sure way is the check when the app becomes active.
enum AutoBackupSchedule {

    static let identifier = "app.kassa.money.autobackup"

    /// Asks the system for a refresh in about a day. The system may run it later, or never.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 20 * 60 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            return
        }
    }

    /// The refresh itself: books the next one, then backs up if one is due. Opens the store on its own (the app may not be showing).
    static func runInBackground() async {
        let settings = AutoBackupSettings()
        if settings.bookmark == nil { return }
        schedule()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .utility).async {
                runNow(settings: settings)
                continuation.resume()
            }
        }
    }

    private static func runNow(settings: AutoBackupSettings) {
        if !settings.isDue { return }
        guard let opened = try? LedgerStore.openDefault() else { return }
        let runner = AutoBackupRunner(settings: settings, makeBytes: { password in try AutoBackupSchedule.bytes(of: opened, password: password) })
        _ = runner.run(mode: .background, now: Date(), open: { try AutoBackupFolder.access(settings) })
    }

    /// The .kassa file for everything in [store]: the same steps as Back up now.
    static func bytes(of store: LedgerStore, password: String?) throws -> Data {
        let data = try store.export()
        let json = BackupBridge.shared.write(data: data)
        return try BackupFile.make(json: json, password: password)
    }
}

/// Settings > Automatic backups: the folder, the password, the daily check and the status. Same queue and rules as the rest of `AppModel`.
extension AppModel {

    // MARK: Reading

    /// Reads the phone's settings into `autoBackupInfo` (main thread).
    func refreshAutoBackupInfo() {
        let settings = AutoBackupSettings(defaults: defaults)
        var info = AutoBackupInfo()
        if settings.bookmark != nil {
            info.folderName = settings.folderName ?? String(localized: "autoBackup.folderDefault")
        }
        let last = settings.lastSuccessMillis
        info.lastSuccess = last > 0 ? Date(timeIntervalSince1970: Double(last) / 1000) : nil
        info.message = settings.lastError
        info.hasPassword = settings.hasPassword
        info.noteDismissed = settings.noteDismissed
        autoBackupInfo = info
    }

    // MARK: When

    /// The app became active (or the data became ready): backs up when the last good backup is more than a day old. Does nothing without
    /// a folder, while one is running, or soon after a try that failed.
    func autoBackupIfDue() {
        if !isReady || autoBackupRunning { return }
        let settings = AutoBackupSettings(defaults: defaults)
        if settings.bookmark == nil { return }
        if !settings.isDue { return }
        runAutoBackup(mode: .scheduled)
    }

    /// "Back up to the folder now".
    func backUpToFolderNow() {
        if autoBackupRunning { return }
        runAutoBackup(mode: .manual)
    }

    /// Books the best-effort background refresh (when the app goes to the background and a folder is chosen).
    func scheduleAutoBackupRefresh() {
        if AutoBackupSettings(defaults: defaults).bookmark != nil { AutoBackupSchedule.schedule() }
    }

    private func runAutoBackup(mode: AutoBackupMode) {
        let settings = AutoBackupSettings(defaults: defaults)
        autoBackupRunning = true
        queue.async {
            guard let opened = self.store else {
                self.publish {
                    self.autoBackupRunning = false
                    self.refreshAutoBackupInfo()
                }
                return
            }
            let runner = AutoBackupRunner(settings: settings, makeBytes: { password in try AutoBackupSchedule.bytes(of: opened, password: password) })
            let report = runner.run(mode: mode, now: Date(), open: { try AutoBackupFolder.access(settings) })
            if report.saved { AutoBackupAlert.withdraw() }
            if report.notifyNow {
                AutoBackupAlert.post(center: self.noticeCenter) { shown in
                    if shown { settings.markAlerted() }
                }
            }
            self.publish {
                self.autoBackupRunning = false
                self.refreshAutoBackupInfo()
            }
        }
    }

    // MARK: Choosing

    /// The folder picker answered. The bookmark is kept; a first backup is made at once so the person sees it work.
    func chooseBackupFolder(_ result: Result<[URL], Error>) {
        switch result {
        case .failure:
            return
        case .success(let urls):
            guard let url = urls.first else { return }
            let settings = AutoBackupSettings(defaults: defaults)
            do {
                settings.bookmark = try AutoBackupFolder.bookmark(for: url)
                settings.folderName = url.lastPathComponent
                settings.lastError = nil
                settings.store(AutoBackupBridge.shared.afterSuccess())
            } catch {
                settings.lastError = String(localized: "failure.locationGone")
                refreshAutoBackupInfo()
                return
            }
            refreshAutoBackupInfo()
            AutoBackupSchedule.schedule()
            runAutoBackup(mode: .manual)
        }
    }

    /// Stops automatic backups: the folder is forgotten; the files in it stay.
    func stopAutoBackup() {
        AutoBackupSettings(defaults: defaults).forgetFolder()
        AutoBackupAlert.withdraw()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: AutoBackupSchedule.identifier)
        refreshAutoBackupInfo()
    }

    /// Sets (or, with empty text, removes) the password the automatic backups are protected with.
    func setAutoBackupPassword(_ text: String) {
        AutoBackupSettings(defaults: defaults).setPassword(text)
        refreshAutoBackupInfo()
    }

    func dismissAutoBackupNote() {
        AutoBackupSettings(defaults: defaults).noteDismissed = true
        refreshAutoBackupInfo()
    }
}
