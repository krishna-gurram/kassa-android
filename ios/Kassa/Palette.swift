import SwiftUI
import UIKit
import KassaShared

/// The colours of every screen: the system's semantic ones, so light and dark both work. Colour is only for state: the shared
/// rules decide it (a `Tone`, always Ink while amounts are hidden) and this only maps it to a colour.
enum Palette {
    static let ground = Color(uiColor: UIColor.systemBackground)
    static let line = Color(uiColor: UIColor.separator)
    static let chip = Color(uiColor: UIColor.tertiarySystemFill)
    static let ink = Color.primary
    static let ink2 = Color.secondary
    static let green = Color(uiColor: UIColor.systemGreen)
    static let orange = Color(uiColor: UIColor.systemOrange)

    /// The colour of a figure. [muted] is the secondary ink for amounts that are not spending or income.
    static func figure(_ tone: Tone, muted: Bool = false) -> Color {
        if tone == Tone.orange { return orange }
        if tone == Tone.green { return green }
        return muted ? ink2 : ink
    }

    /// The tint of a hero block: green or orange, grey for Ink (and so while amounts are hidden).
    static func heroTint(_ tone: Tone) -> Color {
        if tone == Tone.orange { return orange.opacity(0.16) }
        if tone == Tone.green { return green.opacity(0.16) }
        return chip
    }
}

extension Color {
    /// A category colour as the backup stores it: ARGB in an Int32. The alpha byte is ignored (the golden file has 0).
    init(argb: Int32) {
        let bits = UInt32(bitPattern: argb)
        let red = Double((bits >> 16) & 0xFF) / 255.0
        let green = Double((bits >> 8) & 0xFF) / 255.0
        let blue = Double(bits & 0xFF) / 255.0
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: 1)
    }
}
