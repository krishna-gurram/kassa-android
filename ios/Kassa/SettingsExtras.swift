import SwiftUI
import KassaShared

/// The version line of Settings > About: the marketing version and the build number.
enum AppVersion {

    static var text: String {
        let info: [String: Any]? = Bundle.main.infoDictionary
        let short: String = info?["CFBundleShortVersionString"] as? String ?? ""
        let build: String = info?["CFBundleVersion"] as? String ?? ""
        if build.isEmpty { return short }
        return short + " (" + build + ")"
    }
}

/// A plain page of Settings: the ground colour, a scroll view and the same side margins.
private struct SettingsPageFrame<Content: View>: View {
    let title: LocalizedStringKey
    let content: Content

    init(title: LocalizedStringKey, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                content
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle(Text(title))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private func footnote(_ key: LocalizedStringKey) -> some View {
    return Text(key)
        .font(Font.kassa(13, .regular, relativeTo: .footnote))
        .foregroundColor(Palette.ink2)
        .padding(.vertical, 8)
}

/// Settings > Home screen > Open Kassa on: the tab Kassa shows when it starts. Settings is not offered.
struct OpenOnPage: View {
    @ObservedObject var model: AppModel

    static func title(_ name: String) -> LocalizedStringKey {
        if name == "ACTIVITY" { return "tab.activity" }
        if name == "INSIGHTS" { return "tab.insights" }
        if name == "ACCOUNTS" { return "tab.accounts" }
        return "tab.overview"
    }

    var body: some View {
        SettingsPageFrame(title: "settings.openOn") {
            footnote("settings.openOn.hint")
            ForEach(PhoneChoicesBridge.shared.homeTabs(), id: \.self) { name in
                row(name)
                Hairline()
            }
        }
    }

    private func row(_ name: String) -> some View {
        let selected: Bool = model.homeTab == name
        return Button(action: { model.setHomeTab(name) }) {
            HStack(spacing: 8) {
                Text(OpenOnPage.title(name))
                    .font(Font.kassa(17))
                    .foregroundColor(Palette.ink)
                Spacer(minLength: 8)
                if selected {
                    Image(systemName: "checkmark")
                        .font(Font.kassa(17, .semibold))
                        .foregroundColor(Palette.ink)
                }
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Settings > Home screen > Accounts on Overview: tick up to eight (People is one tile). Until something changes here Overview shows the
/// first eight; the first change saves the whole set (`PinRules`).
struct PinAccountsPage: View {
    @ObservedObject var model: AppModel

    private var limit: Int {
        return Int(PhoneChoicesBridge.shared.pinLimit())
    }

    var body: some View {
        let choices: [PinChoiceData] = model.pinChoices
        let picked: Int = choices.filter { $0.picked }.count
        return SettingsPageFrame(title: "settings.pins.title") {
            HStack {
                Text(String(format: String(localized: "settings.pins.hint"), limit))
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(String(format: String(localized: "settings.pins.count"), picked, limit))
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink)
            }
            .padding(.vertical, 8)
            if choices.isEmpty {
                Text("settings.pins.empty")
                    .font(Font.kassa(17))
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
            ForEach(choices, id: \.key) { choice in
                row(choice)
                Hairline()
            }
            Text(String(format: String(localized: "settings.pins.footer"), limit))
                .font(.caption)
                .foregroundColor(Palette.ink2)
                .padding(.top, 12)
        }
    }

    private func row(_ choice: PinChoiceData) -> some View {
        let title: String = choice.isPeople ? String(localized: "accounts.group.people") : choice.name
        return Button(action: { model.togglePin(choice.key) }) {
            HStack(spacing: 12) {
                Image(systemName: choice.picked ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(choice.enabled ? Palette.ink : Palette.ink2)
                Text(title)
                    .font(Font.kassa(17))
                    .foregroundColor(choice.enabled ? Palette.ink : Palette.ink2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if choice.isPeople {
                    Text("settings.pins.oneTile")
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                }
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!choice.enabled)
        .accessibilityAddTraits(choice.picked ? .isSelected : [])
    }
}

/// Settings > Budgeting > Budget month: the day the budget month starts, 1 to 28, from a few quick picks or typed.
struct BudgetMonthPage: View {
    @ObservedObject var model: AppModel

    @State private var typed = ""

    private var valid: Bool {
        return PhoneChoicesBridge.shared.monthStartValid(typed: typed)
    }

    private var days: [Int] {
        let found: [KotlinInt] = PhoneChoicesBridge.shared.quickMonthDays()
        return found.map { $0.intValue }
    }

    var body: some View {
        SettingsPageFrame(title: "settings.budgetMonth") {
            footnote("settings.budgetMonth.intro")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], alignment: .leading, spacing: 8) {
                ForEach(days, id: \.self) { day in
                    FilterChip(
                        title: String(format: String(localized: "settings.budgetMonth.day"), day),
                        selected: model.monthStartDay == day,
                        action: { model.setMonthStartDay(day) }
                    )
                }
            }
            .padding(.vertical, 8)
            HStack(spacing: 8) {
                TextField("settings.budgetMonth.other", text: $typed)
                    .keyboardType(.numberPad)
                    .frame(minHeight: 52)
                    .onChange(of: typed) { value in
                        typed = FieldBridge.shared.typedDigits(raw: value, max: 2)
                    }
                Button(action: set) {
                    Text("settings.set")
                        .font(Font.kassa(17, .semibold))
                        .foregroundColor(valid ? Palette.ink : Palette.ink2)
                        .frame(minWidth: 48, minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!valid)
            }
            Hairline()
        }
    }

    private func set() {
        guard valid else { return }
        model.setMonthStartDay(Int(typed) ?? 1)
        typed = ""
    }
}

/// Settings > Data > Account to person: pick an account that stands in for someone, name the person, and it becomes a person with the
/// same balance while the account is archived with its history. Nothing is matched by name; the rules are :domain's.
struct AccountToPersonPage: View {
    @ObservedObject var model: AppModel

    @State private var pickedId: Int64 = 0
    @State private var typed = ""
    @State private var confirming: ConvertibleAccountData?
    @State private var failure: String?
    @State private var done: String?

    private func problem() -> String? {
        if pickedId == 0 { return nil }
        guard let ledger = model.picks else { return nil }
        return ledger.convertProblem(accountId: pickedId, name: typed)
    }

    var body: some View {
        SettingsPageFrame(title: "settings.person.title") {
            footnote("settings.person.intro")
            if model.convertibles.isEmpty {
                Text("settings.person.none")
                    .font(Font.kassa(17))
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
            ForEach(model.convertibles, id: \.id) { account in
                accountRow(account)
                if account.id == pickedId {
                    nameForm(account)
                }
                Hairline()
            }
            if let text = failure {
                Text(text).font(Font.kassa(13, .regular, relativeTo: .footnote)).foregroundColor(Palette.orange).padding(.top, 8)
            }
            if let text = done {
                Text(text).font(Font.kassa(13, .regular, relativeTo: .footnote)).foregroundColor(Palette.ink2).padding(.top, 8)
            }
        }
        .alert(confirmTitle, isPresented: confirmBinding) {
            Button("settings.person.convert") { convert() }
            Button("settings.cancel", role: .cancel) { confirming = nil }
        } message: {
            Text(confirmBody)
        }
    }

    // MARK: Rows

    private func subtitle(_ account: ConvertibleAccountData) -> String {
        let side = account.side.name
        if side == "OwesYou" { return String(localized: "settings.person.receivable") }
        if side == "YouOwe" { return String(localized: "settings.person.payable") }
        if side == "Settled" { return String(localized: "settings.person.settled") }
        return ""
    }

    private func accountRow(_ account: ConvertibleAccountData) -> some View {
        let line: String = subtitle(account)
        return Button(action: { pick(account) }) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.name)
                        .font(Font.kassa(17))
                        .foregroundColor(Palette.ink)
                        .lineLimit(2)
                    if !line.isEmpty {
                        Text(line)
                            .font(Font.kassa(13, .regular, relativeTo: .footnote))
                            .foregroundColor(Palette.ink2)
                    }
                }
                Spacer(minLength: 8)
                Text(account.amountText)
                    .font(Font.kassa(17, .semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .padding(.vertical, 8)
            .frame(minHeight: 52, alignment: .top)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(account.id == pickedId ? .isSelected : [])
    }

    private func nameForm(_ account: ConvertibleAccountData) -> some View {
        let found: String? = problem()
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                TextField("settings.person.name", text: $typed)
                    .frame(minHeight: 52)
                    .onChange(of: typed) { value in
                        typed = FieldBridge.shared.typedName(raw: value)
                    }
                Button(action: { confirming = account }) {
                    Text("settings.person.convert")
                        .font(Font.kassa(17, .semibold))
                        .foregroundColor(found == nil ? Palette.ink : Palette.ink2)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(found != nil)
            }
            if let text = found {
                Text(text)
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.orange)
            }
        }
        .padding(.bottom, 8)
    }

    private func pick(_ account: ConvertibleAccountData) {
        failure = nil
        done = nil
        if pickedId == account.id {
            pickedId = 0
        } else {
            pickedId = account.id
            typed = FieldBridge.shared.typedName(raw: account.name)
        }
    }

    // MARK: Confirm

    private var confirmBinding: Binding<Bool> {
        return Binding<Bool>(get: { confirming != nil }, set: { open in if !open { confirming = nil } })
    }

    private var confirmTitle: Text {
        let account: String = confirming?.name ?? ""
        return Text(String(format: String(localized: "settings.person.confirmTitle"), account, typed))
    }

    private var confirmBody: String {
        guard let account = confirming else { return "" }
        let side = account.side.name
        if side == "YouOwe" {
            return String(format: String(localized: "settings.person.confirmPayable"), typed, account.amountText, account.name)
        }
        if side == "Hidden" {
            return String(format: String(localized: "settings.person.confirmHidden"), typed, account.name)
        }
        return String(format: String(localized: "settings.person.confirmReceivable"), typed, account.amountText, account.name)
    }

    private func convert() {
        guard let account = confirming else { return }
        let name: String = typed
        confirming = nil
        model.convertAccount(id: account.id, name: name) { outcome in
            switch outcome {
            case .saved:
                pickedId = 0
                failure = nil
                done = String(format: String(localized: "settings.person.done"), account.name, name)
            case .refused(_, let message):
                failure = message
            case .failed(let message):
                failure = message
            }
        }
    }
}
