import SwiftUI
import KassaShared

/// The shared pieces of the design (the same jobs as Android's `ui/common`): hero block, segmented pill, section label, round badge,
/// small tag, the Add button. Screens use these instead of building their own.

/// The tonal block at the top of a screen: a pale tint with a 28 pt radius. Grey while amounts are hidden (as Android's `TonalHero`),
/// so a tint never gives a figure away.
struct TonalHero<Content: View>: View {
    let tone: Tone
    let hidden: Bool
    let content: Content

    init(tone: Tone, hidden: Bool = false, @ViewBuilder content: () -> Content) {
        self.tone = tone
        self.hidden = hidden
        self.content = content()
    }

    private var tint: Color {
        return hidden ? Palette.heroGrey : Palette.heroTint(tone)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .foregroundColor(Palette.ink)
        .padding(.top, 18)
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(tint))
    }
}

/// The small label at the top of a hero ("Spending"), in the hero's own ink.
struct HeroLabel: View {
    let text: LocalizedStringKey
    let tone: Tone
    var hidden: Bool = false

    var body: some View {
        Text(text)
            .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
            .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(tone))
    }
}

/// Segmented choice as a pill: chip background, the active option an ink pill, 44 pt cells.
struct SegmentedPill: View {
    let options: [LocalizedStringKey]
    @Binding var selection: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<options.count, id: \.self) { index in
                cell(index)
            }
        }
        .padding(4)
        .background(Capsule().fill(Palette.chip))
    }

    private func cell(_ index: Int) -> some View {
        let on: Bool = index == selection
        return Button(action: { selection = index }) {
            Text(options[index])
                .font(Font.kassa(15, .semibold))
                .foregroundColor(on ? Palette.onInk : Palette.ink2)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Capsule().fill(on ? Palette.ink : Color.clear))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// An uppercase section label over a list, with an optional link on the right ("See all"). The link is at least 48 pt tall.
struct SectionLabel: View {
    let title: LocalizedStringKey
    var link: LocalizedStringKey? = nil
    var onLink: () -> Void = {}

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .kassa(.label)
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if let text = link {
                Button(action: onLink) {
                    Text(text)
                        .font(Font.kassa(13, .semibold, relativeTo: .footnote))
                        .foregroundColor(Palette.ink)
                        .frame(minHeight: 48)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, 8)
    }
}

/// A round 40 pt icon badge: the icon in its colour on that colour's pale background.
struct Badge: View {
    let symbol: String
    var tint: TintPair = Palette.neutral

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 18, weight: .semibold))
            .foregroundColor(tint.fg)
            .frame(width: 40, height: 40)
            .background(Circle().fill(tint.bg))
            .accessibilityHidden(true)
    }
}

/// A small outlined tag after a name ("principal", "pending").
struct Tag: View {
    let text: LocalizedStringKey

    init(_ text: LocalizedStringKey) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(Font.kassa(12, .medium, relativeTo: .caption))
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 1)
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Palette.line, lineWidth: 1))
    }
}

/// The Add button: an ink rounded square, 58 pt, 18 pt radius, a plus in onInk.
struct AddFab: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.system(size: 26, weight: .semibold))
                .foregroundColor(Palette.onInk)
                .frame(width: 58, height: 58)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Palette.ink))
                .shadow(color: Color.black.opacity(0.2), radius: 9, x: 0, y: 6)
        }
        .accessibilityLabel(Text("a11y.add"))
        .accessibilityIdentifier("addButton")
    }
}

/// One tab of the bottom bar.
struct TabBarItem: Identifiable {
    let tab: ShellTab
    let title: LocalizedStringKey
    let symbol: String
    let identifier: String

    var id: String { identifier }
}

/// The bottom tab bar: ground with a hairline on top; the active tab is its icon on a chip pill with an ink bold label, the others ink2.
/// Every cell is at least 48 pt and reads as a selected tab button to VoiceOver.
struct KassaTabBar: View {
    let items: [TabBarItem]
    let selection: ShellTab
    let onSelect: (ShellTab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Hairline()
            HStack(spacing: 0) {
                ForEach(items) { item in
                    cell(item)
                }
            }
            .padding(.horizontal, 6)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .background(Palette.ground.ignoresSafeArea(edges: .bottom))
    }

    private func cell(_ item: TabBarItem) -> some View {
        let on: Bool = item.tab == selection
        return Button(action: { onSelect(item.tab) }) {
            VStack(spacing: 2) {
                Image(systemName: item.symbol)
                    .font(.system(size: 20, weight: .regular))
                    .frame(width: 56, height: 30)
                    .background(Capsule().fill(on ? Palette.chip : Color.clear))
                Text(item.title)
                    .font(Font.kassa(11, on ? .bold : .medium, relativeTo: .caption2))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .foregroundColor(on ? Palette.ink : Palette.ink2)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(item.identifier)
    }
}

/// The tab rail of the regular-width layouts (iPad, from 600 pt): the same tabs as `KassaTabBar`, stacked at the leading edge, with
/// Add at its foot (there is no floating Add button beside it). Cells are at least 48 pt; the active tab is its icon on a chip pill with
/// an ink bold label. Identifiers are the bar's (`tab.<name>`, `addButton`).
struct KassaTabRail: View {
    let items: [TabBarItem]
    let selection: ShellTab
    let onSelect: (ShellTab) -> Void
    let onAdd: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 6) {
                ForEach(items) { item in
                    cell(item)
                }
                Spacer(minLength: 12)
                AddFab(action: onAdd)
                    .padding(.bottom, 16)
            }
            .padding(.top, 12)
            .padding(.horizontal, 6)
            .frame(minWidth: LayoutMode.railWidth)
            Rectangle()
                .fill(Palette.line)
                .frame(width: 1)
                .ignoresSafeArea(edges: .vertical)
        }
        .background(Palette.ground.ignoresSafeArea(edges: .vertical))
    }

    private func cell(_ item: TabBarItem) -> some View {
        let on: Bool = item.tab == selection
        return Button(action: { onSelect(item.tab) }) {
            VStack(spacing: 2) {
                Image(systemName: item.symbol)
                    .font(.system(size: 20, weight: .regular))
                    .frame(width: 56, height: 30)
                    .background(Capsule().fill(on ? Palette.chip : Color.clear))
                Text(item.title)
                    .font(Font.kassa(11, on ? .bold : .medium, relativeTo: .caption2))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .foregroundColor(on ? Palette.ink : Palette.ink2)
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(item.identifier)
    }
}
