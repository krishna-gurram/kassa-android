import SwiftUI
import UIKit
import KassaShared

/// Kassa's colour tokens, the same hex values as Android's `ui/theme/Theme.kt` (`Light` and `Dark`), as dynamic colours so light and
/// dark both work and follow `preferredColorScheme`. Colour is only for state and categories: the shared rules decide it (a `Tone`,
/// always Ink while amounts are hidden) and this only maps it to a colour. Primary actions are ink on ground.
enum PaletteUI {
    /// A colour from 0xRRGGBB.
    static func rgb(_ hex: UInt32) -> UIColor {
        let red = CGFloat((hex >> 16) & 0xFF) / 255.0
        let green = CGFloat((hex >> 8) & 0xFF) / 255.0
        let blue = CGFloat(hex & 0xFF) / 255.0
        return UIColor(red: red, green: green, blue: blue, alpha: 1)
    }

    /// One token that is [light] in light mode and [dark] in dark mode.
    static func token(_ light: UInt32, _ dark: UInt32) -> UIColor {
        return UIColor { trait in
            return trait.userInterfaceStyle == .dark ? rgb(dark) : rgb(light)
        }
    }

    static let ground = token(0xF3F4F1, 0x111214)
    static let raised = token(0xFFFFFF, 0x1B1C1F)
    static let chip = token(0xE8EAE5, 0x25272A)
    static let line = token(0xE0E2DC, 0x2A2C30)
    static let track = token(0xE4E6E1, 0x2B2D31)
    static let ink = token(0x1A1C1F, 0xECEDEA)
    static let ink2 = token(0x5E636A, 0xA3A7AD)
    static let ink3 = token(0x959AA1, 0x6E7279)
    static let onInk = token(0xF7F7F4, 0x141517)
    static let positive = token(0x0E7A52, 0x48C294)
    static let attention = token(0xB4531A, 0xEF9459)

    static let heroGreen = token(0xE1EDE4, 0x1B2D24)
    static let heroGreenInk = token(0x3C6A4B, 0x8CC3A2)
    static let heroOrange = token(0xF6E5D8, 0x33221A)
    static let heroOrangeInk = token(0x8A4310, 0xE6A878)
    static let heroGrey = token(0xE8EAE5, 0x25272A)
    static let heroGreyInk = token(0x5E636A, 0xA3A7AD)

    /// Category tint pairs from the design (the icon colour and its pale background).
    static let teal = token(0x2A9D8F, 0x4DBFB0)
    static let tealBg = token(0xD8EDE9, 0x1B3532)
    static let blue = token(0x3B82C4, 0x6AA6E0)
    static let blueBg = token(0xDCE8F4, 0x1B2A3B)
    static let pink = token(0xD45D84, 0xEC8AAA)
    static let pinkBg = token(0xF6DCE5, 0x3A1F2B)
    static let sage = token(0x7FA88A, 0x8CC3A2)
    static let sageBg = token(0xE1EAD9, 0x1E2E24)
}

/// An icon colour with the pale background a round badge sits on.
struct TintPair {
    let fg: Color
    let bg: Color
}

enum Palette {
    static let ground = Color(uiColor: PaletteUI.ground)
    static let raised = Color(uiColor: PaletteUI.raised)
    static let chip = Color(uiColor: PaletteUI.chip)
    static let line = Color(uiColor: PaletteUI.line)
    static let track = Color(uiColor: PaletteUI.track)
    static let ink = Color(uiColor: PaletteUI.ink)
    static let ink2 = Color(uiColor: PaletteUI.ink2)
    static let ink3 = Color(uiColor: PaletteUI.ink3)
    /// The text on an ink button or pill.
    static let onInk = Color(uiColor: PaletteUI.onInk)
    static let positive = Color(uiColor: PaletteUI.positive)
    static let attention = Color(uiColor: PaletteUI.attention)
    /// In your favour (income, profit): the same colour as [positive].
    static let green = positive
    /// Against you beyond ordinary spending (owed, due, a loss): the same colour as [attention].
    static let orange = attention

    static let heroGreen = Color(uiColor: PaletteUI.heroGreen)
    static let heroGreenInk = Color(uiColor: PaletteUI.heroGreenInk)
    static let heroOrange = Color(uiColor: PaletteUI.heroOrange)
    static let heroOrangeInk = Color(uiColor: PaletteUI.heroOrangeInk)
    static let heroGrey = Color(uiColor: PaletteUI.heroGrey)
    static let heroGreyInk = Color(uiColor: PaletteUI.heroGreyInk)

    static let teal = TintPair(fg: Color(uiColor: PaletteUI.teal), bg: Color(uiColor: PaletteUI.tealBg))
    static let blue = TintPair(fg: Color(uiColor: PaletteUI.blue), bg: Color(uiColor: PaletteUI.blueBg))
    static let pink = TintPair(fg: Color(uiColor: PaletteUI.pink), bg: Color(uiColor: PaletteUI.pinkBg))
    static let sage = TintPair(fg: Color(uiColor: PaletteUI.sage), bg: Color(uiColor: PaletteUI.sageBg))
    /// The grey badge: chip behind an ink2 icon.
    static let neutral = TintPair(fg: ink2, bg: chip)

    /// The colour of a figure. [muted] is the secondary ink for amounts that are not spending or income.
    static func figure(_ tone: Tone, muted: Bool = false) -> Color {
        if tone.name == "Orange" { return orange }
        if tone.name == "Green" { return green }
        return muted ? ink2 : ink
    }

    /// The tint of a hero block: green or orange, grey for Ink (and so while amounts are hidden).
    static func heroTint(_ tone: Tone) -> Color {
        if tone.name == "Orange" { return heroOrange }
        if tone.name == "Green" { return heroGreen }
        return heroGrey
    }

    /// The ink of a hero's small labels, matching [heroTint].
    static func heroInk(_ tone: Tone) -> Color {
        if tone.name == "Orange" { return heroOrangeInk }
        if tone.name == "Green" { return heroGreenInk }
        return heroGreyInk
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
