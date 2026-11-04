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
            importer.begin(url, replacing: false)
        } else if route == "IMPORT_REPLACE" {
            settingsRequest += 1
            importer.begin(url, replacing: true)
        }
    }

    /// Called when the app has finished opening: a file that came in during the opening is read now.
    func drainPendingFile() {
        guard let url = pendingOpenURL else { return }
        pendingOpenURL = nil
        receiveFile(url)
    }

    /// True once the app can take a file (the first-run choice or the tabs are up).
    var canTakeFiles: Bool { return isFirstRun || isReady }
}
