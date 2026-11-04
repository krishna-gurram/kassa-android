import Foundation
import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// A backup file made and waiting to be saved: its bytes (the .kassa envelope) and the name Android would give it.
struct BackupExport: Identifiable {
    let id = UUID()
    let name: String
    let bytes: Data
}

/// The file the system's exporter writes: the bytes of a .kassa backup, unchanged.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [KassaTypes.backup] }
    static var writableContentTypes: [UTType] { [KassaTypes.backup] }

    let bytes: Data

    init(bytes: Data) {
        self.bytes = bytes
    }

    init(configuration: ReadConfiguration) throws {
        bytes = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        return FileWrapper(regularFileWithContents: bytes)
    }
}

/// `Kassa_yyyyMMdd_HHmmss.kassa`, the name Android's backups get (a file name, not text for people, so the pattern is fixed).
enum BackupNames {

    static func fileName(for date: Date, zone: TimeZone = TimeZone.current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return "Kassa_" + formatter.string(from: date) + ".kassa"
    }
}

/// What Settings does: back up now, restore over what is there, change the currency. Same queue and rules as the rest of `AppModel`;
/// every failure ends as a plain sentence (`Failures`).
extension AppModel {

    // MARK: Back up

    /// Makes a backup of everything in the store, wrapped with [password] when one is given (nil or empty: a file anyone can open),
    /// and hands it over as `pendingExport` for the file exporter. The key derivation takes a moment, so `working` shows meanwhile.
    func makeBackup(password: String?) {
        working = true
        restoreMessage = nil
        notice = nil
        queue.async {
            guard let opened = self.store else {
                self.fail(String(localized: "failure.backupNotSaved"))
                return
            }
            do {
                let data = try opened.export()
                let json = BackupBridge.shared.write(data: data)
                let bytes = try BackupFile.make(json: json, password: password)
                let name = BackupNames.fileName(for: Date())
                let made = BackupExport(name: name, bytes: bytes)
                self.publish {
                    self.working = false
                    self.pendingExport = made
                }
            } catch {
                self.fail(Failures.writeBackup(error))
            }
        }
    }

    /// The file exporter finished: [error] is nil when the file was saved.
    func exportFinished(error: Error?) {
        pendingExport = nil
        if let problem = error {
            let bridged = problem as NSError
            if bridged.domain == NSCocoaErrorDomain && bridged.code == 3072 { return } // userCancelled: nothing was written
            publishRestore(message: Failures.writeBackup(problem), notice: nil)
        } else {
            publishRestore(message: nil, notice: String(localized: "settings.backupSaved"))
        }
    }

    /// The person left the exporter without saving: nothing was written.
    func exportCancelled() {
        pendingExport = nil
    }

    // MARK: Currency

    /// Shows amounts in [code] (a code Kassa lists). Nothing is converted: every amount keeps its number. Only a change of currency
    /// writes anything.
    func changeCurrency(_ code: String) {
        if code == currency { return }
        let given = inputs()
        queue.async {
            guard let opened = self.store else { return }
            do {
                try opened.setCurrency(code)
                try self.load(opened, given)
                self.publish { self.chosenCurrency = code }
            } catch {
                self.fail(String(localized: "write.failed"))
            }
        }
    }

    /// The display name of the ledger's currency ("US dollar"), or its code when Kassa has no name for it.
    var currencyName: String {
        let options: [CurrencyOptionData] = StartBridge.shared.currencies()
        let found: CurrencyOptionData? = options.first { $0.code == currency }
        return found?.name ?? currency
    }

    // MARK: Helpers

    private func publishRestore(message: String?, notice text: String?) {
        restoreMessage = message
        notice = text
    }
}
