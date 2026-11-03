import UniformTypeIdentifiers

enum KassaTypes {
    /// The .kassa backup file. Declared as an exported type in Info.plist (project.yml) for the extension "kassa", conforming to public.data.
    static let backup: UTType = UTType(exportedAs: "app.kassa.money.backup", conformingTo: UTType.data)
}
