import SwiftUI
import KassaShared

/// Pieces of the Overview and Accounts design (batch D1): an amount with lighter cents, the stacked category bar, round-badge rows
/// and the two-column account cells. Figures come from the shared rules; this only lays them out.

/// A money text with its last two digits (the minor units) a little lighter. Only styling of text that is already formatted: while
/// amounts are hidden the mask is drawn as it is, never split.
struct AmountText: View {
    let text: String
    let hidden: Bool
    var size: CGFloat = 52
    var color: Color = Palette.ink
    var centsColor: Color = Palette.ink3

    /// The text before and after the decimal mark ("$1,815" and ".99"); the second part is empty when the text has no
    /// two-digit tail (a grouped number such as 1,234 has three trailing digits, so it is never cut).
    static func split(_ text: String) -> (String, String) {
        let chars: [Character] = Array(text)
        var index: Int = chars.count - 1
        var digits: Int = 0
        while index >= 0 && chars[index].isNumber {
            digits += 1
            index -= 1
        }
        if digits != 2 || index < 1 || !chars[index - 1].isNumber {
            return (text, "")
        }
        let head: String = String(chars[0..<index])
        let tail: String = String(chars[index..<chars.count])
        return (head, tail)
    }

    private var combined: Text {
        if hidden {
            return Text(text).foregroundColor(color)
        }
        let parts: (String, String) = AmountText.split(text)
        if parts.1.isEmpty {
            return Text(text).foregroundColor(color)
        }
        return Text(parts.0).foregroundColor(color) + Text(parts.1).foregroundColor(centsColor)
    }

    var body: some View {
        combined
            .scaledNumeral(size)
            .tracking(-1.5)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }
}

/// The stacked category bar of the hero: one rounded segment per category in its colour.
struct CategoryBar: View {
    let colors: [Color]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<colors.count, id: \.self) { index in
                Capsule().fill(colors[index]).frame(height: 10)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The colour a top category draws in (its own, or a neutral grey without a category).
enum CategoryTint {
    static func color(_ item: TopCategoryData) -> Color {
        if (UInt32(bitPattern: item.color) & 0xFFFFFF) == 0 { return Palette.ink3 }
        return Color(argb: item.color)
    }

    static func name(_ item: TopCategoryData) -> String {
        return item.name.isEmpty ? String(localized: "entry.uncategorized") : item.name
    }
}

/// One legend entry under the bar: a dot and the category name, at least 48 pt tall. Opens the category when [onTap] is given.
struct LegendItem: View {
    let item: TopCategoryData
    let onTap: (() -> Void)?

    private var label: some View {
        HStack(spacing: 6) {
            Circle().fill(CategoryTint.color(item)).frame(width: 10, height: 10)
            Text(CategoryTint.name(item))
                .font(Font.kassa(15, .medium, relativeTo: .subheadline))
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    var body: some View {
        if let tap = onTap {
            Button(action: tap) { label }
                .buttonStyle(.plain)
        } else {
            label
        }
    }
}

/// The notification pill of Overview: orange tint, a bell, "N payments waiting" and a chevron.
struct WaitingPill: View {
    let count: Int
    let onOpen: () -> Void

    private var message: String {
        if count == 1 { return String(localized: "d1.waiting.one") }
        return String(format: String(localized: "d1.waiting.other"), count)
    }

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                Image(systemName: "bell")
                    .font(.system(size: 18, weight: .regular))
                Text(message)
                    .font(Font.kassa(17, .semibold, relativeTo: .body))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Palette.ink3)
            }
            .foregroundColor(Palette.heroOrangeInk)
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(Capsule().fill(Palette.heroOrange))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("noticesBar")
    }
}

/// A tab of the hero ("Spending", "Income"): bold ink with a 2 pt underline when selected, the hero's own ink otherwise.
struct HeroTab: View {
    let title: LocalizedStringKey
    let selected: Bool
    let inkColor: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Text(title)
                    .font(Font.kassa(18, .semibold, relativeTo: .headline))
                    .foregroundColor(selected ? Palette.ink : inkColor)
                    .lineLimit(1)
                Rectangle()
                    .fill(selected ? Palette.ink : Color.clear)
                    .frame(height: 2)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// An account on Overview: a hairline on top, the name, the figure and what is due, a chevron on the right.
struct AccountCell: View {
    let pin: PinRowData
    let hidden: Bool
    let onTap: () -> Void

    private var title: String {
        return pin.isPeople ? String(localized: "accounts.group.people") : pin.name
    }

    private var dueLine: String? {
        if pin.dueKind.name == "Overdue" {
            return String(format: String(localized: "overview.cardOverdue"), pin.dueAmountText)
        }
        if pin.dueKind.name == "Due" {
            let when: String = KassaDates.shortDate(epochDay: Int(pin.dueDay))
            return String(format: String(localized: "overview.cardDue"), pin.dueAmountText, when)
        }
        return nil
    }

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Font.kassa(17, .medium, relativeTo: .body))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                    Text(pin.amountText)
                        .font(Font.kassa(20, .semibold, relativeTo: .title3).monospacedDigit())
                        .foregroundColor(Palette.figure(pin.amountTone))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if let line = dueLine {
                        Text(line)
                            .font(Font.kassa(14, .semibold, relativeTo: .footnote))
                            .foregroundColor(hidden ? Palette.ink2 : Palette.orange)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palette.ink3)
                    .padding(.top, 14)
            }
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 80, alignment: .topLeading)
            .overlay(alignment: .top) { Hairline() }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}
