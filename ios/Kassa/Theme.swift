import SwiftUI
import UIKit
import CoreText

/// The app's typeface: Figtree (SIL OFL 1.1), four static weights built from the Google Fonts variable file, listed in
/// `UIAppFonts` in project.yml. If the font can't be loaded, everything falls back to the system font at the same sizes.
enum KassaFont {
    static let regularName = "Figtree-Regular"
    static let mediumName = "Figtree-Medium"
    static let semiboldName = "Figtree-SemiBold"
    static let boldName = "Figtree-Bold"

    private static let files: [String] = [regularName, mediumName, semiboldName, boldName]

    /// Registers the bundled files for the process when `UIAppFonts` did not already (a safety net; registering twice is harmless).
    static func registerIfNeeded() {
        for file in files {
            if UIFont(name: file, size: 12) != nil { continue }
            var url: URL? = Bundle.main.url(forResource: file, withExtension: "ttf")
            if url == nil { url = Bundle.main.url(forResource: file, withExtension: "ttf", subdirectory: "Fonts") }
            if let found = url {
                CTFontManagerRegisterFontsForURL(found as CFURL, .process, nil)
            }
        }
    }

    /// Whether the bundled font loads.
    static var available: Bool {
        return UIFont(name: regularName, size: 12) != nil
    }

    /// The PostScript name for a weight: Regular, Medium, SemiBold or Bold.
    static func name(_ weight: Font.Weight) -> String {
        if weight == .bold || weight == .heavy || weight == .black { return boldName }
        if weight == .semibold { return semiboldName }
        if weight == .medium { return mediumName }
        return regularName
    }

    private static func uiWeight(_ weight: Font.Weight) -> UIFont.Weight {
        if weight == .bold || weight == .heavy || weight == .black { return .bold }
        if weight == .semibold { return .semibold }
        if weight == .medium { return .medium }
        return .regular
    }

    static func uiStyle(_ style: Font.TextStyle) -> UIFont.TextStyle {
        switch style {
        case .largeTitle: return .largeTitle
        case .title: return .title1
        case .title2: return .title2
        case .title3: return .title3
        case .headline: return .headline
        case .subheadline: return .subheadline
        case .callout: return .callout
        case .footnote: return .footnote
        case .caption: return .caption1
        case .caption2: return .caption2
        default: return .body
        }
    }

    /// A UIKit font at [size] points, grown with Dynamic Type like [style] (for navigation bars and other UIKit parts).
    static func ui(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> UIFont {
        let scaled: CGFloat = UIFontMetrics(forTextStyle: uiStyle(style)).scaledValue(for: size)
        if let font = UIFont(name: name(weight), size: scaled) { return font }
        return UIFont.systemFont(ofSize: scaled, weight: uiWeight(weight))
    }

    /// UIKit parts that SwiftUI can't restyle: navigation bar titles and bar buttons use the app font and ink.
    static func installAppearance() {
        let ink: UIColor = PaletteUI.ink
        let nav = UINavigationBar.appearance()
        nav.titleTextAttributes = [.font: ui(17, .semibold, relativeTo: .headline), .foregroundColor: ink]
        nav.largeTitleTextAttributes = [.font: ui(30, .bold, relativeTo: .largeTitle), .foregroundColor: ink]
        let button = UIBarButtonItem.appearance()
        button.setTitleTextAttributes([.font: ui(17, .regular, relativeTo: .body)], for: .normal)
        button.setTitleTextAttributes([.font: ui(17, .regular, relativeTo: .body)], for: .highlighted)
        let segmented = UISegmentedControl.appearance()
        segmented.setTitleTextAttributes([.font: ui(13, .semibold, relativeTo: .footnote)], for: .normal)
    }
}

extension Font {
    /// The app font at [size] points, scaling with Dynamic Type as [style] does. Falls back to the system font if Figtree is missing.
    static func kassa(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        if KassaFont.available {
            return Font.custom(KassaFont.name(weight), size: size, relativeTo: style)
        }
        let scaled: CGFloat = UIFontMetrics(forTextStyle: KassaFont.uiStyle(style)).scaledValue(for: size)
        return Font.system(size: scaled, weight: weight)
    }

    /// The app font at a fixed size (for the big numerals that scale by their own rule, `scaledNumeral`).
    static func kassaFixed(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        if KassaFont.available {
            return Font.custom(KassaFont.name(weight), fixedSize: size)
        }
        return Font.system(size: size, weight: weight)
    }
}

/// Android's type scale (`KassaType`): size, weight, tracking, and which text style it grows with.
struct KassaStyle {
    let size: CGFloat
    let weight: Font.Weight
    let tracking: CGFloat
    let relativeTo: Font.TextStyle
    let uppercase: Bool
    let tabular: Bool

    static let hero = KassaStyle(size: 56, weight: .bold, tracking: -2, relativeTo: .largeTitle, uppercase: false, tabular: true)
    static let title = KassaStyle(size: 30, weight: .bold, tracking: -0.6, relativeTo: .largeTitle, uppercase: false, tabular: false)
    static let section = KassaStyle(size: 18, weight: .semibold, tracking: 0, relativeTo: .title3, uppercase: false, tabular: false)
    static let body = KassaStyle(size: 15, weight: .regular, tracking: 0, relativeTo: .body, uppercase: false, tabular: true)
    static let bodyStrong = KassaStyle(size: 15, weight: .semibold, tracking: 0, relativeTo: .body, uppercase: false, tabular: true)
    static let small = KassaStyle(size: 13, weight: .regular, tracking: 0, relativeTo: .footnote, uppercase: false, tabular: true)
    static let tiny = KassaStyle(size: 12, weight: .medium, tracking: 0, relativeTo: .caption, uppercase: false, tabular: true)
    /// A small uppercase label over a list (section headers).
    static let label = KassaStyle(size: 11, weight: .bold, tracking: 0.6, relativeTo: .caption2, uppercase: true, tabular: false)
    static let stat = KassaStyle(size: 20, weight: .bold, tracking: 0, relativeTo: .title3, uppercase: false, tabular: true)

    var font: Font {
        let base: Font = Font.kassa(size, weight, relativeTo: relativeTo)
        return tabular ? base.monospacedDigit() : base
    }
}

private struct KassaStyleModifier: ViewModifier {
    let style: KassaStyle

    func body(content: Content) -> some View {
        return content
            .font(style.font)
            .tracking(style.tracking)
            .textCase(style.uppercase ? .uppercase : nil)
    }
}

extension View {
    /// Sets the font, tracking and case of one of Android's type styles: `.kassa(.title)`.
    func kassa(_ style: KassaStyle) -> some View {
        return modifier(KassaStyleModifier(style: style))
    }
}
