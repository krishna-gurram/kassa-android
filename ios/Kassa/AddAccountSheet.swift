import SwiftUI
import KassaShared

/// Add an account, type first (as on Android): a list of kinds, then a form with only that kind's fields and a back arrow. Every
/// rule (the bounds, what a card needs, the sentence under a field) is :domain's `AccountForm`, asked through `AccountBridge`;
/// this only lays the fields out. Kinds that need more setup than this form (Loan or mortgage, Investment, Fixed deposit) are not
/// offered here yet (`AccountBridge.kindGroups`), so nothing is faked.
struct AddAccountSheet: View {
    @ObservedObject var model: AppModel
    let onClose: () -> Void

    enum Stage: Equatable {
        case kinds
        case account(String)
        case person
        case payFrom(String)
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
    @State private var theyOwe = false
    @State private var problems: [String: String] = [:]
    @State private var submitted = false
    @State private var failure: String?
    @State private var busy = false
    @State private var payQuery = ""

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .kinds:
                KindList(onPick: { key in stage = .account(key) }, onPerson: { stage = .person }, onClose: onClose)
            case .account(let key):
                accountForm(key)
            case .person:
                personForm
            case .payFrom(let key):
                payFromPicker(key)
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
            limit: limit, payFromId: payFromId
        )
    }

    private func accountForm(_ key: String) -> some View {
        let info = kind(key)
        let card = info?.hasStatement ?? false
        let debt = info?.isDebt ?? false
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
        payFromId = 0; theyOwe = false; problems = [:]; submitted = false; failure = nil
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
        if key == "PERSON" {
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
                Button("account.cancel", action: onClose).frame(minHeight: 44)
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
                    .frame(minWidth: 44, minHeight: 44)
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
