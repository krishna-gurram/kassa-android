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

/// One of Overview's top categories as a tile: a stripe in its colour, its icon, name and amount on a pale wash of the colour. It is as
/// wide as its row gives it (`WeightedRow`) and at least 48 pt tall. Opens the category when [onTap] is given.
struct CategoryTile: View {
    let item: TopCategoryData
    let onTap: (() -> Void)?

    private var tint: Color { CategoryTint.color(item) }

    private var choice: CategoryChoiceData {
        return CategoryChoiceData(
            id: item.id, name: CategoryTint.name(item), parentName: "", iconKind: item.iconKind, iconValue: item.iconValue,
            color: item.color, hasSubcategories: false
        )
    }

    private var label: some View {
        HStack(spacing: 0) {
            Rectangle().fill(tint).frame(width: 3)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    CategoryIconView(choice: choice, size: 28)
                    Text(CategoryTint.name(item))
                        .font(Font.kassa(12, .medium, relativeTo: .caption))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Text(item.amountText)
                    .font(Font.kassa(15, .semibold, relativeTo: .subheadline))
                    .monospacedDigit()
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .frame(minHeight: 48)
        .background(tint.opacity(0.18))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
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

/// The top categories as tiles in one row, each as wide as its weight (`TopCategoryData.tileWeight`, :domain's `OverviewTiles`: its share
/// of the three, with a floor so a small one still holds its icon, equal while amounts are hidden).
struct CategoryTiles: View {
    let items: [TopCategoryData]
    let onTap: ((TopCategoryData) -> Void)?

    private func tap(for item: TopCategoryData) -> (() -> Void)? {
        guard let handler = onTap else { return nil }
        return { handler(item) }
    }

    var body: some View {
        WeightedRow(weights: items.map { Int($0.tileWeight) }, spacing: 6) {
            ForEach(items, id: \.id) { item in
                CategoryTile(item: item, onTap: tap(for: item))
            }
        }
    }
}

/// A row that gives each child a width in proportion to its weight (after the gaps) and the height of the tallest child.
struct WeightedRow: Layout {
    let weights: [Int]
    let spacing: CGFloat

    /// The width of each of [count] children in [total] points; a child without a weight counts as 1000.
    static func widths(weights: [Int], count: Int, total: CGFloat, spacing: CGFloat) -> [CGFloat] {
        if count == 0 { return [] }
        let parts: [CGFloat] = (0..<count).map { index in CGFloat(index < weights.count ? max(weights[index], 1) : 1000) }
        let sum: CGFloat = parts.reduce(0, +)
        let room: CGFloat = max(total - spacing * CGFloat(count - 1), 0)
        return parts.map { room * $0 / sum }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let total: CGFloat = proposal.width ?? 320
        let widths: [CGFloat] = WeightedRow.widths(weights: weights, count: subviews.count, total: total, spacing: spacing)
        var height: CGFloat = 0
        for (index, view) in subviews.enumerated() {
            height = max(height, view.sizeThatFits(ProposedViewSize(width: widths[index], height: nil)).height)
        }
        return CGSize(width: total, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let widths: [CGFloat] = WeightedRow.widths(weights: weights, count: subviews.count, total: bounds.width, spacing: spacing)
        var x: CGFloat = bounds.minX
        for (index, view) in subviews.enumerated() {
            view.place(
                at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading,
                proposal: ProposedViewSize(width: widths[index], height: bounds.height)
            )
            x += widths[index] + spacing
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
            Text(title)
                .font(Font.kassa(18, .semibold, relativeTo: .headline))
                .foregroundColor(selected ? Palette.ink : inkColor)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.bottom, 4)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(selected ? Palette.ink : Color.clear)
                        .frame(height: 2)
                }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .layoutPriority(2)
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
