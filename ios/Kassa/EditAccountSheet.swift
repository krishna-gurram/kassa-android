import SwiftUI
import KassaShared

/// What is typed on Edit account / Edit card; the rules are :domain's `AccountForm` and `EditRules` (through `AccountInput`).
struct EditAccountFields: FormDraft {
    var type: String
    var name: String
    var last4: String
    var balance: String
    var statementDay: String
    var dueDay: String
    var limit: String
    var payFromId: Int64
    /// A fixed deposit's maturity as an epoch day; 0 for none.
    var maturityDay: Int

    init(_ i: AccountInput) {
        type = i.type
        name = i.name
        last4 = i.last4
        balance = i.balance
        statementDay = i.statementDay
        dueDay = i.dueDay
        limit = i.limit
        payFromId = i.payFromId
        maturityDay = Int(i.maturityDay)
    }

    func input() -> AccountInput {
        return AccountInput(
            type: type, name: name, last4: last4, balance: balance, statementDay: statementDay, dueDay: dueDay,
            limit: limit, payFromId: payFromId, maturityDay: Int32(maturityDay)
        )
    }
}

/// Which part of the edit sheet is showing.
enum EditAccountStage: Equatable {
    case form
    case payFrom
    case maturity
    case kind
}

/// Edit an account or a card (as Android's Edit account page): its name, last four digits, a card's statement day, due day, limit and
/// default payment account, the balance today (it moves the starting balance, so entries stay as they are), a fixed deposit's maturity
/// and its kind. Archive and Restore sit at the foot. Every rule is :domain's; this only lays the fields out.
struct EditAccountSheet: View {
    @ObservedObject var model: AppModel
    let accountId: Int64
    let onClose: () -> Void

    @State private var loaded: AccountEditData?
    @State private var fields = EditAccountFields(AccountInput(type: "CHECKING", name: "", last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0))
    @State private var initial = EditAccountFields(AccountInput(type: "CHECKING", name: "", last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0))
    @State private var stage: EditAccountStage = .form
    @State private var problems: [String: String] = [:]
    @State private var submitted = false
    @State private var failure: String?
    @State private var busy = false
    @State private var query = ""
    @State private var confirmArchive = false
    @State private var confirmDiscard = false

    private var dirty: Bool { return fields != initial }

    private var kindInfo: AccountKindData? {
        let groups: [AccountKindGroupData] = AccountBridge.shared.kindGroups()
        for group in groups {
            for item in group.kinds where item.key == fields.type { return item }
        }
        return nil
    }

    private var isCard: Bool { return kindInfo?.hasStatement ?? false }
    private var isDebt: Bool { return kindInfo?.isDebt ?? false }
    private var hasMaturity: Bool { return kindInfo?.hasMaturity ?? false }

    var body: some View {
        VStack(spacing: 0) {
            if let data = loaded {
                switch stage {
                case .form:
                    form(data)
                case .payFrom:
                    payFromPicker
                case .maturity:
                    DayPickerStage(epochDay: $fields.maturityDay, onDone: { stage = .form })
                case .kind:
                    kindPicker(data)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .interactiveDismissDisabled(dirty)
        .onAppear { load() }
        .onChange(of: fields) { _ in failure = nil; refresh() }
        .keepsDraft(FormDraftStore.key("editAccount", accountId), current: fields, ready: loaded != nil, apply: { kept in
            var next = kept
            let kinds: [String] = loaded?.kinds ?? []
            if !kinds.contains(kept.type) { next.type = fields.type }
            next.payFromId = model.keepAccount(kept.payFromId)
            fields = next
        })
        .alert(Text("edit.discardTitle"), isPresented: $confirmDiscard) {
            Button("edit.keepEditing", role: .cancel) {}
            Button("edit.discard", role: .destructive) { onClose() }
        } message: {
            Text("edit.discardBody")
        }
    }

    private func load() {
        if loaded != nil { return }
        model.accountEditData(id: accountId) { data in
            guard let found = data else {
                onClose()
                return
            }
            let start = EditAccountFields(found.input)
            fields = start
            initial = start
            loaded = found
        }
    }

    private func refresh() {
        model.accountEditProblems(id: accountId, input: fields.input()) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
        }
    }

    /// The sentence under a field: once the person typed in it, or after pressing Save.
    private func shown(_ field: String, _ touched: Bool) -> String? {
        if submitted || touched { return problems[field] }
        return nil
    }

    private func leave() {
        if dirty { confirmDiscard = true } else { onClose() }
    }

    // MARK: The form

    private var balanceLabel: LocalizedStringKey {
        if fields.type == "INVESTMENT" { return "edit.cashToday" }
        if isCard { return "edit.totalBalanceToday" }
        if isDebt { return "edit.outstandingToday" }
        return "edit.balanceToday"
    }

    private var balanceNote: LocalizedStringKey {
        if fields.type == "INVESTMENT" { return "edit.investingNote" }
        return "edit.balanceNote"
    }

    private func form(_ data: AccountEditData) -> some View {
        let title: LocalizedStringKey = isCard ? "edit.card" : "edit.account"
        return VStack(spacing: 0) {
            HStack {
                Text(title).font(.headline)
                Spacer()
                Button("account.cancel", action: { leave() }).frame(minHeight: 48)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "account.name", problem: shown("NAME", !fields.name.isEmpty)) {
                        TextField("account.name", text: filtered($fields.name, { FieldBridge.shared.typedName(raw: $0) }))
                            .textInputAutocapitalization(.words)
                    }
                    FormField(label: "account.last4", problem: shown("LAST4", !fields.last4.isEmpty)) {
                        TextField("account.limitHint", text: filtered($fields.last4, { FieldBridge.shared.typedDigits(raw: $0, max: 4) }))
                            .keyboardType(.numberPad)
                    }
                    if isCard {
                        cardFields
                    }
                    FormField(label: balanceLabel, problem: shown("BALANCE", !fields.balance.isEmpty)) {
                        TextField(balanceLabel, text: filtered($fields.balance, { FieldBridge.shared.typedBalance(raw: $0) }))
                            .keyboardType(.numbersAndPunctuation)
                    }
                    Text(balanceNote)
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if hasMaturity {
                        maturityFields
                    }
                    if !data.kinds.isEmpty {
                        EntryTile(
                            label: "edit.kind", value: Wording.accountTypeTitle(fields.type), selected: false,
                            action: { stage = .kind }
                        ) {
                            AccountIconView(type: fields.type, size: 32)
                        }
                    }
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            VStack(spacing: 4) {
                Button("edit.saveChanges") { save() }
                    .buttonStyle(InkButtonStyle(filled: true))
                    .disabled(busy)
                archiveButton(data)
            }
            .padding(16)
        }
    }

    @ViewBuilder
    private var cardFields: some View {
        HStack(alignment: .top, spacing: 12) {
            FormField(label: "account.statementDay", problem: shown("STATEMENT_DAY", !fields.statementDay.isEmpty)) {
                TextField("account.dayHint", text: filtered($fields.statementDay, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }))
                    .keyboardType(.numberPad)
            }
            FormField(label: "account.dueDay", problem: shown("DUE_DAY", !fields.dueDay.isEmpty)) {
                TextField("account.dayHint", text: filtered($fields.dueDay, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }))
                    .keyboardType(.numberPad)
            }
        }
        FormField(label: "account.limit", problem: shown("LIMIT", !fields.limit.isEmpty)) {
            TextField("account.limitHint", text: filtered($fields.limit, { FieldBridge.shared.typedAmount(raw: $0) }))
                .keyboardType(.decimalPad)
        }
        payFromRow
    }

    private var payFromRow: some View {
        let choice: AccountChoiceData? = fields.payFromId > 0 ? model.picks?.accountChoice(id: fields.payFromId) : nil
        return VStack(alignment: .leading, spacing: 4) {
            EntryTile(label: "account.payFrom", value: choice?.name ?? "", selected: false, action: { query = ""; stage = .payFrom }) {
                if let chosen = choice {
                    AccountIconView(type: chosen.type, size: 32)
                } else {
                    EmptyTileIcon()
                }
            }
            if let text = shown("PAY_FROM", true) {
                Text(text).font(.footnote).foregroundColor(Palette.orange)
            }
        }
    }

    private var payFromPicker: some View {
        let empty = AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        let pick: AccountPickData = model.picks?.payFromPick(query: query) ?? empty
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "account.payFrom"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: fields.payFromId, query: $query, filter: .constant("ALL"),
                onPick: { id in fields.payFromId = id; stage = .form }
            )
            .padding(.horizontal, 16)
            Button("account.noPayFrom") { fields.payFromId = 0; stage = .form }
                .buttonStyle(InkButtonStyle(filled: false))
                .padding(16)
        }
    }

    /// A fixed deposit's optional maturity day: add it (a year out is offered), change it in the calendar, or clear it.
    @ViewBuilder
    private var maturityFields: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("account.maturesOptional").font(.caption).foregroundColor(Palette.ink2)
            if fields.maturityDay > 0 {
                Button(action: { stage = .maturity }) {
                    Text(KassaDates.shortDateYear(epochDay: fields.maturityDay)).foregroundColor(Palette.ink).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                }
                .padding(.horizontal, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
                Button("account.clearMaturity") { fields.maturityDay = 0 }
                    .foregroundColor(Palette.ink2)
                    .frame(minHeight: 48)
            } else {
                Button("account.addMaturity") { fields.maturityDay = Int(AccountBridge.shared.defaultMaturityDay(today: Int32(model.today))) }
                    .foregroundColor(Palette.ink)
                    .frame(minHeight: 48)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func kindPicker(_ data: AccountEditData) -> some View {
        let keys: [String] = data.kinds
        return VStack(spacing: 0) {
            SheetBar(title: String(localized: "edit.kind"), onBack: { stage = .form })
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(keys, id: \.self) { key in
                        Button(action: { fields.type = key; stage = .form }) {
                            HStack(spacing: 12) {
                                Image(systemName: IconSymbols.account(key)).frame(width: 28).foregroundColor(Palette.ink2)
                                Text(Wording.accountTypeTitle(key)).foregroundColor(Palette.ink)
                                Spacer()
                                if key == fields.type {
                                    Image(systemName: "checkmark").foregroundColor(Palette.ink)
                                }
                            }
                            .frame(minHeight: 52)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .overlay(alignment: .bottom) { Hairline() }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    // MARK: Archive and restore

    @ViewBuilder
    private func archiveButton(_ data: AccountEditData) -> some View {
        let archived = data.archived
        let label: LocalizedStringKey = archived ? "edit.restore" : (isCard ? "edit.archiveCard" : "edit.archiveAccount")
        Button(action: { if archived { setArchived(false) } else { confirmArchive = true } }) {
            Text(label)
                .font(.body.weight(.semibold))
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .alert(Text(String(format: String(localized: "edit.archiveTitle"), fields.name)), isPresented: $confirmArchive) {
            Button("account.cancel", role: .cancel) {}
            Button("edit.archive") { setArchived(true) }
        } message: {
            Text("edit.archiveBody")
        }
    }

    // MARK: Plumbing

    /// A text binding that applies the shared field filter as you type.
    private func filtered(_ text: Binding<String>, _ filter: @escaping (String) -> String) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in text.wrappedValue = filter(raw) }
        )
    }

    private func save() {
        submitted = true
        busy = true
        model.accountEditProblems(id: accountId, input: fields.input()) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
            if !found.isEmpty { busy = false; return }
            model.updateAccount(id: accountId, input: fields.input()) { outcome in finish(outcome) }
        }
    }

    private func setArchived(_ archived: Bool) {
        busy = true
        model.setAccountArchived(id: accountId, archived: archived) { outcome in finish(outcome) }
    }

    private func finish(_ outcome: WriteOutcome) {
        busy = false
        switch outcome {
        case .saved: onClose()
        case .refused(_, let message): failure = message
        case .failed(let message): failure = message
        }
    }
}
