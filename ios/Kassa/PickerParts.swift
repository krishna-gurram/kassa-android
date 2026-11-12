import SwiftUI
import KassaShared

/// The number pad: digits, the phone's decimal mark, and delete. Keys are plain strings ("0" to "9", ".", "del") for the shared
/// `AmountPad` rules; the point key only *shows* [decimalMark]. Plain numerals on the ground (no key caps), each key at least 56 pt tall.
struct NumberPad: View {
    let decimalMark: String
    let onKey: (String) -> Void

    private let rows: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], [".", "0", "del"]]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(row, id: \.self) { key in
                        keyButton(key)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func keyLabel(_ key: String) -> some View {
        if key == "del" {
            Image(systemName: "delete.left")
                .font(.system(size: 22, weight: .regular))
        } else if key == "." {
            Text(decimalMark)
                .font(Font.kassa(30, .medium, relativeTo: .title))
        } else {
            Text(key)
                .font(Font.kassa(30, .medium, relativeTo: .title).monospacedDigit())
        }
    }

    private func keyButton(_ key: String) -> some View {
        Button(action: { onKey(key) }) {
            keyLabel(key)
                .foregroundColor(Palette.ink)
                .frame(maxWidth: .infinity, minHeight: 56)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(key == "del" ? Text("a11y.deleteDigit") : (key == "." ? Text("a11y.decimalMark") : Text(key)))
    }
}

private struct FlatScrollKey: EnvironmentKey {
    static let defaultValue: Bool = false
}

extension EnvironmentValues {
    /// Set by the screenshot tests: `ImageRenderer` can't draw a ScrollView's content, so [KassaScroll] lays its content out flat.
    var flatScroll: Bool {
        get { return self[FlatScrollKey.self] }
        set { self[FlatScrollKey.self] = newValue }
    }
}

/// A ScrollView (vertical unless [axes] says otherwise) that the screenshot tests can flatten (see `flatScroll`).
struct KassaScroll<Content: View>: View {
    @Environment(\.flatScroll) private var flat
    let axes: Axis.Set
    let content: Content

    init(_ axes: Axis.Set = .vertical, @ViewBuilder content: () -> Content) {
        self.axes = axes
        self.content = content()
    }

    var body: some View {
        if flat && axes == .vertical {
            content.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else if flat {
            content.frame(maxWidth: .infinity, alignment: .leading)
        } else {
            ScrollView(axes, showsIndicators: false) {
                content
            }
        }
    }
}

/// A grid made of plain stacks (not lazy ones, so a screenshot can draw it): [columns] tiles a row, the last row padded.
struct TileGrid<Item: Identifiable, Tile: View>: View {
    let items: [Item]
    let columns: Int
    let tile: (Item) -> Tile

    private var rows: [[Item]] {
        var result: [[Item]] = []
        var start = 0
        while start < items.count {
            let end = min(start + columns, items.count)
            result.append(Array(items[start..<end]))
            start = end
        }
        return result
    }

    var body: some View {
        VStack(spacing: 10) {
            ForEach(Array(rows.enumerated()), id: \.offset) { entry in
                HStack(spacing: 10) {
                    ForEach(entry.element) { item in
                        tile(item)
                    }
                    ForEach(0..<(columns - entry.element.count), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity, minHeight: 1)
                    }
                }
            }
        }
    }
}

/// One choice in a picker: a raised tile with a hairline border, 16 pt radius; the picked one has a 2 pt ink border and an ink tick
/// badge in its corner. At least [minHeight] tall.
struct PickTile<Content: View>: View {
    let selected: Bool
    let label: String
    var minHeight: CGFloat = 66
    let action: () -> Void
    let content: Content

    init(selected: Bool, label: String, minHeight: CGFloat = 66, action: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        self.selected = selected
        self.label = label
        self.minHeight = minHeight
        self.action = action
        self.content = content()
    }

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                content
                    .frame(maxWidth: .infinity, minHeight: minHeight)
                    .padding(6)
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Palette.onInk)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(Palette.ink))
                        .padding(6)
                }
            }
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.raised))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? Palette.ink : Palette.line, lineWidth: selected ? 2 : 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
    }
}

/// A tile whose content is a round icon over a name (categories and accounts in the pickers): 88 pt tall.
struct IconPickTile<Icon: View>: View {
    let selected: Bool
    let label: String
    let name: String
    var detail: String = ""
    let action: () -> Void
    let icon: Icon

    init(selected: Bool, label: String, name: String, detail: String = "", action: @escaping () -> Void, @ViewBuilder icon: () -> Icon) {
        self.selected = selected
        self.label = label
        self.name = name
        self.detail = detail
        self.action = action
        self.icon = icon()
    }

    var body: some View {
        PickTile(selected: selected, label: label, minHeight: 88, action: action) {
            VStack(spacing: 6) {
                icon
                Text(name)
                    .font(Font.kassa(15, .medium, relativeTo: .subheadline))
                    .foregroundColor(Palette.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .multilineTextAlignment(.center)
                if !detail.isEmpty {
                    Text(detail)
                        .font(Font.kassa(12, .regular, relativeTo: .caption))
                        .foregroundColor(Palette.ink2)
                }
            }
        }
    }
}

/// One row of a plain choice list on the ground, hairline under it: a title, a tick when picked or a trailing symbol. 52 pt tall.
struct ChoiceRow: View {
    let title: String
    let selected: Bool
    var muted: Bool = false
    var symbol: String = ""
    let action: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: action) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(Font.kassa(17, .regular, relativeTo: .body))
                        .foregroundColor(muted ? Palette.ink2 : Palette.ink)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(Palette.ink)
                    } else if !symbol.isEmpty {
                        Image(systemName: symbol)
                            .font(.system(size: 16, weight: .regular))
                            .foregroundColor(Palette.ink)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
            Hairline()
        }
    }
}

/// A quiet chip: a kind filter or a quick answer. The active one is an ink pill; the tap target is 48 pt tall around a 40 pt pill.
struct FilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Font.kassa(15, .semibold, relativeTo: .subheadline))
                .lineLimit(1)
                .foregroundColor(selected ? Palette.onInk : Palette.ink2)
                .padding(.horizontal, 16)
                .frame(minHeight: 40)
                .background(Capsule().fill(selected ? Palette.ink : Palette.chip))
                .frame(minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
    }
}

/// The search field of the pickers.
struct PickerSearch: View {
    let placeholder: LocalizedStringKey
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 18, weight: .regular))
                .foregroundColor(Palette.ink3)
            TextField(placeholder, text: $text)
                .font(Font.kassa(16, .regular))
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .submitLabel(.done)
            if !text.isEmpty {
                Button(action: { text = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(Palette.ink2)
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityLabel(Text("a11y.clearSearch"))
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .background(Capsule().fill(Palette.chip))
    }
}

/// A small uppercase label over a group of tiles.
struct GroupLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .kassa(.label)
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 10)
    }
}

extension CategoryChoiceData: Identifiable {}
extension AccountChoiceData: Identifiable {}
extension StarterData: Identifiable {
    public var id: String { name }
}
