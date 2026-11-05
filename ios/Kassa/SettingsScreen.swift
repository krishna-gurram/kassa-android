import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// Settings: Hide balances, Currency, Backup and restore, and the third-party notices. The app's switches live in `AppModel`
/// (Hide balances is remembered in UserDefaults); backups and restores run through `BackupFile` and the store on the model's queue.
/// This wrapper owns the system sheets (file exporter and importer, the password and "Replace everything?" alerts); the layout is
/// `SettingsContent`, which only reads values.
struct SettingsScreen: View {
    @ObservedObject var model: AppModel

    @State private var choosingRestore = false
    @State private var choosingImport = false
    @State private var askingBackupPassword = false
    @State private var backupPassword = ""
    @State private var restorePassword = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                SettingsContent(
                    hidden: model.hideAmounts, currencyName: model.currencyName, working: model.working,
                    message: model.restoreMessage, notice: model.notice, linked: true,
                    onSetHidden: { shown in model.setHidden(shown) },
                    onBackUp: { askingBackupPassword = true },
                    onRestore: { choosingRestore = true },
                    onImport: { choosingImport = true },
                    currencyDestination: AnyView(CurrencyPage(model: model)),
                    noticesDestination: AnyView(NoticesPage()),
                    billsDestination: AnyView(BillsPage(model: model)),
                    budgetsDestination: AnyView(BudgetsPage(model: model)),
                    categoriesDestination: AnyView(CategoriesPage(model: model)),
                    notificationsDestination: AnyView(NotificationSettingsPage(model: model))
                )
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Palette.ground.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
        .background(exporterHost)
        .background(importerHost)
        .background(otherAppHost)
        .background(backupPasswordHost)
        .background(restorePasswordHost)
        .background(replaceHost)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("tab.settings")
                .font(.largeTitle.weight(.bold))
                .padding(.top, 8)
            hideRow
            Hairline()
            linkRow(title: "settings.currency", detail: currencyName, destination: currencyDestination)
            Hairline()
            linkRow(title: "settings.notifications", detail: "", destination: notificationsDestination)
            Hairline()
            linkRow(title: "settings.bills", detail: "", destination: billsDestination)
            Hairline()
            linkRow(title: "settings.budgets", detail: "", destination: budgetsDestination)
            Hairline()
            linkRow(title: "settings.categories", detail: "", destination: categoriesDestination)
            Hairline()
            sectionLabel("settings.backup.header")
            actionRow(title: "settings.backup.now", symbol: "square.and.arrow.up", action: onBackUp)
            Hairline()
            actionRow(title: "settings.restore.button", symbol: "square.and.arrow.down", action: onRestore)
            Hairline()
            actionRow(title: "settings.import", symbol: "tray.and.arrow.down", action: onImport)
            Hairline()
            backupStatus
            sectionLabel("settings.about")
            linkRow(title: "settings.notices", detail: "", destination: noticesDestination)
            Hairline()
            Text("settings.footer")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .padding(.top, 8)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    // MARK: Rows

    private var hideRow: some View {
        let binding = Binding<Bool>(get: { hidden }, set: { value in onSetHidden(value) })
        return VStack(alignment: .leading, spacing: 2) {
            Toggle(isOn: binding) {
                Text("settings.hideBalances")
                    .font(.body)
            }
            .accessibilityIdentifier("settingsHideBalances")
            Text("settings.hideBalancesHint")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
        }
        .padding(.vertical, 8)
    }

    private func sectionLabel(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundColor(Palette.ink2)
            .padding(.top, 18)
    }

    private func rowLabel(title: LocalizedStringKey, detail: String, chevron: Bool) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.body)
                .foregroundColor(Palette.ink)
            Spacer(minLength: 8)
            if !detail.isEmpty {
                Text(detail)
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

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
    }

    private func actionRow(title: LocalizedStringKey, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .foregroundColor(Palette.ink2)
                    .frame(width: 24)
                Text(title)
                    .font(.body)
                    .foregroundColor(Palette.ink)
                Spacer(minLength: 8)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(working)
    }

    @ViewBuilder
    private var backupStatus: some View {
        if working {
            HStack(spacing: 8) {
                ProgressView()
                Text("settings.backup.working")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .padding(.vertical, 4)
        }
        if let text = message {
            Text(text)
                .font(.footnote)
                .foregroundColor(Palette.orange)
        }
        if let text = notice {
            Text(text)
                .font(.footnote)
                .foregroundColor(Palette.ink2)
        }
        Text("settings.backup.note")
            .font(.footnote)
            .foregroundColor(Palette.ink2)
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
