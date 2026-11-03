import SwiftUI
import KassaShared

/// Opens the entry page for an `EntryPageRequest` (new, or editing one entry) over the whole screen, and gives it its draft model.
struct EntryPageHost: View {
    @ObservedObject var model: AppModel
    let request: EntryPageRequest
    let ledger: LedgerView
    /// The scene's kept copy of an unfinished new entry (empty text when none); the page writes to it as it changes.
    @Binding var keptDraft: String

    @StateObject private var draft: EntryDraftModel

    init(model: AppModel, request: EntryPageRequest, ledger: LedgerView, keptDraft: Binding<String>) {
        self.model = model
        self.request = request
        self.ledger = ledger
        _keptDraft = keptDraft
        let made = EntryDraftModel(
            view: ledger, editing: request.editing, draft: request.draft, today: KassaDates.todayEpochDay(),
            zone: TimeZone.current.identifier
        )
        _draft = StateObject(wrappedValue: made)
    }

    var body: some View {
        AddEntryScreen(
            draft: draft,
            amountText: request.amountText,
            onSave: { input, finished in
                if input.id == 0 {
                    model.addEntry(input, done: finished)
                } else {
                    model.updateEntry(input, done: finished)
                }
            },
            onDelete: { id, finished in model.deleteEntry(id: id, done: finished) },
            onClose: {
                keptDraft = ""
                model.closeEntryPage()
            }
        )
        .onChange(of: draft.encoded) { text in
            // Only an unfinished new entry is kept, and only while there is something in it.
            if request.editing == nil { keptDraft = draft.isDirty ? text : "" }
        }
    }
}

/// The add and edit page: the kind switch, the amount, the tiles, the date row and the note on top, and one panel at the bottom
/// (the number pad, the category or account picker, the calendar). Picking moves on to the next empty input, so a new entry is a few
/// taps. The one ink button is under the pad. There is no eye here: typed amounts are never masked. Leaving with unsaved changes asks
/// first; deleting asks first.
struct AddEntryScreen: View {
    @ObservedObject var draft: EntryDraftModel
    let amountText: String
    let onSave: (EntryInput, @escaping (WriteOutcome) -> Void) -> Void
    let onDelete: (Int64, @escaping (WriteOutcome) -> Void) -> Void
    let onClose: () -> Void

    @FocusState private var noteFocused: Bool
    @State private var confirmDiscard = false
    @State private var confirmDelete = false
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            AddEntryForm(draft: draft, noteFocused: $noteFocused)
                .padding(.horizontal, 16)
            if !noteFocused {
                panelView
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
                    .padding(.bottom, 8)
                    .frame(maxHeight: .infinity)
            } else {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: 640)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text("entry.discard.title"), isPresented: $confirmDiscard) {
            Button("entry.discard.keep", role: .cancel) {}
            Button("entry.discard.confirm", role: .destructive) { onClose() }
        } message: {
            Text(draft.editing == nil ? LocalizedStringKey("entry.discard.newBody") : LocalizedStringKey("entry.discard.editBody"))
        }
        .alert(Text("entry.delete.title"), isPresented: $confirmDelete) {
            Button("entry.delete.keep", role: .cancel) {}
            Button("entry.delete.confirm", role: .destructive) { delete() }
        } message: {
            Text(deleteBody)
        }
    }

    // MARK: Top bar

    private var title: LocalizedStringKey {
        guard draft.editing != nil else { return "entry.title.new" }
        if draft.kind == "INCOME" { return "entry.title.editIncome" }
        if draft.kind == "TRANSFER" { return "entry.title.editTransfer" }
        return "entry.title.editExpense"
    }

    private var deleteBody: String {
        let name = draft.editing?.note ?? ""
        let shown = name.isEmpty ? String(localized: "entry.thisEntry") : name
        return String(format: String(localized: "entry.delete.body"), shown, amountText)
    }

    private var topBar: some View {
        HStack(spacing: 4) {
            Button(action: leave) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(title)
                .font(.headline)
                .lineLimit(1)
            Spacer(minLength: 8)
            if draft.editing != nil {
                Button("entry.delete") { confirmDelete = true }
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.orange)
                    .frame(minHeight: 44)
                    .padding(.trailing, 8)
            }
        }
        .padding(.horizontal, 8)
    }

    private func leave() {
        if draft.isDirty {
            confirmDiscard = true
        } else {
            onClose()
        }
    }

    // MARK: Saving

    private func save() {
        if busy { return }
        busy = true
        noteFocused = false
        onSave(draft.input()) { outcome in
            busy = false
            switch outcome {
            case .saved:
                onClose()
            case .refused(let field, let message):
                draft.panel = draft.panel(forProblem: field)
                draft.error = message
            case .failed(let message):
                draft.error = message
            }
        }
    }

    private func delete() {
        guard let entry = draft.editing, !busy else { return }
        busy = true
        onDelete(entry.id) { outcome in
            busy = false
            switch outcome {
            case .saved:
                onClose()
            case .refused(_, let message):
                draft.error = message
            case .failed(let message):
                draft.error = message
            }
        }
    }

    // MARK: Panel

    @ViewBuilder
    private var panelView: some View {
        switch draft.panel {
        case .amount:
            AmountPanel(draft: draft, busy: busy, onSave: save)
        case .category:
            CategoryPanel(draft: draft)
        case .subcategory:
            SubcategoryPanel(draft: draft)
        case .account, .to:
            AccountPanel(draft: draft)
        case .date:
            DatePanel(draft: draft)
        }
    }
}

// MARK: - The form

/// The top half of the page: kind, amount, the tiles, the date row, the note, and the problem if there is one.
struct AddEntryForm: View {
    @ObservedObject var draft: EntryDraftModel
    var noteFocused: FocusState<Bool>.Binding

    var body: some View {
        VStack(spacing: 10) {
            kindSwitch
            amountHero
            tiles
            DateRow(draft: draft)
            noteField
            if let problem = draft.error {
                Text(problem)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var kindSwitch: some View {
        Picker(selection: kindBinding, label: Text("entry.kind")) {
            Text("entry.kind.expense").tag("EXPENSE")
            Text("entry.kind.income").tag("INCOME")
            Text("entry.kind.transfer").tag("TRANSFER")
        }
        .pickerStyle(.segmented)
    }

    private var kindBinding: Binding<String> {
        return Binding<String>(get: { draft.kind }, set: { draft.switchKind(to: $0) })
    }

    private var amountHero: some View {
        Button(action: { draft.show(.amount) }) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(draft.symbol)
                    .font(.title2.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                Text(draft.amountShown)
                    .font(.system(size: 44, weight: .bold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(draft.typed.isEmpty ? Palette.ink2 : (draft.kind == "INCOME" ? Palette.green : Palette.ink))
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("entry.amount"))
        .accessibilityValue(Text(draft.symbol + draft.amountShown))
    }

    @ViewBuilder
    private var tiles: some View {
        if draft.kind == "TRANSFER" {
            HStack(spacing: 8) {
                accountTile(label: "entry.from", id: draft.accountId, panel: .account)
                Image(systemName: "arrow.right")
                    .foregroundColor(Palette.ink2)
                accountTile(label: "entry.to", id: draft.toAccountId, panel: .to)
            }
        } else {
            HStack(spacing: 8) {
                categoryTile
                accountTile(label: draft.kind == "INCOME" ? "entry.into" : "entry.paidWith", id: draft.accountId, panel: .account)
            }
        }
    }

    private var categoryTile: some View {
        let choice: CategoryChoiceData? = draft.categoryId > 0 ? draft.view.categoryChoice(id: draft.categoryId) : nil
        let value: String = choice.map { $0.parentName.isEmpty ? $0.name : $0.parentName + " \u{203A} " + $0.name } ?? ""
        let on = draft.panel == .category || draft.panel == .subcategory
        return EntryTile(label: "entry.category", value: value, selected: on, action: { draft.show(.category) }) {
            if let chosen = choice {
                CategoryIconView(choice: chosen, size: 32)
            } else {
                EmptyTileIcon()
            }
        }
    }

    private func accountTile(label: LocalizedStringKey, id: Int64, panel: EntryPanel) -> some View {
        let choice: AccountChoiceData? = id > 0 ? draft.view.accountChoice(id: id) : nil
        let value: String = choice?.name ?? ""
        let on = draft.panel == panel
        return EntryTile(label: label, value: value, selected: on, action: { draft.show(panel) }) {
            if let chosen = choice {
                AccountIconView(type: chosen.type, size: 32)
            } else {
                EmptyTileIcon()
            }
        }
    }

    private var noteField: some View {
        let hint: LocalizedStringKey = draft.kind == "TRANSFER" ? "entry.addNote" : "entry.merchantOrNote"
        return TextField(hint, text: noteBinding)
            .focused(noteFocused)
            .submitLabel(.done)
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
    }

    private var noteBinding: Binding<String> {
        return Binding<String>(get: { draft.note }, set: { draft.typeNote($0) })
    }
}

/// A tile of the form: a small label over the chosen value (a hint while none is chosen), an icon, 56 pt tall; opens its picker.
struct EntryTile<Icon: View>: View {
    let label: LocalizedStringKey
    let value: String
    let selected: Bool
    let action: () -> Void
    let icon: Icon

    init(label: LocalizedStringKey, value: String, selected: Bool, action: @escaping () -> Void, @ViewBuilder icon: () -> Icon) {
        self.label = label
        self.value = value
        self.selected = selected
        self.action = action
        self.icon = icon()
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                icon
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                    Text(value.isEmpty ? String(localized: "entry.choose") : value)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(value.isEmpty ? Palette.ink2 : Palette.ink)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Palette.ink : Palette.line, lineWidth: selected ? 2 : 0.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The empty icon of a tile that has no choice yet.
struct EmptyTileIcon: View {
    var body: some View {
        ZStack {
            Circle().fill(Palette.chip)
            Text("?")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Palette.ink2)
        }
        .frame(width: 32, height: 32)
        .accessibilityHidden(true)
    }
}

/// An account kind's symbol on a round chip.
struct AccountIconView: View {
    let type: String
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Palette.chip)
            Image(systemName: IconSymbols.account(type))
                .font(.system(size: size * 0.46, weight: .medium))
                .foregroundColor(Palette.ink2)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Today, Yesterday, Other day: the first two set the day; the third opens the calendar in the panel below and shows the day picked.
struct DateRow: View {
    @ObservedObject var draft: EntryDraftModel

    private var isToday: Bool { draft.epochDay == draft.today }
    private var isYesterday: Bool { draft.epochDay == draft.today - 1 }
    private var otherLabel: String {
        if isToday || isYesterday { return String(localized: "entry.otherDay") }
        return KassaDates.shortDate(epochDay: draft.epochDay)
    }

    var body: some View {
        HStack(spacing: 8) {
            FilterChip(title: String(localized: "entry.today"), selected: isToday, action: { draft.chooseDay(draft.today) })
            FilterChip(title: String(localized: "entry.yesterday"), selected: isYesterday, action: { draft.chooseDay(draft.today - 1) })
            FilterChip(title: otherLabel, selected: !isToday && !isYesterday, action: { draft.show(.date) })
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Panels

/// The number pad, with the one ink button under it.
struct AmountPanel: View {
    @ObservedObject var draft: EntryDraftModel
    let busy: Bool
    let onSave: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            NumberPad(decimalMark: draft.decimalMark, onKey: { draft.press($0) })
            Button(action: primary) {
                Text(LocalizedStringKey(draft.buttonKey))
            }
            .buttonStyle(InkButtonStyle(filled: true))
            .disabled(busy)
            Spacer(minLength: 0)
        }
    }

    /// Next opens the next empty input; Add and Save write it.
    private func primary() {
        if let next = draft.missingPanel {
            draft.show(next)
        } else {
            onSave()
        }
    }
}

/// Today's categories as an icon grid (A to Z), or what matches the search. A top-level category with subcategories opens them.
struct CategoryPanel: View {
    @ObservedObject var draft: EntryDraftModel

    private var kind: String { draft.kind == "INCOME" ? "INCOME" : "EXPENSE" }

    var body: some View {
        let choices: [CategoryChoiceData] = draft.view.categoryChoices(kind: kind, query: draft.query)
        let searching = !draft.query.trimmingCharacters(in: .whitespaces).isEmpty
        VStack(spacing: 8) {
            PickerSearch(placeholder: "entry.searchCategories", text: $draft.query)
            ScrollView {
                if choices.isEmpty {
                    Text(searching ? String(format: String(localized: "entry.noCategoryMatches"), draft.query) : String(localized: "entry.noCategories"))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(4)
                } else {
                    TileGrid(items: choices, columns: searching ? 2 : 3) { choice in
                        tile(choice, searching: searching)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func tile(_ choice: CategoryChoiceData, searching: Bool) -> some View {
        let picked = draft.categoryId == choice.id
        let label = choice.parentName.isEmpty ? choice.name : choice.parentName + " \u{203A} " + choice.name
        PickTile(selected: picked, label: label, action: { draft.pick(category: choice) }) {
            if searching {
                HStack(spacing: 8) {
                    CategoryIconView(choice: choice, size: 28)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(choice.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Palette.ink)
                            .lineLimit(1)
                        if !choice.parentName.isEmpty {
                            Text(choice.parentName)
                                .font(.caption2)
                                .foregroundColor(Palette.ink2)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
            } else {
                VStack(spacing: 4) {
                    CategoryIconView(choice: choice, size: 34)
                    Text(choice.name)
                        .font(.caption.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                }
            }
        }
    }
}

/// A category's subcategories: "No subcategory" first. "+ New" (adding a subcategory without leaving) comes with the category
/// screens; until then a subcategory is made on Android or in a backup.
struct SubcategoryPanel: View {
    @ObservedObject var draft: EntryDraftModel

    /// The top-level category whose subcategories are shown: the picked one, or the parent of the picked subcategory.
    private var top: CategoryChoiceData? {
        guard draft.categoryId > 0, let picked = draft.view.categoryChoice(id: draft.categoryId) else { return nil }
        if picked.parentName.isEmpty { return picked }
        let all: [CategoryChoiceData] = draft.view.categoryChoices(kind: draft.kind == "INCOME" ? "INCOME" : "EXPENSE", query: "")
        return all.first { $0.name == picked.parentName }
    }

    var body: some View {
        if let parent = top {
            content(parent)
        } else {
            CategoryPanel(draft: draft)
        }
    }

    private func content(_ parent: CategoryChoiceData) -> some View {
        let subs: [CategoryChoiceData] = draft.view.subcategoryChoices(topId: parent.id)
        return VStack(spacing: 8) {
            HStack(spacing: 4) {
                Button(action: { draft.show(.category) }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(Text("a11y.back"))
                Text(parent.name)
                    .font(.headline)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            ScrollView {
                VStack(spacing: 6) {
                    PickTile(
                        selected: draft.categoryId == parent.id, label: String(localized: "entry.noSubcategory"),
                        action: { draft.pickSubcategory(parent.id) }
                    ) {
                        Text("entry.noSubcategory")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Palette.ink2)
                    }
                    TileGrid(items: subs, columns: 2) { sub in
                        PickTile(selected: draft.categoryId == sub.id, label: sub.name, action: { draft.pickSubcategory(sub.id) }) {
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
}

/// The accounts picker: search, kind chips (only kinds that have an account), a Recent row, then icon tiles grouped by kind and A to
/// Z. No amounts, nothing preselected; closed accounts and, for the source of a transfer, cards are left out (`AccountPicking`).
struct AccountPanel: View {
    @ObservedObject var draft: EntryDraftModel

    var body: some View {
        let isTo = draft.panel == .to
        let pick: AccountPickData = draft.view.accountPick(
            entryKind: draft.kind, field: isTo ? "TO" : "ACCOUNT", accountId: draft.accountId, filter: draft.filter,
            query: draft.query, recentLimit: 3
        )
        let selected: Int64 = isTo ? draft.toAccountId : draft.accountId
        return AccountPickerBody(
            pick: pick, selectedId: selected, query: $draft.query, filter: $draft.filter, onPick: { draft.pick(account: $0) }
        )
    }
}

/// The picker itself, for the entry page and the "Pay from" choice of a card: it only lays out the [pick] it is given.
struct AccountPickerBody: View {
    let pick: AccountPickData
    let selectedId: Int64
    @Binding var query: String
    @Binding var filter: String
    let onPick: (Int64) -> Void

    var body: some View {
        VStack(spacing: 8) {
            PickerSearch(placeholder: "entry.searchAccounts", text: $query)
            if pick.filters.count > 2 {
                chips
            }
            ScrollView {
                VStack(spacing: 6) {
                    if pick.groups.isEmpty {
                        Text(pick.noneOpen ? String(localized: "entry.addAccountFirst") : String(format: String(localized: "entry.noAccountMatches"), query))
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(4)
                    }
                    if !pick.recent.isEmpty {
                        GroupLabel(text: String(localized: "entry.recent"))
                        grid(pick.recent)
                    }
                    ForEach(pick.groups, id: \.key) { group in
                        GroupLabel(text: Wording.sectionTitle(group.key))
                        grid(group.accounts)
                    }
                }
            }
        }
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pick.filters, id: \.self) { name in
                    FilterChip(title: Wording.filterTitle(name), selected: filter == name, action: { filter = name })
                }
            }
        }
    }

    private func grid(_ accounts: [AccountChoiceData]) -> some View {
        TileGrid(items: accounts, columns: 3) { account in
            tile(account)
        }
    }

    private func tile(_ account: AccountChoiceData) -> some View {
        let label = account.last4.isEmpty ? account.name : String(format: String(localized: "entry.accountEnding"), account.name, account.last4)
        return PickTile(selected: selectedId == account.id, label: label, action: { onPick(account.id) }) {
            VStack(spacing: 4) {
                AccountIconView(type: account.type, size: 32)
                Text(account.name)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                if !account.last4.isEmpty {
                    Text("\u{00B7}\u{00B7}" + account.last4)
                        .font(.caption2)
                        .foregroundColor(Palette.ink2)
                }
            }
        }
    }
}

/// The calendar for "Other day": graphical, the day picked is the entry's day.
struct DatePanel: View {
    @ObservedObject var draft: EntryDraftModel

    var body: some View {
        VStack(spacing: 8) {
            DatePicker(selection: dateBinding, displayedComponents: .date) {
                Text("entry.date")
            }
            .datePickerStyle(.graphical)
            .environment(\.timeZone, KassaDates.utc)
            .environment(\.calendar, KassaDates.gregorian)
            Spacer(minLength: 0)
        }
    }

    private var dateBinding: Binding<Date> {
        return Binding<Date>(
            get: { KassaDates.date(epochDay: draft.epochDay) },
            set: { draft.chooseDay(KassaDates.epochDay(of: $0)) }
        )
    }
}
