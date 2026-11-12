import XCTest
import SwiftUI
import UIKit
@testable import Kassa

/// The design foundation (batch D0): colour tokens equal Android's `Theme.kt` in light and dark, the app font loads, the type scale
/// is Android's.
final class DesignTests: XCTestCase {

    private func hex(_ color: UIColor, dark: Bool) -> UInt32 {
        let style: UIUserInterfaceStyle = dark ? .dark : .light
        let resolved: UIColor = color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let r: UInt32 = UInt32((red * 255).rounded())
        let g: UInt32 = UInt32((green * 255).rounded())
        let b: UInt32 = UInt32((blue * 255).rounded())
        return (r << 16) | (g << 8) | b
    }

    func testBaseTokensMatchAndroid() {
        XCTAssertEqual(hex(PaletteUI.ground, dark: false), 0xF3F4F1)
        XCTAssertEqual(hex(PaletteUI.ground, dark: true), 0x111214)
        XCTAssertEqual(hex(PaletteUI.raised, dark: false), 0xFFFFFF)
        XCTAssertEqual(hex(PaletteUI.raised, dark: true), 0x1B1C1F)
        XCTAssertEqual(hex(PaletteUI.chip, dark: false), 0xE8EAE5)
        XCTAssertEqual(hex(PaletteUI.chip, dark: true), 0x25272A)
        XCTAssertEqual(hex(PaletteUI.line, dark: false), 0xE0E2DC)
        XCTAssertEqual(hex(PaletteUI.line, dark: true), 0x2A2C30)
        XCTAssertEqual(hex(PaletteUI.track, dark: false), 0xE4E6E1)
        XCTAssertEqual(hex(PaletteUI.track, dark: true), 0x2B2D31)
        XCTAssertEqual(hex(PaletteUI.ink, dark: false), 0x1A1C1F)
        XCTAssertEqual(hex(PaletteUI.ink, dark: true), 0xECEDEA)
        XCTAssertEqual(hex(PaletteUI.ink2, dark: false), 0x5E636A)
        XCTAssertEqual(hex(PaletteUI.ink2, dark: true), 0xA3A7AD)
        XCTAssertEqual(hex(PaletteUI.ink3, dark: false), 0x959AA1)
        XCTAssertEqual(hex(PaletteUI.ink3, dark: true), 0x6E7279)
        XCTAssertEqual(hex(PaletteUI.onInk, dark: false), 0xF7F7F4)
        XCTAssertEqual(hex(PaletteUI.onInk, dark: true), 0x141517)
        XCTAssertEqual(hex(PaletteUI.positive, dark: false), 0x0E7A52)
        XCTAssertEqual(hex(PaletteUI.positive, dark: true), 0x48C294)
        XCTAssertEqual(hex(PaletteUI.attention, dark: false), 0xB4531A)
        XCTAssertEqual(hex(PaletteUI.attention, dark: true), 0xEF9459)
    }

    func testHeroTokensMatchAndroid() {
        XCTAssertEqual(hex(PaletteUI.heroGreen, dark: false), 0xE1EDE4)
        XCTAssertEqual(hex(PaletteUI.heroGreen, dark: true), 0x1B2D24)
        XCTAssertEqual(hex(PaletteUI.heroGreenInk, dark: false), 0x3C6A4B)
        XCTAssertEqual(hex(PaletteUI.heroGreenInk, dark: true), 0x8CC3A2)
        XCTAssertEqual(hex(PaletteUI.heroOrange, dark: false), 0xF6E5D8)
        XCTAssertEqual(hex(PaletteUI.heroOrange, dark: true), 0x33221A)
        XCTAssertEqual(hex(PaletteUI.heroOrangeInk, dark: false), 0x8A4310)
        XCTAssertEqual(hex(PaletteUI.heroOrangeInk, dark: true), 0xE6A878)
        XCTAssertEqual(hex(PaletteUI.heroGrey, dark: false), 0xE8EAE5)
        XCTAssertEqual(hex(PaletteUI.heroGrey, dark: true), 0x25272A)
        XCTAssertEqual(hex(PaletteUI.heroGreyInk, dark: false), 0x5E636A)
        XCTAssertEqual(hex(PaletteUI.heroGreyInk, dark: true), 0xA3A7AD)
    }

    func testCategoryTintPairs() {
        XCTAssertEqual(hex(PaletteUI.teal, dark: false), 0x2A9D8F)
        XCTAssertEqual(hex(PaletteUI.tealBg, dark: true), 0x1B3532)
        XCTAssertEqual(hex(PaletteUI.blue, dark: true), 0x6AA6E0)
        XCTAssertEqual(hex(PaletteUI.blueBg, dark: false), 0xDCE8F4)
        XCTAssertEqual(hex(PaletteUI.pink, dark: false), 0xD45D84)
        XCTAssertEqual(hex(PaletteUI.pinkBg, dark: true), 0x3A1F2B)
        XCTAssertEqual(hex(PaletteUI.sage, dark: false), 0x7FA88A)
        XCTAssertEqual(hex(PaletteUI.sageBg, dark: false), 0xE1EAD9)
    }

    func testGreenAndOrangeAreTheStateColours() {
        XCTAssertEqual(Palette.green, Palette.positive)
        XCTAssertEqual(Palette.orange, Palette.attention)
    }

    func testFontLoadsAndFollowsWeights() {
        KassaFont.registerIfNeeded()
        XCTAssertTrue(KassaFont.available)
        XCTAssertEqual(KassaFont.name(.bold), "HankenGrotesk-Bold")
        XCTAssertEqual(KassaFont.name(.semibold), "HankenGrotesk-SemiBold")
        XCTAssertEqual(KassaFont.name(.medium), "HankenGrotesk-Medium")
        XCTAssertEqual(KassaFont.name(.regular), "HankenGrotesk-Regular")
        let font: UIFont = KassaFont.ui(15, .bold)
        XCTAssertEqual(font.fontName, "HankenGrotesk-Bold")
        _ = Font.kassa(15, .semibold, relativeTo: .body)
        _ = Font.kassaFixed(56, .bold)
    }

    func testTypeScaleIsAndroids() {
        XCTAssertEqual(KassaStyle.hero.size, 56)
        XCTAssertEqual(KassaStyle.hero.tracking, -2)
        XCTAssertTrue(KassaStyle.hero.tabular)
        XCTAssertEqual(KassaStyle.title.size, 30)
        XCTAssertEqual(KassaStyle.title.tracking, -0.6, accuracy: 0.0001)
        XCTAssertEqual(KassaStyle.section.size, 18)
        XCTAssertEqual(KassaStyle.body.size, 15)
        XCTAssertEqual(KassaStyle.small.size, 13)
        XCTAssertEqual(KassaStyle.tiny.size, 12)
        XCTAssertEqual(KassaStyle.label.size, 11)
        XCTAssertEqual(KassaStyle.label.tracking, 0.6, accuracy: 0.0001)
        XCTAssertTrue(KassaStyle.label.uppercase)
        XCTAssertEqual(KassaStyle.stat.size, 20)
    }

    func testTabBarHasTheFiveTabsInOrder() {
        let names: [String] = AppShell.barItems.map { $0.identifier }
        XCTAssertEqual(names, ["tab.overview", "tab.activity", "tab.insights", "tab.accounts", "tab.settings"])
    }
}
