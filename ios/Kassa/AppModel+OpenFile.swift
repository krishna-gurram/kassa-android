import Foundation
import KassaShared

/// Files handed to Kassa from outside: Files ("Open in Kassa") and the share sheet. Only a file URL is taken; the kind of file is
/// worked out from what is inside it by the same import as a picked file (`ImportModel.begin`, `PickedFileRules` in :importer), so a
/// .kassa, Money Manager or Cashew file each go where they do from Settings. Where the file goes is :shared's `ExternalOpen`.
/// Nothing is read from the network: the file is already on the phone.
extension AppModel {

    /// The name `ExternalOpen.route` is asked with for the phase the app is in.
    private var openState: String {
        switch phase {
        case .opening: return "OPENING"
        case .firstRun: return "FIRST_RUN"
        case .ready: return "READY"
        case .failed: return "FAILED"
        }
    }

    /// A URL given to `onOpenURL`.
    func receiveFile(_ url: URL) {
        if !url.isFileURL { return }
        let route: String = ExternalOpen.shared.route(state: openState)
        if route == "WAIT" {
            pendingOpenURL = url
        } else if route == "IMPORT_NEW" {
            trackInboxCopy(url)
            importer.begin(url, replacing: false)
        } else if route == "IMPORT_REPLACE" {
            settingsRequest += 1
            trackInboxCopy(url)
            importer.begin(url, replacing: true)
        } else if InboxCleanup.shared.removeAfterFlow(path: url.path, flowActive: false) {
            // Nothing will read it (the data can't be opened): the copy goes.
            removeFile(url)
        }
    }

    /// Called when the app has finished opening: a file that came in during the opening is read now.
    func drainPendingFile() {
        sweepInboxSoon()
        guard let url = pendingOpenURL else { return }
        pendingOpenURL = nil
        receiveFile(url)
    }

    // MARK: Inbox copies

    /// iOS copies a file opened from Files or the share sheet into the app's Inbox. Kassa reads it once, so the copy is removed when the
    /// import or restore that reads it has finished, been cancelled or failed (`settleOpenedFile`), and anything older at launch
    /// (`sweepInbox`). Which files, and when, is :shared's `InboxCleanup`; this only removes the files it names.
    private func trackInboxCopy(_ url: URL) {
        if !InboxCleanup.shared.isInboxFile(path: url.path) { return }
        openedInboxURL = url
        watchOpenedFile()
    }

    private func watchOpenedFile() {
        if !inboxWatch.isEmpty { return }
        let again: () -> Void = { [weak self] in self?.settleOpenedFile() }
        inboxWatch.append(importer.$phase.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append(importer.$asksReplace.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append(importer.$asksCurrency.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append($working.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append($asksPassword.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append($asksReplace.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
        inboxWatch.append($asksCurrencyForBackup.dropFirst().receive(on: DispatchQueue.main).sink { _ in again() })
    }

    /// True while an import or restore still needs the file's contents or an answer from the person. A finished, cancelled or failed
    /// import is not going on.
    var openedFileInUse: Bool {
        switch importer.phase {
        case .reading, .questions, .review, .saving: return true
        case .idle, .done, .failed: break
        }
        if working || asksPassword || asksCurrencyForBackup || importer.asksReplace || importer.asksCurrency { return true }
        return asksReplace != nil
    }

    /// Removes the Inbox copy when nothing reads it any more.
    func settleOpenedFile() {
        guard let url = openedInboxURL else { return }
        if !InboxCleanup.shared.removeAfterFlow(path: url.path, flowActive: openedFileInUse) { return }
        openedInboxURL = nil
        removeFile(url)
    }

    /// The folders iOS puts opened files in: Documents/Inbox, and tmp/<bundle id>-Inbox.
    static func inboxFolders() -> [URL] {
        var folders: [URL] = []
        let documents: [URL] = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        for place in documents { folders.append(place.appendingPathComponent("Inbox", isDirectory: true)) }
        let temporary: URL = FileManager.default.temporaryDirectory
        let names: [String] = (try? FileManager.default.contentsOfDirectory(atPath: temporary.path)) ?? []
        for name in names where name.hasSuffix("-Inbox") {
            folders.append(temporary.appendingPathComponent(name, isDirectory: true))
        }
        return folders
    }

    /// At launch: removes every Inbox file except the one pending or being imported, so a cold start from a file still works.
    /// Runs once, a moment after the app is ready, so the file the app was launched with has been received by then.
    func sweepInbox(folders: [URL] = AppModel.inboxFolders()) {
        var found: [String] = []
        for folder in folders {
            let names: [String] = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            for name in names { found.append(folder.appendingPathComponent(name).path) }
        }
        var keep: [String] = []
        if let pending = pendingOpenURL { keep.append(pending.path) }
        if let opened = openedInboxURL { keep.append(opened.path) }
        let stale: [String] = InboxCleanup.shared.staleAtLaunch(found: found, keep: keep)
        for path in stale { removeFile(URL(fileURLWithPath: path)) }
    }

    /// Schedules the launch sweep once.
    func sweepInboxSoon() {
        if inboxSwept { return }
        inboxSwept = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.sweepInbox() }
    }

    private func removeFile(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    /// True once the app can take a file (the first-run choice or the tabs are up).
    var canTakeFiles: Bool { return isFirstRun || isReady }
}
