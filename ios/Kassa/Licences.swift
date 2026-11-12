import Foundation

/// One credited library and the bundled text file with its licence (the same files Android shows, from `app/src/main/assets/licences`).
struct LicenceEntry: Identifiable, Equatable {
    let name: String
    let licence: String
    let file: String

    var id: String { name }
}

/// The third-party notices page. SQLCipher's BSD-style notice must be shown before release; the Kotlin runtime and libraries inside
/// `KassaShared` are Apache 2.0.
enum Licences {

    static let all: [LicenceEntry] = [
        LicenceEntry(name: "Kotlin, kotlinx.coroutines and kotlinx-datetime (KassaShared)", licence: "Apache 2.0", file: "apache-2.0"),
        LicenceEntry(name: "SQLCipher", licence: "BSD-style", file: "sqlcipher"),
        LicenceEntry(name: "Hanken Grotesk font", licence: "SIL OFL 1.1", file: "ofl-1.1"),
    ]

    /// The full text of [entry]'s licence, or "" when the file is missing from the bundle.
    static func text(for entry: LicenceEntry, bundle: Bundle = Bundle.main) -> String {
        guard let url = bundle.url(forResource: entry.file, withExtension: "txt") else { return "" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }
}
