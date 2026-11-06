import SwiftUI
import KassaShared

/// "+ New" at the end of a category's subcategories in a picker: a tile that turns into a name field (the problem under it, an Add
/// button) and adds a subcategory under [parentId] without leaving the picker. The name is checked by :domain (`CategoryRules`)
/// before it is sent; the new subcategory is handed to [onAdded] so the picker can pick it.
struct NewSubcategoryTile: View {
    let ledger: LedgerView?
    let parentId: Int64
    let parentName: String
    let onAdd: (Int64, String, @escaping (WriteOutcome) -> Void) -> Void
    let onAdded: (Int64) -> Void

    @State private var open = false
    @State private var text = ""
    @State private var problem: String?
    @State private var busy = false

    var body: some View {
        if open {
            field
        } else {
            PickTile(selected: false, label: String(localized: "cats.plusNew"), action: { open = true }) {
                Text("cats.plusNew")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    private var textBinding: Binding<String> {
        return Binding<String>(
            get: { text },
            set: { raw in text = CategoryIconBridge.shared.typedName(raw: raw); problem = nil }
        )
    }

    private var field: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                TextField(String(format: String(localized: "cats.newUnder"), parentName), text: textBinding)
                    .textInputAutocapitalization(.sentences)
                    .submitLabel(.done)
                    .onSubmit { add() }
                    .padding(.horizontal, 12)
                    .frame(minHeight: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
                Button("cats.add") { add() }
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
                    .disabled(busy)
                Button("cats.cancel") { text = ""; problem = nil; open = false }
                    .foregroundColor(Palette.ink2)
                    .frame(minHeight: 48)
            }
            if let message = problem {
                Text(message).font(.footnote).foregroundColor(Palette.orange).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func add() {
        if busy { return }
        let found: String = ledger?.subcategoryProblem(parentId: parentId, name: text) ?? ""
        if !found.isEmpty {
            problem = found
            return
        }
        busy = true
        onAdd(parentId, text) { outcome in
            busy = false
            switch outcome {
            case .saved(let id):
                text = ""
                problem = nil
                open = false
                onAdded(id)
            case .refused(_, let message):
                problem = message
            case .failed(let message):
                problem = message
            }
        }
    }
}
