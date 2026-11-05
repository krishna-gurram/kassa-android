import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// Shown when the database is empty: bring a backup (the file picker, and a password when the file has one) or start fresh.
/// Start fresh asks for the currency first when the phone's region currency isn't one Kassa lists (never guessed), then offers
/// the starter categories. A backup that carries no currency asks the same way. Failures are the plain sentences of `AppModel`.
struct FirstRunScreen: View {
    @ObservedObject var model: AppModel

    @State private var choosing = false
    @State private var password = ""
    @State private var step: Step = .welcome
    @State private var listing = false
    /// True while the list was opened by Start fresh, so a pick carries on to the categories.
    @State private var freshAfterPick = false

    enum Step {
        case welcome
        case categories
    }

    var body: some View {
        Group {
            if step == .welcome {
                welcome
            } else {
                StarterCategoriesStep(model: model, onBack: { step = .welcome })
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .sheet(isPresented: sheetShown, onDismiss: sheetClosed) {
            CurrencySheet(selected: model.chosenCurrency ?? "", onPick: picked)
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [UTType.data], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let first = urls.first {
                // Any file: a .kassa backup restores as before; a Money Manager or Cashew file goes to the import (`ImportModel`).
                model.importer.begin(first, replacing: false)
            }
        }
        .alert(Text("password.title"), isPresented: $model.asksPassword) {
            SecureField("password.field", text: $password)
            Button("password.open") {
                let typed = password
                password = ""
                model.submitPassword(typed)
            }
            Button("password.cancel", role: .cancel) {
                password = ""
                model.cancelPassword()
            }
        } message: {
            Text(model.restoreMessage ?? String(localized: "password.message"))
        }
    }

    private var welcome: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("firstRun.title")
                .font(.largeTitle.weight(.bold))
            Text("firstRun.subtitle")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.center)
            Spacer()
            if model.working {
                ProgressView()
            }
            if let message = model.restoreMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
                    .multilineTextAlignment(.center)
            }
            currencyRow
            Button("firstRun.chooseFile") { choosing = true }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(model.working)
            Button("firstRun.startFresh", action: startFresh)
                .buttonStyle(InkButtonStyle(filled: false))
                .disabled(model.working)
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// The chosen currency (or a prompt to pick one); tapping opens the list.
    private var currencyRow: some View {
        Button(action: { listing = true }) {
            HStack {
                Text("firstRun.currency")
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(currencyName)
                    .foregroundColor(model.chosenCurrency == nil ? Palette.ink2 : Palette.ink)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .font(.body)
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(RoundedRectangle(cornerRadius: 14).fill(Palette.chip))
        }
        .buttonStyle(.plain)
        .disabled(model.working)
    }

    private var currencyName: String {
        guard let code = model.chosenCurrency else { return String(localized: "firstRun.chooseCurrency") }
        let options: [CurrencyOptionData] = StartBridge.shared.currencies()
        return options.first { $0.code == code }.map { $0.name } ?? code
    }

    // MARK: Currency list

    private var sheetShown: Binding<Bool> {
        return Binding<Bool>(
            get: { listing || model.asksCurrencyForBackup },
            set: { shown in if !shown { listing = false } }
        )
    }

    private func sheetClosed() {
        // Left without picking: a backup waiting for a currency is not restored, and Start fresh waits.
        if model.asksCurrencyForBackup { model.cancelCurrency() }
        freshAfterPick = false
    }

    private func picked(_ code: String) {
        if model.asksCurrencyForBackup {
            model.finishBackup(currency: code)
        } else {
            model.chooseCurrency(code)
            if freshAfterPick { step = .categories }
        }
        freshAfterPick = false
        listing = false
    }

    private func startFresh() {
        if model.chosenCurrency == nil {
            freshAfterPick = true
            listing = true
        } else {
            step = .categories
        }
    }
}

/// The starter categories to tick before the app opens empty; the list and the defaults are :domain's (`StarterCategories`).
struct StarterCategoriesStep: View {
    @ObservedObject var model: AppModel
    let onBack: () -> Void

    @State private var picked: Set<String> = Set(StartBridge.shared.starters().filter { $0.on }.map { $0.name })

    var body: some View {
        let starters: [StarterData] = StartBridge.shared.starters()
        let spending: [StarterData] = starters.filter { $0.kind == "EXPENSE" }
        let income: [StarterData] = starters.filter { $0.kind != "EXPENSE" }
        return VStack(spacing: 12) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityLabel(Text("a11y.back"))
                Spacer()
            }
            Text("firstRun.categoriesTitle")
                .font(.title2.weight(.bold))
            Text("firstRun.categoriesBody")
                .font(.subheadline)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.center)
            ScrollView {
                VStack(spacing: 6) {
                    GroupLabel(text: String(localized: "firstRun.spending"))
                    chips(spending)
                    GroupLabel(text: String(localized: "firstRun.income"))
                    chips(income)
                }
            }
            if model.working {
                ProgressView()
            }
            Button("firstRun.start", action: begin)
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(model.working)
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func chips(_ items: [StarterData]) -> some View {
        TileGrid(items: items, columns: 2) { item in
            PickTile(selected: picked.contains(item.name), label: item.name, action: { toggle(item.name) }) {
                HStack(spacing: 6) {
                    Image(systemName: IconSymbols.symbol(for: item.icon))
                        .foregroundColor(Palette.ink2)
                    Text(item.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private func toggle(_ name: String) {
        if picked.contains(name) { picked.remove(name) } else { picked.insert(name) }
    }

    private func begin() {
        // List order, not the order of ticking, so the stored order is always the domain's.
        let all: [StarterData] = StartBridge.shared.starters()
        let names: [String] = all.map { $0.name }.filter { picked.contains($0) }
        model.startFresh(categories: names)
    }
}

/// The currencies Kassa lists, A to Z, each with a sample of how amounts will look; search narrows them.
struct CurrencySheet: View {
    let selected: String
    let onPick: (String) -> Void

    @State private var query = ""

    var body: some View {
        let all: [CurrencyOptionData] = StartBridge.shared.currencies()
        let text = query.trimmingCharacters(in: .whitespaces).lowercased()
        let shown: [CurrencyOptionData] = text.isEmpty ? all : all.filter { $0.name.lowercased().contains(text) || $0.code.lowercased().contains(text) }
        return VStack(spacing: 8) {
            Text("firstRun.currencyTitle")
                .font(.headline)
                .padding(.top, 16)
            Text("firstRun.currencyBody")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            PickerSearch(placeholder: "firstRun.searchCurrencies", text: $query)
                .padding(.horizontal, 16)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(shown, id: \.code) { option in
                        CurrencyRow(option: option, selected: selected, onPick: onPick)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// One currency of the list: its name and code, a sample of how amounts will look, and a tick on the picked one.
struct CurrencyRow: View {
    let option: CurrencyOptionData
    let selected: String
    let onPick: (String) -> Void

    var body: some View {
        Button(action: { onPick(option.code) }) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(option.name)
                        .font(.body)
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                    Text(option.code)
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                }
                Spacer(minLength: 8)
                Text(option.sample)
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                if option.code == selected {
                    Image(systemName: "checkmark")
                        .foregroundColor(Palette.ink)
                }
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { Hairline() }
    }
}
