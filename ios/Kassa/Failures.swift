import Foundation

/// The one place that turns a failure into words for the person, as Android's `data/Failures.kt` does. Raw error text never reaches
/// the screen: it can hold file paths or names, and means nothing to someone using a money app. The sentences are Android's, kept in
/// Localizable.strings (keys `failure.*`); anything unknown gets the plain line for what was being done.
enum Failures {

    // MARK: Reading a backup file

    /// A file that could not be read when it was picked.
    static func readFile(_ error: Error) -> String {
        let code = cocoaCode(error)
        if code == CocoaCode.readNoPermission || code == CocoaCode.readNoSuchFile || code == CocoaCode.fileNoSuchFile {
            return String(localized: "failure.fileUnreadable")
        }
        if code == CocoaCode.fileReadCorrupt {
            return String(localized: "failure.fileDamaged")
        }
        return String(localized: "failure.couldntOpen")
    }

    // MARK: Writing a backup file

    /// A backup that could not be made or saved.
    static func writeBackup(_ error: Error) -> String {
        if error is BackupWriteError { return String(localized: "failure.backupNotSaved") }
        let code = cocoaCode(error)
        if code == CocoaCode.writeOutOfSpace { return String(localized: "failure.noSpace") }
        if code == CocoaCode.writeNoPermission || code == CocoaCode.fileNoSuchFile || code == CocoaCode.fileWriteVolumeReadOnly {
            return String(localized: "failure.locationGone")
        }
        return String(localized: "failure.backupNotSaved")
    }

    // MARK: Importing from another app

    /// A picked Money Manager or Cashew file that could not be copied, opened or read (Android's `Doing.IMPORT_FILE`).
    static func importFile(_ error: Error) -> String {
        let code = cocoaCode(error)
        if code == CocoaCode.writeOutOfSpace { return String(localized: "failure.noSpace") }
        if code == CocoaCode.readNoPermission || code == CocoaCode.readNoSuchFile || code == CocoaCode.fileNoSuchFile {
            return String(localized: "failure.fileUnreadable")
        }
        if code == CocoaCode.fileReadCorrupt || error is SQLiteError {
            return String(localized: "failure.fileDamaged")
        }
        return String(localized: "failure.importFileGeneric")
    }

    /// An import that could not be saved: nothing was changed (Android's `Doing.IMPORT_SAVE`).
    static func importSave(_ error: Error) -> String {
        if cocoaCode(error) == CocoaCode.writeOutOfSpace { return String(localized: "failure.noSpace") }
        if let problem = error as? SQLiteError, problem.code == 13 { return String(localized: "failure.noSpace") } // SQLITE_FULL
        return String(localized: "failure.importNotSaved")
    }

    // MARK: Helpers

    /// The Foundation error code when [error] is one of Foundation's file errors, else 0.
    private static func cocoaCode(_ error: Error) -> Int {
        let bridged = error as NSError
        if bridged.domain == NSCocoaErrorDomain { return bridged.code }
        if bridged.domain == NSPOSIXErrorDomain && bridged.code == 28 { return CocoaCode.writeOutOfSpace } // ENOSPC
        return 0
    }

    /// The `NSCocoaErrorDomain` codes used above (CocoaError.Code raw values).
    private enum CocoaCode {
        static let fileNoSuchFile = 4
        static let writeNoPermission = 513
        static let readNoPermission = 257
        static let readNoSuchFile = 260
        static let fileReadCorrupt = 259
        static let writeOutOfSpace = 640
        static let fileWriteVolumeReadOnly = 642
    }
}
