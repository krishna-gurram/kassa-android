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
