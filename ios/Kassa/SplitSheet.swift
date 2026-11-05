import SwiftUI
import KassaShared

/// "Split with <name>": the expenses of the last 60 days that can be split, as Activity's rows. Picking one opens it on the entry page
/// with the person in its split. Only lays out what `LedgerView.splitCandidates` gave.
struct SplitPickerSheet: View {
    @ObservedObject var model: AppModel
    let request: SplitSheetRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(String(format: String(localized: "personPage.splitWith"), request.personName))
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button("split.close") { model.closeSplitSheet() }
                    .frame(minHeight: 48)
            }
            Text("personPage.pickExpense")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
            if request.rows.isEmpty {
                Text("personPage.noExpenses60")
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 8)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(request.rows.enumerated()), id: \.offset) { item in
                        EntryRowView(
                            entry: item.element.entry, showDivider: true,
                            onOpen: { row in model.pickSplit(request, entryId: row.id) }
                        )
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Palette.ground.ignoresSafeArea())
    }
}
