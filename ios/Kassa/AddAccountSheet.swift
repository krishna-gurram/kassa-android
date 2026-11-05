import SwiftUI
import KassaShared

/// Add an account, type first (as on Android): a list of kinds, then a form with only that kind's fields and a back arrow. Every
/// rule (the bounds, what a card needs, the sentence under a field) is :domain's `AccountForm`, asked through `AccountBridge`;
/// this only lays the fields out. Kinds that need more setup than this form (Investment, Fixed deposit) are not
/// offered here yet (`AccountBridge.kindGroups`), so nothing is faked; a Fixed deposit asks for an optional maturity day. Loan or mortgage has its own form (`LoanFormBody`).
struct AddAccountSheet: View {
    @ObservedObject var model: AppModel
    let onClose: () -> Void

    enum Stage: Equatable {
        case kinds
        case account(String)
        case person
        case payFrom(String)
        case day(LoanDayTarget)
        case maturity(String)
    }

    @State private var stage: Stage = .kinds

    // The form, kept here so choosing "Pay from" and coming back loses nothing.
    @State private var name = ""
    @State private var last4 = ""
    @State private var balance = ""
    @State private var statementDay = ""
    @State private var dueDay = ""
    @State private var limit = ""
    @State private var payFromId: Int64 = 0
    /// A fixed deposit's maturity as an epoch day; 0 for none.
    @State private var maturityDay: Int = 0
    @State private var theyOwe = false
    @State private var problems: [String: String] = [:]
    @State private var submitted = false
    @State private var failure: String?
    @State private var busy = false
    @State private var payQuery = ""
    // The loan form (Loan or mortgage): the rules are :domain's `LoanForm`, the text is kept here like the other fields.
    @State private var loan = LoanDraft.starting(today: KassaDates.todayEpochDay())
    @State private var loanPreview: LoanPreviewData?

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .kinds:
                KindList(onPick: { key in stage = .account(key) }, onPerson: { stage = .person }, onClose: onClose)
            case .account(let key):
                if key == "LOAN" {
                    loanForm
                } else {
                    accountForm(key)
                }
            case .person:
                personForm
            case .payFrom(let key):
                payFromPicker(key)
            case .day(let target):
                DayPickerStage(epochDay: LoanFormBody.dayBinding($loan, target), onDone: { stage = .account("LOAN") })
            case .maturity(let key):
                DayPickerStage(epochDay: $maturityDay, onDone: { stage = .account(key); refresh(key) })
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    // MARK: Account form

    private func kind(_ key: String) -> AccountKindData? {
        let groups: [AccountKindGroupData] = AccountBridge.shared.kindGroups()
        for group in groups {
            for item in group.kinds where item.key == key { return item }
        }
        return nil
    }

    private func input(_ key: String) -> AccountInput {
        return AccountInput(
            type: key, name: name, last4: last4, balance: balance, statementDay: statementDay, dueDay: dueDay,
            limit: limit, payFromId: payFromId, maturityDay: Int32(maturityDay)
        )
    }

    private func accountForm(_ key: String) -> some View {
        let info = kind(key)
        let card = info?.hasStatement ?? false
        let debt = info?.isDebt ?? false
        let matures = info?.hasMaturity ?? false
        let balanceKey: LocalizedStringKey = debt ? "account.owed" : "account.balance"
        return VStack(spacing: 0) {
            SheetBar(title: Wording.accountTypeTitle(key), onBack: { reset(); stage = .kinds })
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "account.name", problem: shown("NAME", !name.isEmpty)) {
                        TextField("account.name", text: bound($name, { FieldBridge.shared.typedName(raw: $0) }, key))
                            .textInputAutocapitalization(.words)
                    }
                    if card {
                        FormField(label: "account.last4", problem: shown("LAST4", !last4.isEmpty)) {
                            TextField("account.last4", text: bound($last4, { FieldBridge.shared.typedDigits(raw: $0, max: 4) }, key))
                                .keyboardType(.numberPad)
                        }
                    }
                    FormField(label: balanceKey, problem: shown("BALANCE", !balance.isEmpty)) {
                        TextField(balanceKey, text: bound($balance, { FieldBridge.shared.typedBalance(raw: $0) }, key))
                            .keyboardType(.numbersAndPunctuation)
                    }
                    if matures {
                        maturityFields(key)
                    }
                    if card {
                        cardFields(key)
                    }
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            Button("account.add") { saveAccount(key) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    /// A fixed deposit's optional maturity day: add it (a year out is offered), change it in the calendar, or clear it.
    @ViewBuilder
    private func maturityFields(_ key: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("account.maturesOptional").font(.caption).foregroundColor(Palette.ink2)
            if maturityDay > 0 {
                Button(action: { stage = .maturity(key) }) {
                    Text(KassaDates.shortDateYear(epochDay: maturityDay)).foregroundColor(Palette.ink).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                }
                .padding(.horizontal, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
                Button("account.clearMaturity") { maturityDay = 0; refresh(key) }
                    .foregroundColor(Palette.ink2)
                    .frame(minHeight: 48)
            } else {
                Button("account.addMaturity") { maturityDay = Int(AccountBridge.shared.defaultMaturityDay(today: Int32(model.today))); refresh(key) }
                    .foregroundColor(Palette.ink)
                    .frame(minHeight: 48)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func cardFields(_ key: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            FormField(label: "account.statementDay", problem: shown("STATEMENT_DAY", !statementDay.isEmpty)) {
                TextField("account.dayHint", text: bound($statementDay, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }, key))
                    .keyboardType(.numberPad)
            }
            FormField(label: "account.dueDay", problem: shown("DUE_DAY", !dueDay.isEmpty)) {
                TextField("account.dayHint", text: bound($dueDay, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }, key))
                    .keyboardType(.numberPad)
            }
        }
        FormField(label: "account.limit", problem: shown("LIMIT", !limit.isEmpty)) {
            TextField("account.limitHint", text: bound($limit, { FieldBridge.shared.typedAmount(raw: $0) }, key))
                .keyboardType(.decimalPad)
        }
        payFromRow(key)
    }

    private func payFromRow(_ key: String) -> some View {
        let choice: AccountChoiceData? = payFromId > 0 ? model.picks?.accountChoice(id: payFromId) : nil
        return EntryTile(label: "account.payFrom", value: choice?.name ?? "", selected: false, action: { payQuery = ""; stage = .payFrom(key) }) {
            if let chosen = choice {
                AccountIconView(type: chosen.type, size: 32)
            } else {
                EmptyTileIcon()
            }
        }
    }

    private func payFromPicker(_ key: String) -> some View {
        let pick: AccountPickData = model.picks?.payFromPick(query: payQuery) ?? AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "account.payFrom"), onBack: { stage = .account(key) })
            AccountPickerBody(
                pick: pick, selectedId: payFromId, query: $payQuery, filter: .constant("ALL"),
                onPick: { id in payFromId = id; stage = .account(key) }
            )
            .padding(.horizontal, 16)
            Button("account.noPayFrom") { payFromId = 0; stage = .account(key) }
                .buttonStyle(InkButtonStyle(filled: false))
                .padding(16)
        }
    }

    // MARK: Loan form

    private var loanInput: LoanInput {
        var draft = loan
        draft.payFromId = payFromId
        return draft.input()
    }

    private var payFromChoice: AccountChoiceData? {
        if payFromId <= 0 { return nil }
        return model.picks?.accountChoice(id: payFromId)
    }

    private var loanAccountInput: AccountInput {
        return AccountInput(type: "LOAN", name: name, last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0)
    }

    private var loanForm: some View {
        return VStack(spacing: 0) {
            SheetBar(title: Wording.accountTypeTitle("LOAN"), onBack: { reset(); stage = .kinds })
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "account.name", problem: shown("NAME", !name.isEmpty)) {
                        TextField("account.name", text: bound($name, { FieldBridge.shared.typedName(raw: $0) }, "LOAN"))
                            .textInputAutocapitalization(.words)
                    }
                    LoanFormBody(
                        draft: $loan, problems: problems, submitted: submitted, preview: loanPreview, today: model.today,
                        payFromName: payFromChoice?.name ?? "", payFromType: payFromChoice?.type ?? "",
                        onPickPayFrom: { payQuery = ""; stage = .payFrom("LOAN") },
                        onPickDay: { target in stage = .day(target) }
                    )
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            Button("account.add") { saveLoan() }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
        .onChange(of: loan) { _ in failure = nil; refresh("LOAN") }
        .onAppear { refresh("LOAN") }
    }

    private func saveLoan() {
        submitted = true
        busy = true
        model.loanProblems(account: loanAccountInput, loan: loanInput) { found in
            store(found)
            if !found.isEmpty { busy = false; return }
            model.addLoan(name: name, last4: "", loan: loanInput) { outcome in finish(outcome) }
        }
    }

    // MARK: Person form

    private var personForm: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(localized: "account.addPerson"), onBack: { reset(); stage = .kinds })
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "person.name", problem: shown("NAME", !name.isEmpty)) {
                        TextField("person.name", text: bound($name, { FieldBridge.shared.typedName(raw: $0) }, "PERSON"))
                            .textInputAutocapitalization(.words)
                    }
                    Picker(selection: personDirection, label: Text("person.direction")) {
                        Text("person.owesMe").tag(true)
                        Text("person.iOwe").tag(false)
                    }
                    .pickerStyle(.segmented)
                    FormField(label: "person.amount", problem: shown("AMOUNT", !balance.isEmpty)) {
                        TextField("person.amountHint", text: bound($balance, { FieldBridge.shared.typedAmount(raw: $0) }, "PERSON"))
                            .keyboardType(.decimalPad)
                    }
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            Button("account.add") { savePerson() }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    /// "They owe me" is true; the segment order puts it first.
    private var personDirection: Binding<Bool> {
        return Binding<Bool>(get: { theyOwe }, set: { theyOwe = $0; refresh("PERSON") })
    }

    // MARK: Plumbing

    private func reset() {
        name = ""; last4 = ""; balance = ""; statementDay = ""; dueDay = ""; limit = ""
        payFromId = 0; maturityDay = 0; theyOwe = false; problems = [:]; submitted = false; failure = nil
        loan = LoanDraft.starting(today: KassaDates.todayEpochDay()); loanPreview = nil
    }

    /// A text binding that applies the shared field filter as you type, then asks for the problems again.
    private func bound(_ text: Binding<String>, _ filter: @escaping (String) -> String, _ key: String) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in
                text.wrappedValue = filter(raw)
                failure = nil
                refresh(key)
            }
        )
    }

    private func refresh(_ key: String) {
        if key == "LOAN" {
            model.loanProblems(account: loanAccountInput, loan: loanInput) { found in store(found) }
            model.loanPreview(loan: loanInput) { data in loanPreview = data }
        } else if key == "PERSON" {
            model.personProblems(name: name, theyOwe: theyOwe, amount: balance) { found in store(found) }
        } else {
            model.accountProblems(input(key)) { found in store(found) }
        }
    }

    private func store(_ found: [FieldProblemData]) {
        var next: [String: String] = [:]
        for item in found { next[item.field] = item.message }
        problems = next
    }

    /// The sentence under a field: once the person typed in it, or after pressing Add.
    private func shown(_ field: String, _ touched: Bool) -> String? {
        if submitted || touched { return problems[field] }
        return nil
    }

    private func saveAccount(_ key: String) {
        submitted = true
        busy = true
        model.accountProblems(input(key)) { found in
            store(found)
            if !found.isEmpty { busy = false; return }
            model.addAccount(input(key)) { outcome in finish(outcome) }
        }
    }

    private func savePerson() {
        submitted = true
        busy = true
        model.personProblems(name: name, theyOwe: theyOwe, amount: balance) { found in
            store(found)
            if !found.isEmpty { busy = false; return }
            model.addPerson(name: name, theyOwe: theyOwe, amount: balance) { outcome in finish(outcome) }
        }
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

/// The list of kinds: grouped, then "Add a person".
struct KindList: View {
    let onPick: (String) -> Void
    let onPerson: () -> Void
    let onClose: () -> Void

    var body: some View {
        let groups: [AccountKindGroupData] = AccountBridge.shared.kindGroups()
        return VStack(spacing: 0) {
            HStack {
                Text("account.addTitle").font(.headline)
                Spacer()
                Button("account.cancel", action: onClose).frame(minHeight: 48)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(groups, id: \.key) { group in
                        GroupLabel(text: Wording.kindGroupTitle(group.key))
                        ForEach(group.kinds, id: \.key) { item in
                            row(Wording.accountTypeTitle(item.key), symbol: IconSymbols.account(item.key), action: { onPick(item.key) })
                        }
                    }
                    GroupLabel(text: String(localized: "account.people"))
                    row(String(localized: "account.addPerson"), symbol: "person", action: onPerson)
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func row(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .frame(width: 28)
                    .foregroundColor(Palette.ink2)
                Text(title).foregroundColor(Palette.ink)
                Spacer()
                Image(systemName: "chevron.right").font(.footnote).foregroundColor(Palette.ink2)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { Hairline() }
    }
}

/// A sheet's top line: a back arrow and a title.
struct SheetBar: View {
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(title).font(.headline).lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 8)
    }
}

/// A labelled text field with the sentence of its problem under it.
struct FormField<Content: View>: View {
    let label: LocalizedStringKey
    let problem: String?
    let content: Content

    init(label: LocalizedStringKey, problem: String?, @ViewBuilder content: () -> Content) {
        self.label = label
        self.problem = problem
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundColor(Palette.ink2)
            content
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
            if let text = problem {
                Text(text).font(.footnote).foregroundColor(Palette.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
