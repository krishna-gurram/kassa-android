import SwiftUI
import KassaShared

/// The number pad: digits, the phone's decimal mark, and delete. Keys are plain strings ("0" to "9", ".", "del") for the shared
/// `AmountPad` rules; the point key only *shows* [decimalMark]. At least 52 pt tall, so every key is an easy target.
struct NumberPad: View {
    let decimalMark: String
    let onKey: (String) -> Void

    private let rows: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], [".", "0", "del"]]

    var body: some View {
        VStack(spacing: 8) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 8) {
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
                .font(.title3)
        } else if key == "." {
            Text(decimalMark)
                .font(.title2.weight(.medium))
        } else {
            Text(key)
                .font(.title2.weight(.medium))
                .monospacedDigit()
        }
    }

    private func keyButton(_ key: String) -> some View {
        Button(action: { onKey(key) }) {
            keyLabel(key)
                .foregroundColor(Palette.ink)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(key == "del" ? Text("a11y.deleteDigit") : (key == "." ? Text("a11y.decimalMark") : Text(key)))
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
        VStack(spacing: 6) {
            ForEach(Array(rows.enumerated()), id: \.offset) { entry in
                HStack(spacing: 6) {
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

/// One choice in a picker: outlined and ticked when picked, at least 66 pt tall.
struct PickTile<Content: View>: View {
    let selected: Bool
    let label: String
    let action: () -> Void
    let content: Content

    init(selected: Bool, label: String, action: @escaping () -> Void, @ViewBuilder content: () -> Content) {
        self.selected = selected
        self.label = label
        self.action = action
        self.content = content()
    }

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                content
                    .frame(maxWidth: .infinity, minHeight: 66)
                    .padding(6)
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(Palette.ink)
                        .padding(4)
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(selected ? Palette.ink : Color.clear, lineWidth: 2)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
    }
}

/// A quiet chip: a kind filter or a quick answer. At least 44 pt tall.
struct FilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .foregroundColor(selected ? Palette.ground : Palette.ink)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(Capsule().fill(selected ? Palette.ink : Palette.chip))
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
                .foregroundColor(Palette.ink2)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .submitLabel(.done)
            if !text.isEmpty {
                Button(action: { text = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(Palette.ink2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(Text("a11y.clearSearch"))
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
    }
}

/// A small uppercase label over a group of tiles.
struct GroupLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
    }
}

extension CategoryChoiceData: Identifiable {}
extension AccountChoiceData: Identifiable {}
