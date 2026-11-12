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
            zone: TimeZone.current.identifier, split: request.split, openSplit: request.openSplit,
            presetAccountId: request.presetAccountId
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
            },
            onAddSubcategory: model.subcategoryAdder
        )
        .onReceive(model.$picks) { next in
            if let fresh = next { draft.refresh(view: fresh) }
        }
        .onChange(of: draft.encoded) { text in
            // Only an unfinished new entry is kept, and only while there is something in it.
            if request.editing == nil { keptDraft = draft.isDirty ? text : "" }
        }
    }
}

/// The add and edit page. The start screen is the kind pill, the amount, the Category / Paid with / Date tiles, the note, the Split
/// row and the number pad; the top bar has back and one ink Save pill (grey until the entry is complete; tapped early it opens the
/// next empty input). Choosing opens a panel (category, subcategory, account, date, split) that replaces the body under a compact
/// amount line, with its title and Done. Picking moves on to the next empty input, so a new entry is a few taps. There is no eye
/// here: typed amounts are never masked. Leaving with unsaved changes asks first; deleting asks first.
struct AddEntryScreen: View {
    @ObservedObject var draft: EntryDraftModel
    let amountText: String
    let onSave: (EntryInput, @escaping (WriteOutcome) -> Void) -> Void
    let onDelete: (Int64, @escaping (WriteOutcome) -> Void) -> Void
    let onClose: () -> Void
    /// The subcategory picker's "+ New": adds a subcategory under a category and answers with the new one's id.
    var onAddSubcategory: (Int64, String, @escaping (WriteOutcome) -> Void) -> Void = { _, _, _ in }

    @FocusState private var noteFocused: Bool
    @State private var confirmDiscard = false
    @State private var confirmDelete = false
    @State private var busy = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if draft.panel == .amount {
                startScreen
            } else {
                panelScreen
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

    /// Whether the entry has everything it needs (the Save pill is ink); the checks themselves are :domain's.
    private var complete: Bool {
        return draft.missingPanel == nil && draft.cents > 0
    }

    private var topBar: some View {
        HStack(spacing: 4) {
            Button(action: back) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.back"))
            if draft.editing != nil {
                Text(title)
                    .font(Font.kassa(17, .semibold, relativeTo: .headline))
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if draft.editing != nil {
                Button("entry.delete") { confirmDelete = true }
                    .font(Font.kassa(16, .semibold))
                    .foregroundColor(Palette.orange)
                    .frame(minHeight: 48)
                    .padding(.horizontal, 8)
            }
            savePill
        }
        .padding(.leading, 8)
        .padding(.trailing, 20)
    }

    private var savePill: some View {
        return Button(action: primary) {
            Text("entry.save")
                .font(Font.kassa(16, .semibold))
                .foregroundColor(complete ? Palette.onInk : Palette.ink3)
                .padding(.horizontal, 22)
                .frame(minWidth: 76, minHeight: 44)
                .background(Capsule().fill(complete ? Palette.ink : Palette.chip))
                .frame(minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityHint(complete ? Text("") : Text("a11y.saveIncomplete"))
        .accessibilityIdentifier("entrySave")
    }

    /// Save writes the entry; before it is complete the pill opens the next empty input instead.
    private func primary() {
        if let next = draft.missingPanel {
            noteFocused = false
            draft.show(next)
        } else {
            save()
        }
    }

    /// Back leaves a panel for the one before it (a subcategory goes back to the categories); from the start screen it leaves the page.
    private func back() {
        if draft.sharePerson > 0 {
            draft.cancelShare()
        } else if draft.panel == .subcategory {
            draft.show(.category)
        } else if draft.panel != .amount {
            draft.show(.amount)
        } else {
            leave()
        }
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

    // MARK: Start screen and panels

    private var startScreen: some View {
        VStack(spacing: 0) {
            KassaScroll {
                AddEntryForm(draft: draft, noteFocused: $noteFocused)
                    .padding(.horizontal, 20)
                    .padding(.top, 4)
            }
            if !noteFocused {
                NumberPad(decimalMark: draft.decimalMark, onKey: { draft.press($0) })
                    .padding(.horizontal, 20)
                    .padding(.bottom, 8)
            }
        }
    }

    private var panelScreen: some View {
        VStack(spacing: 0) {
            AmountLine(draft: draft, size: 40)
                .padding(.horizontal, 20)
            if draft.sharePerson == 0 {
                PanelHeader(title: panelTitle, onDone: { draft.show(.amount) })
                    .padding(.horizontal, 20)
            }
            panelView
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
                .frame(maxHeight: .infinity, alignment: .top)
        }
    }

    private var panelTitle: String {
        switch draft.panel {
        case .category:
            return String(localized: "entry.category")
        case .subcategory:
            if let chosen = draft.view.categoryChoice(id: draft.categoryId) {
                return chosen.parentName.isEmpty ? chosen.name : chosen.parentName
            }
            return String(localized: "entry.category")
        case .account:
            if draft.kind == "TRANSFER" { return String(localized: "entry.from") }
            if draft.kind == "INCOME" { return String(localized: "entry.into") }
            return String(localized: "entry.paidWith")
        case .to:
            return String(localized: "entry.to")
        case .date:
            return String(localized: "entry.date")
        case .split:
            return String(localized: "split.title")
        case .amount:
            return String(localized: "entry.amount")
        }
    }

    @ViewBuilder
    private var panelView: some View {
        switch draft.panel {
        case .amount:
            EmptyView()
        case .category:
            CategoryPanel(draft: draft)
        case .subcategory:
            SubcategoryPanel(draft: draft, onAdd: onAddSubcategory)
        case .account, .to:
            AccountPanel(draft: draft)
        case .date:
            DatePanel(draft: draft)
        case .split:
            SplitPanel(draft: draft)
        }
    }
}

/// A panel's title (22 pt bold) with Done on the right, over the panel's body.
struct PanelHeader: View {
    let title: String
    let onDone: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(Font.kassa(22, .bold, relativeTo: .title2))
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            Button(action: onDone) {
                Text("entry.done")
                    .font(Font.kassa(16, .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 8)
    }
}

// MARK: - The amount

/// "AMOUNT" over the typed amount; the cents are muted, and the whole thing is muted while nothing is typed. [size] is 56 on the start
/// screen and smaller over a panel. Tapping it goes back to the number pad.
struct AmountLine: View {
    @ObservedObject var draft: EntryDraftModel
    let size: CGFloat

    private var whole: String {
        if draft.typed.isEmpty { return draft.symbol + "0" }
        let shown: String = draft.amountShown
        if let range = shown.range(of: draft.decimalMark, options: .backwards) {
            return draft.symbol + String(shown[shown.startIndex..<range.lowerBound])
        }
        return draft.symbol + shown
    }

    private var cents: String {
        if draft.typed.isEmpty { return draft.decimalMark + "00" }
        let shown: String = draft.amountShown
        if let range = shown.range(of: draft.decimalMark, options: .backwards) {
            return String(shown[range.lowerBound..<shown.endIndex])
        }
        return ""
    }

    private var wholeColor: Color {
        if draft.typed.isEmpty { return Palette.ink3 }
        return draft.kind == "INCOME" ? Palette.green : Palette.ink
    }

    private var numerals: Text {
        return Text(whole).foregroundColor(wholeColor) + Text(cents).foregroundColor(Palette.ink3)
    }

    var body: some View {
        Button(action: { draft.show(.amount) }) {
            VStack(alignment: .leading, spacing: 0) {
                Text("entry.amount")
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                numerals
                    .scaledNumeral(size)
                    .tracking(size > 48 ? -2 : -1)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            .padding(.leading, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("entry.amount"))
        .accessibilityValue(Text(draft.symbol + draft.amountShown))
    }
}

// MARK: - The form

/// The top of the start screen: kind, amount, the tiles, the note, the Split row, and the problem if there is one.
struct AddEntryForm: View {
    @ObservedObject var draft: EntryDraftModel
    var noteFocused: FocusState<Bool>.Binding

    private let kinds: [String] = ["EXPENSE", "INCOME", "TRANSFER"]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SegmentedPill(options: ["entry.kind.expense", "entry.kind.income", "entry.kind.transfer"], selection: kindBinding)
            AmountLine(draft: draft, size: 56)
                .padding(.top, 4)
            tiles
            noteField
            if draft.offersSplit {
                SplitRow(draft: draft)
            }
            if let problem = draft.error {
                Text(problem)
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var kindBinding: Binding<Int> {
        return Binding<Int>(
            get: { kinds.firstIndex(of: draft.kind) ?? 0 },
            set: { index in draft.switchKind(to: kinds[index]) }
        )
    }

    @ViewBuilder
    private var tiles: some View {
        if draft.kind == "TRANSFER" {
            HStack(spacing: 12) {
                accountTile(label: "entry.from", id: draft.accountId, panel: .account)
                accountTile(label: "entry.to", id: draft.toAccountId, panel: .to)
            }
            dateTile
        } else {
            categoryTile
            HStack(spacing: 12) {
                if draft.someoneElsePaid {
                    payerTile
                } else {
                    accountTile(label: draft.kind == "INCOME" ? "entry.into" : "entry.paidWith", id: draft.accountId, panel: .account)
                }
                dateTile
            }
        }
    }

    private var categoryTile: some View {
        let choice: CategoryChoiceData? = draft.categoryId > 0 ? draft.view.categoryChoice(id: draft.categoryId) : nil
        let value: String = choice.map { $0.parentName.isEmpty ? $0.name : $0.parentName + " \u{203A} " + $0.name } ?? ""
        let on = draft.panel == .category || draft.panel == .subcategory
        return EntryTile(label: "entry.category", value: value, selected: on, action: { draft.show(.category) })
    }

    /// Someone else paid: the tile that would name your account names them instead, and opens the split.
    private var payerTile: some View {
        let name: String = draft.view.personName(id: draft.split.paidBy)
        let value: String = name.isEmpty ? String(localized: "split.someone") : name
        return EntryTile(label: "split.paidBy", value: value, selected: draft.panel == .split, action: { draft.show(.split) })
    }

    private func accountTile(label: LocalizedStringKey, id: Int64, panel: EntryPanel) -> some View {
        let choice: AccountChoiceData? = id > 0 ? draft.view.accountChoice(id: id) : nil
        let value: String = choice?.name ?? ""
        return EntryTile(label: label, value: value, selected: draft.panel == panel, action: { draft.show(panel) })
    }

    private var dateTile: some View {
        let value: String
        if draft.epochDay == draft.today {
            value = String(localized: "entry.today")
        } else if draft.epochDay == draft.today - 1 {
            value = String(localized: "entry.yesterday")
        } else {
            value = KassaDates.shortDate(epochDay: draft.epochDay)
        }
        return EntryTile(label: "entry.date", value: value, selected: draft.panel == .date, action: { draft.show(.date) })
    }

    private var noteField: some View {
        let hint: LocalizedStringKey = draft.kind == "TRANSFER" ? "entry.addNote" : "entry.merchantOrNote"
        return VStack(spacing: 0) {
            TextField(hint, text: noteBinding)
                .font(Font.kassa(17, .regular))
                .focused(noteFocused)
                .submitLabel(.done)
                .frame(minHeight: 48)
                .padding(.horizontal, 4)
            Hairline()
        }
        .padding(.top, 4)
    }

    private var noteBinding: Binding<String> {
        return Binding<String>(get: { draft.note }, set: { draft.typeNote($0) })
    }
}

/// A tile of the form: a small label over the chosen value ("Choose" while none), a chevron; raised, hairline border, 16 pt radius,
/// at least 56 pt tall. Opens its picker. Callers that still pass an [icon] get it at the left.
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
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(Font.kassa(13, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                    Text(value.isEmpty ? String(localized: "entry.choose") : value)
                        .font(Font.kassa(17, .semibold, relativeTo: .body))
                        .foregroundColor(value.isEmpty ? Palette.ink3 : Palette.ink)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Palette.ink3)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.raised))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? Palette.ink : Palette.line, lineWidth: selected ? 2 : 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

extension EntryTile where Icon == EmptyView {
    init(label: LocalizedStringKey, value: String, selected: Bool, action: @escaping () -> Void) {
        self.init(label: label, value: value, selected: selected, action: action, icon: { EmptyView() })
    }
}

/// The empty icon of a tile that has no choice yet.
struct EmptyTileIcon: View {
    var body: some View {
        ZStack {
            Circle().fill(Palette.chip)
            Text("?")
                .font(Font.kassa(15, .semibold))
                .foregroundColor(Palette.ink2)
        }
        .frame(width: 32, height: 32)
        .accessibilityHidden(true)
    }
}

/// An account kind's symbol on a round badge, tinted by kind (checking blue, savings teal, cards pink, cash sage, the rest grey).
struct AccountIconView: View {
    let type: String
    let size: CGFloat

    private var tint: TintPair {
        if type == "CHECKING" { return Palette.blue }
        if type == "SAVINGS" { return Palette.teal }
        if type == "CREDIT_CARD" { return Palette.pink }
        if type == "CASH" { return Palette.sage }
        return Palette.neutral
    }

    var body: some View {
        ZStack {
            Circle().fill(tint.bg)
            Image(systemName: IconSymbols.account(type))
                .font(.system(size: size * 0.46, weight: .medium))
                .foregroundColor(tint.fg)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - Panels

/// Today's categories as an icon grid (A to Z), or what matches the search. A top-level category with subcategories opens them.
struct CategoryPanel: View {
    @ObservedObject var draft: EntryDraftModel

    private var kind: String { draft.kind == "INCOME" ? "INCOME" : "EXPENSE" }

    var body: some View {
        let choices: [CategoryChoiceData] = draft.view.categoryChoices(kind: kind, query: draft.query)
        let searching = !draft.query.trimmingCharacters(in: .whitespaces).isEmpty
        VStack(spacing: 8) {
            PickerSearch(placeholder: "entry.searchCategories", text: $draft.query)
            KassaScroll {
                if choices.isEmpty {
                    Text(searching ? String(format: String(localized: "entry.noCategoryMatches"), draft.query) : String(localized: "entry.noCategories"))
                        .font(Font.kassa(13, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(4)
                } else {
                    TileGrid(items: choices, columns: searching ? 2 : 3) { choice in
                        tile(choice, searching: searching)
                    }
                    .padding(.top, 8)
                }
            }
        }
    }

    @ViewBuilder
    private func tile(_ choice: CategoryChoiceData, searching: Bool) -> some View {
        let picked = draft.categoryId == choice.id
        let label = choice.parentName.isEmpty ? choice.name : choice.parentName + " \u{203A} " + choice.name
        if searching {
            PickTile(selected: picked, label: label, action: { draft.pick(category: choice) }) {
                HStack(spacing: 8) {
                    CategoryIconView(choice: choice, size: 32)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(choice.name)
                            .font(Font.kassa(15, .medium, relativeTo: .subheadline))
                            .foregroundColor(Palette.ink)
                            .lineLimit(1)
                        if !choice.parentName.isEmpty {
                            Text(choice.parentName)
                                .font(Font.kassa(12, .regular, relativeTo: .caption))
                                .foregroundColor(Palette.ink2)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            IconPickTile(selected: picked, label: label, name: choice.name, action: { draft.pick(category: choice) }) {
                CategoryIconView(choice: choice, size: 40)
            }
        }
    }
}

/// A category's subcategories as a plain list: "No subcategory" first, then each, and "+ New subcategory" to add one without leaving
/// (it is picked at once).
struct SubcategoryPanel: View {
    @ObservedObject var draft: EntryDraftModel
    let onAdd: (Int64, String, @escaping (WriteOutcome) -> Void) -> Void

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
        return KassaScroll {
            VStack(spacing: 0) {
                Hairline()
                ChoiceRow(
                    title: String(localized: "entry.noSubcategory"), selected: draft.categoryId == parent.id, muted: true,
                    action: { draft.pickSubcategory(parent.id) }
                )
                ForEach(subs) { sub in
                    ChoiceRow(title: sub.name, selected: draft.categoryId == sub.id, action: { draft.pickSubcategory(sub.id) })
                }
                NewSubcategoryTile(
                    ledger: draft.view, parentId: parent.id, parentName: parent.name, onAdd: onAdd,
                    onAdded: { id in draft.pickSubcategory(id) }
                )
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
        VStack(spacing: 4) {
            PickerSearch(placeholder: "entry.searchAccounts", text: $query)
            if pick.filters.count > 2 {
                chips
            }
            KassaScroll {
                VStack(spacing: 6) {
                    if pick.groups.isEmpty {
                        Text(pick.noneOpen ? String(localized: "entry.addAccountFirst") : String(format: String(localized: "entry.noAccountMatches"), query))
                            .font(Font.kassa(13, .regular, relativeTo: .footnote))
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
        KassaScroll(.horizontal) {
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
        let detail: String = account.last4.isEmpty ? "" : "\u{00B7}\u{00B7}" + account.last4
        return IconPickTile(
            selected: selectedId == account.id, label: label, name: account.name, detail: detail, action: { onPick(account.id) }
        ) {
            AccountIconView(type: account.type, size: 40)
        }
    }
}

/// The date panel: Today, Yesterday and Other day chips over the month calendar (days after today are not offered).
struct DatePanel: View {
    @ObservedObject var draft: EntryDraftModel

    private var isToday: Bool { draft.epochDay == draft.today }
    private var isYesterday: Bool { draft.epochDay == draft.today - 1 }
    private var otherLabel: String {
        if isToday || isYesterday { return String(localized: "entry.otherDay") }
        return KassaDates.shortDate(epochDay: draft.epochDay)
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                FilterChip(title: String(localized: "entry.today"), selected: isToday, action: { draft.chooseDay(draft.today) })
                FilterChip(title: String(localized: "entry.yesterday"), selected: isYesterday, action: { draft.chooseDay(draft.today - 1) })
                FilterChip(title: otherLabel, selected: !isToday && !isYesterday, action: { draft.chooseDay(draft.epochDay) })
                Spacer(minLength: 0)
            }
            EntryCalendar(selected: draft.epochDay, today: draft.today, onPick: { draft.chooseDay($0) })
            Spacer(minLength: 0)
        }
    }
}
