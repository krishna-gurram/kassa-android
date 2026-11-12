import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// Settings: Hide balances, Currency, Backup and restore, and the third-party notices. The app's switches live in `AppModel`
/// (Hide balances is remembered in UserDefaults); backups and restores run through `BackupFile` and the store on the model's queue.
/// This wrapper owns the system sheets (file exporter and importer, the password and "Replace everything?" alerts); the layout is
/// `SettingsContent`, which only reads values.
struct SettingsScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var lock: AppLockModel

    @State private var choosingRestore = false
    @State private var choosingImport = false
    @State private var askingBackupPassword = false
    @State private var backupPassword = ""
    @State private var restorePassword = ""
    /// Settings > Automatic backups is showing (also set when the "Backup needs attention" notification is tapped).
    @State private var autoBackupOpen = false

    var body: some View {
        NavigationStack {
            ScrollView {
                SettingsContent(
                    hidden: model.hideAmounts, currencyName: model.currencyName, working: model.working,
                    message: model.restoreMessage, notice: model.notice, linked: true,
                    onSetHidden: { shown in model.setHidden(shown) },
                    showsLock: true, lockOn: lock.enabled, lockMessage: lock.message,
                    onSetLock: { on in lock.setEnabled(on) },
                    onBackUp: { askingBackupPassword = true },
                    onRestore: { choosingRestore = true },
                    onImport: { choosingImport = true },
                    currencyDestination: AnyView(CurrencyPage(model: model)),
                    noticesDestination: AnyView(NoticesPage()),
                    billsDestination: AnyView(BillsPage(model: model)),
                    budgetsDestination: AnyView(BudgetsPage(model: model)),
                    categoriesDestination: AnyView(CategoriesPage(model: model)),
                    notificationsDestination: AnyView(NotificationSettingsPage(model: model)),
                    theme: model.theme, onSetTheme: { name in model.setTheme(name) },
                    openOnTitle: OpenOnPage.title(model.homeTab), openOnDestination: AnyView(OpenOnPage(model: model)),
                    pinsDetail: String(format: String(localized: "settings.pins.value"), model.pinChoices.filter { $0.picked }.count),
                    pinsDestination: AnyView(PinAccountsPage(model: model)),
                    budgetMonthDetail: String(format: String(localized: "settings.budgetMonth.value"), model.monthStartDay),
                    budgetMonthDestination: AnyView(BudgetMonthPage(model: model)),
                    showsPerson: !model.convertibles.isEmpty, personDestination: AnyView(AccountToPersonPage(model: model)),
                    versionText: AppVersion.text,
                    autoBackupDestination: AnyView(AutoBackupPage(model: model)),
                    autoBackupDetail: autoBackupDetail,
                    coachDestination: AnyView(CoachSettingsPage(model: model))
                )
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationDestination(isPresented: $autoBackupOpen) {
                AutoBackupPage(model: model)
            }
            .toolbar(.hidden, for: .navigationBar)
            .onAppear {
                model.refreshAutoBackupInfo()
                openBackupPageIfAsked()
            }
            .onChange(of: model.backupPageRequested) { _ in openBackupPageIfAsked() }
        }
        .background(exporterHost)
        .background(importerHost)
        .background(otherAppHost)
        .background(backupPasswordHost)
        .background(restorePasswordHost)
        .background(replaceHost)
    }

    private var autoBackupDetail: String {
        if model.autoBackupInfo.folderName == nil { return String(localized: "autoBackup.off") }
        return String(localized: "autoBackup.on")
    }

    /// A tap on "Backup needs attention" asks for the automatic backups page once.
    private func openBackupPageIfAsked() {
        if model.backupPageRequested {
            model.backupPageRequested = false
            autoBackupOpen = true
        }
    }

    // MARK: System sheets, each on its own view so one never replaces another

    private var exporterHost: some View {
        let shown = Binding<Bool>(
            get: { model.pendingExport != nil },
            set: { open in if !open { model.exportCancelled() } }
        )
        let pending: BackupExport? = model.pendingExport
        return Color.clear.fileExporter(
            isPresented: shown,
            document: BackupDocument(bytes: pending?.bytes ?? Data()),
            contentType: KassaTypes.backup,
            defaultFilename: pending?.name ?? "Kassa.kassa"
        ) { result in
            switch result {
            case .success:
                model.exportFinished(error: nil)
            case .failure(let problem):
                model.exportFinished(error: problem)
            }
        }
    }

    private var importerHost: some View {
        return Color.clear.fileImporter(
            isPresented: $choosingRestore, allowedContentTypes: [KassaTypes.backup], allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let first = urls.first {
                model.chooseBackup(first, replacing: true)
            }
        }
    }

    /// Settings > Import from another app: any file, since Money Manager and Cashew files have no type Kassa knows.
    private var otherAppHost: some View {
        return Color.clear.fileImporter(
            isPresented: $choosingImport, allowedContentTypes: [UTType.data], allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let first = urls.first {
                model.importer.begin(first, replacing: true)
            }
        }
    }

    private var backupPasswordHost: some View {
        return Color.clear.alert(Text("settings.backup.passwordTitle"), isPresented: $askingBackupPassword) {
            SecureField("settings.backup.passwordField", text: $backupPassword)
            Button("settings.backup.save") {
                let typed = backupPassword
                backupPassword = ""
                model.makeBackup(password: typed.isEmpty ? nil : typed)
            }
            Button("settings.cancel", role: .cancel) {
                backupPassword = ""
            }
        } message: {
            Text("settings.backup.passwordMessage")
        }
    }

    private var restorePasswordHost: some View {
        return Color.clear.alert(Text("password.title"), isPresented: $model.asksPassword) {
            SecureField("password.field", text: $restorePassword)
            Button("password.open") {
                let typed = restorePassword
                restorePassword = ""
                model.submitPassword(typed)
            }
            Button("password.cancel", role: .cancel) {
                restorePassword = ""
                model.cancelPassword()
            }
        } message: {
            Text(model.restoreMessage ?? String(localized: "password.message"))
        }
    }

    private var replaceHost: some View {
        let asking = Binding<Bool>(
            get: { model.asksReplace != nil },
            set: { _ in }
        )
        let line: String = model.asksReplace ?? ""
        return Color.clear.alert(Text("settings.replace.title"), isPresented: asking) {
            Button("settings.replace.confirm", role: .destructive) { model.confirmReplaceNow() }
            Button("settings.cancel", role: .cancel) { model.cancelReplace() }
        } message: {
            Text(String(format: String(localized: "settings.replace.body"), line))
        }
    }
}

/// The settings list without its scroll view or system sheets, so a screenshot can draw it. [linked] is false there: rows that open
/// a page are drawn without the navigation link.
struct SettingsContent: View {
    let hidden: Bool
    let currencyName: String
    let working: Bool
    let message: String?
    let notice: String?
    let linked: Bool
    let onSetHidden: (Bool) -> Void
    /// App lock (`AppLockModel`): the row shows only where the screen has the model (a screenshot has none).
    var showsLock: Bool = false
    var lockOn: Bool = false
    var lockMessage: String? = nil
    var onSetLock: (Bool) -> Void = { _ in }
    let onBackUp: () -> Void
    let onRestore: () -> Void
    /// Settings > Import from another app (batch K); a screen without it (an older call site) does nothing.
    var onImport: () -> Void = {}
    let currencyDestination: AnyView
    let noticesDestination: AnyView
    /// Settings > Bills and Settings > Notifications (batch I); a screen that has neither (a screenshot) leaves them out.
    var billsDestination: AnyView = AnyView(EmptyView())
    /// Settings > Budgets (batch J).
    var budgetsDestination: AnyView = AnyView(EmptyView())
    /// Settings > Categories (batch Q).
    var categoriesDestination: AnyView = AnyView(EmptyView())
    var notificationsDestination: AnyView = AnyView(EmptyView())
    /// Batch R: Appearance (theme), Home screen (the tab Kassa opens on, the Overview tiles), Budget month, Account to person (only when
    /// an account can be converted) and the version line. A screen without them (a screenshot) leaves them out.
    var theme: String? = nil
    var onSetTheme: (String) -> Void = { _ in }
    var openOnTitle: LocalizedStringKey = "tab.overview"
    var openOnDestination: AnyView? = nil
    var pinsDetail: String = ""
    var pinsDestination: AnyView? = nil
    var budgetMonthDetail: String = ""
    var budgetMonthDestination: AnyView? = nil
    var showsPerson: Bool = false
    var personDestination: AnyView = AnyView(EmptyView())
    var versionText: String = ""
    /// Settings > Automatic backups (batch S): the folder backups go to by themselves; the detail reads On or Off.
    var autoBackupDestination: AnyView = AnyView(EmptyView())
    var autoBackupDetail: String = ""
    /// Settings > Coach settings; a screen without it (a screenshot) leaves the section out.
    var coachDestination: AnyView? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabTop(title: "tab.settings", hidden: hidden, onToggle: { onSetHidden(!hidden) })
            group("settings.section.preferences")
            linkRow(title: "settings.currency", detail: currencyName, destination: currencyDestination)
            themeRows
            openOnRow
            pinsRow
            budgetMonthRow
            hideRow
            lockRows
            group("settings.section.money")
            linkRow(title: "settings.categories", detail: "", destination: categoriesDestination)
            linkRow(title: "settings.budgets", detail: "", destination: budgetsDestination)
            linkRow(title: "settings.bills", detail: "", destination: billsDestination)
            linkRow(title: "settings.notifications", detail: "", destination: notificationsDestination)
            coachGroup
            group("settings.section.data")
            actionRow(title: "settings.backup.now", action: onBackUp)
            linkRow(title: "settings.autoBackup", detail: autoBackupDetail, destination: autoBackupDestination)
            actionRow(title: "settings.restore.button", action: onRestore)
            actionRow(title: "settings.import", action: onImport)
            personRow
            backupStatus
            group("settings.about")
            linkRow(title: "settings.notices", detail: "", destination: noticesDestination)
            versionRow
            Text("settings.footer")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .padding(.top, 12)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 96)
    }

    // MARK: Rows

    /// A section: its uppercase label and the hairline the first row sits under.
    private func group(_ title: LocalizedStringKey) -> some View {
        return VStack(alignment: .leading, spacing: 4) {
            SectionLabel(title: title)
            Hairline()
        }
        .padding(.top, 18)
    }

    @ViewBuilder
    private var coachGroup: some View {
        if let destination = coachDestination {
            group("settings.section.coach")
            linkRow(title: "settings.coach", detail: "", destination: destination)
        }
    }

    @ViewBuilder
    private var lockRows: some View {
        if showsLock {
            lockRow
            Hairline()
        }
    }

    private var lockRow: some View {
        let binding = Binding<Bool>(get: { lockOn }, set: { value in onSetLock(value) })
        return VStack(alignment: .leading, spacing: 2) {
            Toggle(isOn: binding) {
                Text("settings.lock")
                    .font(Font.kassa(17))
            }
            .frame(minHeight: 52)
            .accessibilityIdentifier("settingsAppLock")
            Text(lockMessage ?? String(localized: "settings.lockHint"))
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(lockMessage == nil ? Palette.ink2 : Palette.orange)
                .padding(.bottom, 8)
        }
    }

    @ViewBuilder
    private var themeRows: some View {
        if let current = theme {
            themeRow(current)
            Hairline()
        }
    }

    private func themeTitle(_ name: String) -> LocalizedStringKey {
        if name == "LIGHT" { return "settings.theme.light" }
        if name == "DARK" { return "settings.theme.dark" }
        return "settings.theme.system"
    }

    private func themeRow(_ current: String) -> some View {
        let binding = Binding<String>(get: { current }, set: { value in onSetTheme(value) })
        return Menu {
            Picker("settings.theme", selection: binding) {
                Text("settings.theme.system").tag("SYSTEM")
                Text("settings.theme.light").tag("LIGHT")
                Text("settings.theme.dark").tag("DARK")
            }
        } label: {
            HStack(spacing: 8) {
                Text("settings.theme")
                    .font(Font.kassa(17))
                    .foregroundColor(Palette.ink)
                Spacer(minLength: 8)
                Text(themeTitle(current))
                    .font(Font.kassa(15))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
    }

    @ViewBuilder
    private var openOnRow: some View {
        if let destination = openOnDestination {
            if linked {
                NavigationLink(destination: destination) {
                    rowLabelKey(title: "settings.openOn", detail: openOnTitle, chevron: true)
                }
                .buttonStyle(.plain)
            } else {
                rowLabelKey(title: "settings.openOn", detail: openOnTitle, chevron: true)
            }
            Hairline()
        }
    }

    @ViewBuilder
    private var pinsRow: some View {
        if let destination = pinsDestination {
            linkRow(title: "settings.pins.title", detail: pinsDetail, destination: destination)
        }
    }

    @ViewBuilder
    private var budgetMonthRow: some View {
        if let destination = budgetMonthDestination {
            linkRow(title: "settings.budgetMonth", detail: budgetMonthDetail, destination: destination)
        }
    }

    @ViewBuilder
    private var personRow: some View {
        if showsPerson {
            linkRow(title: "settings.person.title", detail: "", destination: personDestination)
        }
    }

    @ViewBuilder
    private var versionRow: some View {
        if !versionText.isEmpty {
            Text(String(format: String(localized: "settings.version"), versionText))
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .frame(minHeight: 52, alignment: .leading)
        }
    }

    private var hideRow: some View {
        let binding = Binding<Bool>(get: { hidden }, set: { value in onSetHidden(value) })
        return VStack(alignment: .leading, spacing: 2) {
            Toggle(isOn: binding) {
                Text("settings.hideBalances")
                    .font(Font.kassa(17))
            }
            .frame(minHeight: 52)
            .accessibilityIdentifier("settingsHideBalances")
            Text("settings.hideBalancesHint")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .padding(.bottom, 8)
            Hairline()
        }
    }

    private func rowLabel(title: LocalizedStringKey, detail: String, chevron: Bool) -> some View {
        return rowLabel(title: title, detail: Text(detail), chevron: chevron, empty: detail.isEmpty)
    }

    private func rowLabelKey(title: LocalizedStringKey, detail: LocalizedStringKey, chevron: Bool) -> some View {
        return rowLabel(title: title, detail: Text(detail), chevron: chevron, empty: false)
    }

    private func rowLabel(title: LocalizedStringKey, detail: Text, chevron: Bool, empty: Bool) -> some View {
        return HStack(spacing: 8) {
            Text(title)
                .font(Font.kassa(17))
                .foregroundColor(Palette.ink)
                .multilineTextAlignment(.leading)
            Spacer(minLength: 8)
            if !empty {
                detail
                    .font(Font.kassa(15))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }

    /// A tappable row that opens a page, with the hairline under it.
    @ViewBuilder
    private func linkRow(title: LocalizedStringKey, detail: String, destination: AnyView) -> some View {
        if linked {
            NavigationLink(destination: destination) {
                rowLabel(title: title, detail: detail, chevron: true)
            }
            .buttonStyle(.plain)
        } else {
            rowLabel(title: title, detail: detail, chevron: true)
        }
        Hairline()
    }

    private func actionRow(title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        return VStack(spacing: 0) {
            Button(action: action) {
                rowLabel(title: title, detail: "", chevron: true)
            }
            .buttonStyle(.plain)
            .disabled(working)
            Hairline()
        }
    }

    @ViewBuilder
    private var backupStatus: some View {
        if working {
            HStack(spacing: 8) {
                ProgressView()
                Text("settings.backup.working")
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
            }
            .padding(.vertical, 4)
        }
        if let text = message {
            Text(text)
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.orange)
        }
        if let text = notice {
            Text(text)
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
        }
        Text("settings.backup.note")
            .font(Font.kassa(13, .regular, relativeTo: .footnote))
            .foregroundColor(Palette.ink2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }
}

/// Settings > Currency: the list Kassa shows; picking one relabels every amount without converting anything, and asks first when
/// there is data (as Android does).
struct CurrencyPage: View {
    @ObservedObject var model: AppModel

    @State private var query = ""
    @State private var confirming: CurrencyOptionData?

    var body: some View {
        let all: [CurrencyOptionData] = StartBridge.shared.currencies()
        let text = query.trimmingCharacters(in: .whitespaces).lowercased()
        let shown: [CurrencyOptionData] = text.isEmpty ? all : all.filter { $0.name.lowercased().contains(text) || $0.code.lowercased().contains(text) }
        return VStack(spacing: 8) {
            PickerSearch(placeholder: "firstRun.searchCurrencies", text: $query)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(shown, id: \.code) { option in
                        CurrencyRow(option: option, selected: model.currency, onPick: { code in pick(code, in: all) })
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle(Text("settings.currency"))
        .navigationBarTitleDisplayMode(.inline)
        .background(confirmHost)
    }

    private func pick(_ code: String, in all: [CurrencyOptionData]) {
        if code == model.currency { return }
        let hasData: Bool = !(model.picks?.isEmpty() ?? true)
        if hasData {
            confirming = all.first { $0.code == code }
        } else {
            model.changeCurrency(code)
        }
    }

    private var confirmHost: some View {
        let asking = Binding<Bool>(
            get: { confirming != nil },
            set: { open in if !open { confirming = nil } }
        )
        let name: String = confirming?.name ?? ""
        let code: String = confirming?.code ?? ""
        let title = Text(String(format: String(localized: "settings.currency.confirmTitle"), name))
        return Color.clear.alert(title, isPresented: asking) {
            Button(String(format: String(localized: "settings.currency.switch"), code)) {
                model.changeCurrency(code)
                confirming = nil
            }
            Button("settings.cancel", role: .cancel) { confirming = nil }
        } message: {
            Text(String(format: String(localized: "settings.currency.confirmBody"), code))
        }
    }
}

/// Settings > Third-party notices: each library and its licence; tapping opens the full text.
struct NoticesPage: View {

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("settings.notices.intro")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.top, 8)
                ForEach(Licences.all) { entry in
                    NavigationLink(destination: LicenceTextPage(entry: entry)) {
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.name)
                                    .font(.body)
                                    .foregroundColor(Palette.ink)
                                    .multilineTextAlignment(.leading)
                                Text(entry.licence)
                                    .font(.footnote)
                                    .foregroundColor(Palette.ink2)
                            }
                            Spacer(minLength: 8)
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundColor(Palette.ink2)
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Hairline()
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle(Text("settings.notices"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// One licence's full text, read from the app bundle.
struct LicenceTextPage: View {
    let entry: LicenceEntry

    var body: some View {
        ScrollView {
            Text(Licences.text(for: entry))
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle(Text(entry.name))
        .navigationBarTitleDisplayMode(.inline)
    }
}
