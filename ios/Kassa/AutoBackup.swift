import Foundation
import Security
import UserNotifications
import KassaShared

/// Automatic backups into a folder the person chose (Android's daily backup, adapted: iOS cannot run anything at a set time).
///
/// What decides is :domain's (`AutoBackupRules`, `AutoBackupStreak`, through `AutoBackupBridge`): whether a backup is due, which files in the
/// folder are Kassa's own and which of those to delete, and the failed-day streak. This file only does what the phone has to do: keep the
/// folder bookmark and the numbers in UserDefaults, the password in the Keychain, write the file, delete the old ones and post the notification.
/// The file is exactly what Back up now writes (`BackupFile.make` over `BackupBridge.write`); the format is not touched.

// MARK: Secret

/// Where the password for automatic backups is kept. The Keychain on the phone; a stand-in in tests.
protocol SecretStore: AnyObject {
    func read() -> String?
    /// nil or empty removes it.
    func write(_ text: String?)
}

/// Generic password under service "app.kassa.money", account "auto-backup-password". `AfterFirstUnlockThisDeviceOnly`, so a background
/// refresh with the phone locked can still read it and it never leaves this phone.
final class KeychainSecret: SecretStore {

    static let service = "app.kassa.money"
    static let account = "auto-backup-password"

    private var identity: [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainSecret.service,
            kSecAttrAccount as String: KeychainSecret.account,
        ]
    }

    func read() -> String? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ text: String?) {
        SecItemDelete(identity as CFDictionary)
        guard let text = text, !text.isEmpty else { return }
        var attributes = identity
        attributes[kSecValueData as String] = Data(text.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }
}

final class MemorySecret: SecretStore {
    private var text: String?
    func read() -> String? { return text }
    func write(_ value: String?) { text = (value ?? "").isEmpty ? nil : value }
}

// MARK: Settings

/// What the phone remembers about automatic backups (the counterpart of Android's backup settings): the folder bookmark and its name,
/// the last good backup and the last try (epoch milliseconds, 0 for never), the last plain problem, the failed-day streak, and whether the
/// "not encrypted" note was dismissed.
final class AutoBackupSettings {

    static let bookmarkKey = "autoBackupBookmark"
    static let folderNameKey = "autoBackupFolderName"
    static let lastSuccessKey = "autoBackupLastSuccess"
    static let lastAttemptKey = "autoBackupLastAttempt"
    static let lastErrorKey = "autoBackupLastError"
    static let failedDaysKey = "autoBackupFailedDays"
    static let lastFailedDayKey = "autoBackupLastFailedDay"
    static let alertedKey = "autoBackupAlerted"
    static let noteDismissedKey = "autoBackupNoteDismissed"

    let defaults: UserDefaults
    let secret: SecretStore

    init(defaults: UserDefaults = UserDefaults.standard, secret: SecretStore = KeychainSecret()) {
        self.defaults = defaults
        self.secret = secret
    }

    var bookmark: Data? {
        get { return defaults.data(forKey: AutoBackupSettings.bookmarkKey) }
        set { defaults.set(newValue, forKey: AutoBackupSettings.bookmarkKey) }
    }

    var folderName: String? {
        get { return defaults.string(forKey: AutoBackupSettings.folderNameKey) }
        set { defaults.set(newValue, forKey: AutoBackupSettings.folderNameKey) }
    }

    var lastSuccessMillis: Int64 {
        get { return Int64(defaults.integer(forKey: AutoBackupSettings.lastSuccessKey)) }
        set { defaults.set(Int(newValue), forKey: AutoBackupSettings.lastSuccessKey) }
    }

    var lastAttemptMillis: Int64 {
        get { return Int64(defaults.integer(forKey: AutoBackupSettings.lastAttemptKey)) }
        set { defaults.set(Int(newValue), forKey: AutoBackupSettings.lastAttemptKey) }
    }

    var lastError: String? {
        get { return defaults.string(forKey: AutoBackupSettings.lastErrorKey) }
        set { defaults.set(newValue, forKey: AutoBackupSettings.lastErrorKey) }
    }

    var noteDismissed: Bool {
        get { return defaults.bool(forKey: AutoBackupSettings.noteDismissedKey) }
        set { defaults.set(newValue, forKey: AutoBackupSettings.noteDismissedKey) }
    }

    var password: String? { return secret.read() }

    var hasPassword: Bool { return !(secret.read() ?? "").isEmpty }

    func setPassword(_ text: String?) {
        secret.write(text)
    }

    var failedDays: Int { return defaults.integer(forKey: AutoBackupSettings.failedDaysKey) }

    var lastFailedDay: Int64 {
        if defaults.object(forKey: AutoBackupSettings.lastFailedDayKey) == nil { return -1 }
        return Int64(defaults.integer(forKey: AutoBackupSettings.lastFailedDayKey))
    }

    var alerted: Bool { return defaults.bool(forKey: AutoBackupSettings.alertedKey) }

    func store(_ streak: BackupStreakData) {
        defaults.set(Int(streak.failedDays), forKey: AutoBackupSettings.failedDaysKey)
        defaults.set(Int(streak.lastFailedDay), forKey: AutoBackupSettings.lastFailedDayKey)
        defaults.set(streak.alerted, forKey: AutoBackupSettings.alertedKey)
    }

    /// The notification was really shown: no repeat for this streak.
    func markAlerted() {
        store(AutoBackupBridge.shared.afterAlert(failedDays: Int32(failedDays), lastFailedDay: lastFailedDay, alerted: alerted))
    }

    /// Forgets the folder (Stop). The password, the note and the history stay; nothing in the folder is touched.
    func forgetFolder() {
        bookmark = nil
        folderName = nil
        lastError = nil
        store(AutoBackupBridge.shared.afterSuccess())
    }

    static func millis(_ date: Date) -> Int64 {
        return Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
    }

    /// The phone's calendar day as days since 1970-01-01, for the streak.
    static func epochDay(_ date: Date, zone: TimeZone = TimeZone.current) -> Int64 {
        let local = date.timeIntervalSince1970 + Double(zone.secondsFromGMT(for: date))
        return Int64((local / 86_400).rounded(.down))
    }

    var isDue: Bool {
        return due(now: Date())
    }

    func due(now: Date) -> Bool {
        return AutoBackupBridge.shared.isDue(lastSuccessMillis: lastSuccessMillis, lastAttemptMillis: lastAttemptMillis, nowMillis: AutoBackupSettings.millis(now))
    }
}

// MARK: Folder

enum AutoBackupError: Error {
    case noFolder
    case folderGone
}

/// An opened folder: its URL while the security scope is open, and the call that closes it.
struct AutoBackupAccess {
    let url: URL
    let stop: () -> Void
}

enum AutoBackupFolder {

    /// The bookmark for a folder the document picker returned. The picker's URL is only usable inside its security scope.
    static func bookmark(for url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    /// Opens the chosen folder: resolves the bookmark (a moved folder is followed), opens its scope and checks it is still there.
    /// Throws `AutoBackupError.noFolder` when none was chosen, `folderGone` when it cannot be reached any more.
    static func access(_ settings: AutoBackupSettings) throws -> AutoBackupAccess {
        guard let data = settings.bookmark else { throw AutoBackupError.noFolder }
        var stale = false
        let url: URL
        do {
            url = try URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            throw AutoBackupError.folderGone
        }
        let scoped = url.startAccessingSecurityScopedResource()
        let stop: () -> Void = { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            _ = try url.checkResourceIsReachable()
        } catch {
            stop()
            throw AutoBackupError.folderGone
        }
        if stale {
            let fresh: Data? = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            if let renewed = fresh { settings.bookmark = renewed }
        }
        return AutoBackupAccess(url: url, stop: stop)
    }
}

// MARK: Files

/// The file work: write one backup so a half-written file never stays, and delete Kassa's own old ones.
enum AutoBackupFiles {

    /// Writes [bytes] as [name] in [folder] (to a temporary file that replaces the target, so no cut-short file appears under the name).
    /// If anything fails, the file this call made is removed. Throws the underlying error.
    static func save(_ bytes: Data, named name: String, in folder: URL) throws {
        let target = folder.appendingPathComponent(name)
        let existed = FileManager.default.fileExists(atPath: target.path)
        var coordinationError: NSError?
        var writeError: Error?
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { url in
            do {
                try bytes.write(to: url, options: .atomic)
            } catch {
                writeError = error
            }
        }
        if let problem = coordinationError {
            if !existed { try? FileManager.default.removeItem(at: target) }
            throw problem
        }
        if let problem = writeError {
            if !existed { try? FileManager.default.removeItem(at: target) }
            throw problem
        }
        let written = try FileManager.default.attributesOfItem(atPath: target.path)
        let size: NSNumber? = written[FileAttributeKey.size] as? NSNumber
        if size?.intValue != bytes.count {
            if !existed { try? FileManager.default.removeItem(at: target) }
            throw BackupWriteError.compressionFailed
        }
    }

    /// Deletes Kassa's own backups beyond the newest 30 (:domain decides which; a file that does not match the name exactly is never in
    /// the list). A file that cannot be deleted is left; the backup is safe either way. Returns how many were deleted.
    @discardableResult
    static func trim(in folder: URL) -> Int {
        let listed: [String]? = try? FileManager.default.contentsOfDirectory(atPath: folder.path)
        let names: [String] = listed ?? []
        let doomed: [String] = AutoBackupBridge.shared.toDelete(names: names)
        var removed = 0
        for name in doomed {
            do {
                try FileManager.default.removeItem(at: folder.appendingPathComponent(name))
                removed += 1
            } catch {
                continue
            }
        }
        return removed
    }
}

// MARK: Running one

/// Who asked for the backup. A scheduled one (the app became active) counts toward the failed-day streak; a manual one only shows its
/// problem; a background refresh is best effort and says nothing when it fails (a locked phone is not a failed day).
enum AutoBackupMode {
    case scheduled
    case manual
    case background
}

struct AutoBackupReport: Equatable {
    let saved: Bool
    /// The plain sentence for a problem, or nil.
    let message: String?
    /// "Backup needs attention" is due now (the third failed day in a row).
    let notifyNow: Bool
}

final class AutoBackupRunner {

    let settings: AutoBackupSettings
    /// The bytes of the .kassa file for the given password (nil: none), made the same way as Back up now.
    let makeBytes: (String?) throws -> Data

    init(settings: AutoBackupSettings, makeBytes: @escaping (String?) throws -> Data) {
        self.settings = settings
        self.makeBytes = makeBytes
    }

    /// Makes one backup into the folder [open] gives, tidies the folder, and records what happened.
    func run(mode: AutoBackupMode, now: Date, open: () throws -> AutoBackupAccess) -> AutoBackupReport {
        let access: AutoBackupAccess
        do {
            access = try open()
        } catch AutoBackupError.noFolder {
            return failed(String(localized: "autoBackup.pickFirst"), mode: mode, now: now)
        } catch {
            return failed(String(localized: "failure.locationGone"), mode: mode, now: now)
        }
        defer { access.stop() }
        do {
            let bytes = try makeBytes(settings.password)
            let name = BackupNames.fileName(for: now)
            try AutoBackupFiles.save(bytes, named: name, in: access.url)
            succeeded(now: now)
            // Tidying is secondary: the backup is safe even if an old file cannot be deleted.
            AutoBackupFiles.trim(in: access.url)
            return AutoBackupReport(saved: true, message: nil, notifyNow: false)
        } catch {
            return failed(Failures.writeBackup(error), mode: mode, now: now)
        }
    }

    private func succeeded(now: Date) {
        let millis = AutoBackupSettings.millis(now)
        settings.lastSuccessMillis = millis
        settings.lastAttemptMillis = millis
        settings.lastError = nil
        settings.store(AutoBackupBridge.shared.afterSuccess())
    }

    private func failed(_ message: String, mode: AutoBackupMode, now: Date) -> AutoBackupReport {
        if mode == .background {
            return AutoBackupReport(saved: false, message: nil, notifyNow: false)
        }
        settings.lastAttemptMillis = AutoBackupSettings.millis(now)
        settings.lastError = message
        if mode == .manual {
            return AutoBackupReport(saved: false, message: message, notifyNow: false)
        }
        let after = AutoBackupBridge.shared.afterFailure(
            failedDays: Int32(settings.failedDays), lastFailedDay: settings.lastFailedDay, alerted: settings.alerted,
            epochDay: AutoBackupSettings.epochDay(now)
        )
        settings.store(after)
        return AutoBackupReport(saved: false, message: message, notifyNow: after.notifyNow)
    }
}

// MARK: The notification

/// "Backup needs attention": no amounts, nothing from the data. It only tells; a tap opens Settings > Backup. Not shown (and so offered
/// again on the next failed day) when notifications are not allowed.
enum AutoBackupAlert {

    static let identifier = "app.kassa.backupAlert"
    static let kind = "BACKUP"

    static func notice() -> ScheduledNotice {
        return ScheduledNotice(
            identifier: identifier, title: String(localized: "autoBackup.alertTitle"), body: String(localized: "autoBackup.alertBody"),
            components: nil, seconds: 1, repeats: false, kind: kind, id: 0
        )
    }

    /// Posts it when the person allowed notifications; [done] says whether it was handed to the system.
    static func post(center: NoticeCenter, done: @escaping (Bool) -> Void) {
        center.status { status in
            if status != .allowed {
                done(false)
                return
            }
            center.replace(prefix: identifier, with: [notice()]) { done(true) }
        }
    }

    /// A working backup ends the notification too.
    static func withdraw() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }
}
