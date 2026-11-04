import SwiftUI
import KassaShared

/// The text and icon of a category tile: the category picked, or [defaultText] while none is (the loan's own default).
struct CategoryChoiceTile: View {
    let label: String
    let choice: CategoryChoiceData?
    let defaultText: String
    let action: () -> Void

    private var value: String {
        guard let picked = choice else { return defaultText }
        if picked.parentName.isEmpty { return picked.name }
        return picked.parentName + " \u{203A} " + picked.name
    }

    var body: some View {
        EntryTile(label: LocalizedStringKey(label), value: value, selected: false, action: action) {
            icon
        }
    }

    @ViewBuilder
    private var icon: some View {
        if let picked = choice {
            CategoryIconView(choice: picked, size: 32)
        } else {
            EmptyTileIcon()
        }
    }
}

/// The shared category chooser of the loan sheets: search, the expense categories as an icon grid (A to Z), a category with
/// subcategories opens them ("No subcategory" first), and an optional first choice that means "the default" (id 0). One picker for
/// the interest, principal and escrow categories, as Android's `CategoryChoiceRow`.
struct CategoryChooser: View {
    let ledger: LedgerView?
    let title: String
    let selectedId: Int64
    /// The text of the first choice (the default, which picks id 0); empty for none.
    let defaultText: String
    let onPick: (Int64) -> Void
    let onBack: () -> Void

    @State private var query = ""
    @State private var top: CategoryChoiceData?

    var body: some View {
        VStack(spacing: 8) {
            SheetBar(title: title, onBack: backAction)
            if let parent = top {
                subcategories(parent)
            } else {
                categories
            }
        }
        .padding(.horizontal, 16)
    }

    private func backAction() {
        if top != nil {
            top = nil
        } else {
            onBack()
        }
    }

    private var categories: some View {
        let choices: [CategoryChoiceData] = ledger?.categoryChoices(kind: "EXPENSE", query: query) ?? []
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        return VStack(spacing: 8) {
            PickerSearch(placeholder: "entry.searchCategories", text: $query)
            ScrollView {
                VStack(spacing: 6) {
                    if !defaultText.isEmpty && !searching {
                        defaultTile
                    }
                    if choices.isEmpty {
                        Text(String(format: String(localized: "entry.noCategoryMatches"), query))
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    TileGrid(items: choices, columns: searching ? 2 : 3) { choice in
                        tile(choice)
                    }
                }
            }
        }
    }

    private var defaultTile: some View {
        return PickTile(selected: selectedId == 0, label: defaultText, action: { onPick(0) }) {
            Text(defaultText)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Palette.ink)
                .lineLimit(2)
                .multilineTextAlignment(.center)
        }
    }

    private func tile(_ choice: CategoryChoiceData) -> some View {
        let label = choice.parentName.isEmpty ? choice.name : choice.parentName + " \u{203A} " + choice.name
        return PickTile(selected: selectedId == choice.id, label: label, action: { choose(choice) }) {
            VStack(spacing: 4) {
                CategoryIconView(choice: choice, size: 34)
                Text(choice.name)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
            }
        }
    }

    private func choose(_ choice: CategoryChoiceData) {
        if choice.hasSubcategories && choice.parentName.isEmpty && query.isEmpty {
            top = choice
        } else {
            onPick(choice.id)
        }
    }

    private func subcategories(_ parent: CategoryChoiceData) -> some View {
        let subs: [CategoryChoiceData] = ledger?.subcategoryChoices(topId: parent.id) ?? []
        return ScrollView {
            VStack(spacing: 6) {
                PickTile(
                    selected: selectedId == parent.id, label: String(localized: "entry.noSubcategory"),
                    action: { onPick(parent.id) }
                ) {
                    Text("entry.noSubcategory")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Palette.ink2)
                }
                TileGrid(items: subs, columns: 2) { sub in
                    PickTile(selected: selectedId == sub.id, label: sub.name, action: { onPick(sub.id) }) {
                        Text(sub.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Palette.ink)
                            .lineLimit(2)
                    }
                }
            }
        }
    }
}

/// A switch row with a title and a line under it (the count-the-whole-payment-as-spending switch).
struct SwitchLine: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body)
                if !detail.isEmpty {
                    Text(detail).font(.footnote).foregroundColor(Palette.ink2)
                }
            }
        }
        .frame(minHeight: 48)
    }
}

/// A label on the left and a figure on the right, one line of a sheet's summary.
struct SummaryLine: View {
    let label: String
    let value: String
    var strong: Bool = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(strong ? .subheadline.weight(.semibold) : .subheadline)
                .foregroundColor(strong ? Palette.ink : Palette.ink2)
                .lineLimit(2)
            Spacer(minLength: 8)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(minHeight: 28)
        .accessibilityElement(children: .combine)
    }
}

/// Sentences of the loan sheets that are made from a word the rules give.
extension Wording {
    /// "Interest category" / "Profit category".
    static func loanCostCategory(_ word: String) -> String {
        return String(format: String(localized: "loanPay.costCategory"), word)
    }

    /// "Same as interest (Loan interest)" or "Same as loan (Car)": what an empty Principal category stands for.
    static func loanSameAs(kind: String, name: String) -> String {
        if kind == "LOAN" { return String(format: String(localized: "loanPay.sameAsLoan"), name) }
        return String(format: String(localized: "loanPay.sameAsInterest"), name)
    }
}
