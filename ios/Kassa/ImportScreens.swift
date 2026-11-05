import SwiftUI
import KassaShared

/// The import from another app, functional first (a visual pass comes later). `ImportScreen` owns the system pieces (the "Replace
/// everything?" question and the currency list) and picks a layout by phase; the layouts below only read values, so a screenshot can
/// draw them. All wording comes from the shared rules through `ImportWording` and Localizable.strings.
struct ImportScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var importer: ImportModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                content
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text("import.replaceTitle"), isPresented: $importer.asksReplace) {
            Button("import.replaceConfirm", role: .destructive) { importer.save(pickedCurrency: nil) }
            Button("import.cancel", role: .cancel) {}
        } message: {
            Text("import.replaceBody")
        }
        .sheet(isPresented: $importer.asksCurrency) {
            CurrencySheet(selected: "", onPick: { code in importer.save(pickedCurrency: code) })
        }
    }

    @ViewBuilder
    private var content: some View {
        switch importer.phase {
        case .idle:
            EmptyView()
        case .reading:
            ImportBusy(key: "import.reading")
        case .saving:
            ImportBusy(key: "import.saving")
        case .failed:
            ImportFailedContent(message: importer.message ?? "", onClose: { importer.reset() })
        case .questions:
            if let data = importer.question {
                ImportQuestionContent(
                    data: data,
                    onType: { pk, kind in importer.setAccountType(pk, kind) },
                    onLoan: { pk, choice in importer.setLoan(pk, choice) },
                    onLeaveOut: { pk, out in importer.setLeaveOut(pk, out) },
                    onNext: { importer.goOn() },
                    onBack: { importer.goBack() }
                )
            }
        case .review:
            if let data = importer.review {
                ImportReviewContent(
                    data: data, overExisting: importer.overExistingData,
                    onImport: { importer.confirm() }, onChooseDifferent: { importer.reset() }
                )
            }
        case .done:
            if let data = importer.doneReview {
                ImportDoneContent(data: data, onOpen: { importer.reset() })
            }
        }
    }
}

// MARK: Small pieces

struct ImportBusy: View {
    let key: LocalizedStringKey

    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text(key)
                .font(.body)
                .foregroundColor(Palette.ink2)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }
}

struct ImportFailedContent: View {
    let message: String
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("import.title")
                .font(.title2.weight(.bold))
            Text(message)
                .font(.body)
                .foregroundColor(Palette.orange)
            Button("import.close", action: onClose)
                .buttonStyle(InkButtonStyle(filled: true))
        }
        .padding(.top, 24)
    }
}

private struct ImportSectionLabel: View {
    let title: String
    var note: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Hairline()
            HStack {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                if !note.isEmpty {
                    Text(note)
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                }
            }
        }
        .padding(.top, 16)
    }
}

private struct ImportFact: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.body)
                .foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Text(value)
                .font(.body.weight(.semibold))
                .lineLimit(1)
        }
        .padding(.vertical, 4)
    }
}

private struct ImportBullet: View {
    let text: String
    var mark: String = "\u{2022}"
    var markColor: Color = Palette.ink2

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(mark)
                .font(.body.weight(.semibold))
                .foregroundColor(markColor)
            Text(text)
                .font(.body)
        }
        .padding(.vertical, 4)
    }
}

// MARK: Cashew questions

struct ImportQuestionContent: View {
    let data: ImportQuestionData
    let onType: (String, String) -> Void
    let onLoan: (String, String) -> Void
    let onLeaveOut: (String, Bool) -> Void
    let onNext: () -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            step
            actions
        }
        .padding(.top, 8)
    }

    @ViewBuilder
    private var step: some View {
        switch data.step {
        case "ACCOUNTS":
            accounts
        case "LOANS":
            loans
        case "CURRENCY":
            currency
        default:
            found
        }
    }

    // Found

    private var found: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("import.cashew.foundTitle")
                .font(.title2.weight(.bold))
            Text("import.cashew.foundBody")
                .font(.body)
                .foregroundColor(Palette.ink2)
            ImportFact(label: String(localized: "import.accounts"), value: ImportWording.number(data.accountCount))
            ImportFact(label: String(localized: "import.categories"), value: categoriesText)
            ImportFact(label: String(localized: "import.cashew.entries"), value: ImportWording.number(data.entries))
            ImportFact(label: String(localized: "import.transfers"), value: ImportWording.number(data.transfers))
            if data.hasRange {
                ImportFact(label: String(localized: "import.cashew.from"), value: rangeText)
            }
            Text("import.cashew.nextHint")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
        }
    }

    private var categoriesText: String {
        if data.subcategories > 0 {
            return String(format: String(localized: "import.cashew.categoriesSub"), ImportWording.number(data.categories), ImportWording.number(data.subcategories))
        }
        return ImportWording.number(data.categories)
    }

    private var rangeText: String {
        let first: String = KassaDates.shortDateYear(epochDay: Int(data.firstEpochDay))
        let last: String = KassaDates.shortDateYear(epochDay: Int(data.lastEpochDay))
        return String(format: String(localized: "import.cashew.range"), first, last)
    }

    // Accounts

    private var accounts: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("import.cashew.accountsTitle")
                .font(.title2.weight(.bold))
            Text("import.cashew.accountsBody")
                .font(.body)
                .foregroundColor(Palette.ink2)
            ForEach(data.accounts, id: \.pk) { account in
                accountRow(account)
            }
            if !data.canGoOn {
                Text("import.cashew.accountsPickAll")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    private func accountRow(_ account: ImportWalletData) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Hairline()
            HStack {
                Text(account.name)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(account.shownAmount)
                    .font(.body)
                    .lineLimit(1)
            }
            kindMenu(account)
            if account.other {
                Text(String(format: String(localized: "import.cashew.otherCurrencyNote"), account.currency.uppercased(), data.phoneCurrency))
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
            }
        }
    }

    private func kindMenu(_ account: ImportWalletData) -> some View {
        Menu {
            ForEach(ImportWording.accountKinds, id: \.self) { kind in
                Button(ImportWording.kindTitle(kind)) { onType(account.pk, kind) }
            }
        } label: {
            HStack(spacing: 6) {
                Text(ImportWording.kindTitle(account.kind))
                    .font(.subheadline.weight(.semibold))
                Image(systemName: "chevron.down")
                    .font(.footnote)
            }
            .foregroundColor(Palette.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Capsule().fill(Palette.chip))
        }
    }

    // Loans

    private var loans: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("import.cashew.loansTitle")
                .font(.title2.weight(.bold))
            Text("import.cashew.loansBody")
                .font(.body)
                .foregroundColor(Palette.ink2)
            ForEach(data.loans, id: \.pk) { loan in
                loanRow(loan)
            }
            ImportBullet(text: String(localized: "import.cashew.bulletPerson"))
            ImportBullet(text: String(localized: "import.cashew.bulletAccount"))
            ImportBullet(text: String(localized: "import.cashew.bulletEntries"))
            Text("import.cashew.loansOneOff")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
            if !data.canGoOn {
                Text("import.cashew.loansPickAll")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    private func loanRow(_ loan: ImportLoanData) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Hairline()
            Text(loan.name)
                .font(.body.weight(.semibold))
                .lineLimit(1)
            Text(ImportWording.loanLine(loan))
                .font(.footnote)
                .foregroundColor(Palette.ink2)
            HStack(spacing: 8) {
                FilterChip(title: String(localized: "import.cashew.choicePerson"), selected: loan.choice == "PERSON", action: { onLoan(loan.pk, "PERSON") })
                FilterChip(title: String(localized: "import.cashew.choiceAccount"), selected: loan.choice == "ACCOUNT", action: { onLoan(loan.pk, "ACCOUNT") })
                FilterChip(title: String(localized: "import.cashew.choiceEntries"), selected: loan.choice == "ENTRIES", action: { onLoan(loan.pk, "ENTRIES") })
            }
        }
    }

    // Other currency

    @ViewBuilder
    private var currency: some View {
        if let wallet = data.currencyAccounts.first {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(format: String(localized: "import.cashew.currencyTitle"), wallet.name, wallet.currency.uppercased()))
                    .font(.title2.weight(.bold))
                    .lineLimit(2)
                Text(String(format: String(localized: "import.cashew.currencyBody"), data.phoneCurrency))
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                HStack(spacing: 8) {
                    FilterChip(title: String(localized: "import.cashew.bringAsIs"), selected: !wallet.leaveOut, action: { onLeaveOut(wallet.pk, false) })
                    FilterChip(title: String(localized: "import.cashew.leaveOut"), selected: wallet.leaveOut, action: { onLeaveOut(wallet.pk, true) })
                }
                Text(ImportWording.currencyDetail(wallet))
                    .font(.body)
                Text("import.cashew.noConvert")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    // Buttons

    private var actions: some View {
        VStack(spacing: 8) {
            if data.canGoOn {
                Button("import.next", action: onNext)
                    .buttonStyle(InkButtonStyle(filled: true))
            }
            Button(LocalizedStringKey(data.step == "FOUND" ? "import.chooseDifferent" : "import.back"), action: onBack)
                .buttonStyle(InkButtonStyle(filled: false))
        }
        .padding(.top, 12)
    }
}

// MARK: Review

struct ImportReviewContent: View {
    let data: ImportReviewData
    let overExisting: Bool
    let onImport: () -> Void
    let onChooseDifferent: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("import.review.title")
                .font(.title2.weight(.bold))
            Text(String(format: String(localized: "import.review.from"), data.sourceName))
                .font(.body)
                .foregroundColor(Palette.ink2)
            comesOver
            checks
            lists
            buttons
        }
        .padding(.top, 8)
    }

    private var comesOver: some View {
        VStack(alignment: .leading, spacing: 0) {
            ImportSectionLabel(title: String(localized: "import.review.whatComesOver"))
            ImportFact(label: String(localized: "import.expenses"), value: ImportWording.number(data.expenses))
            ImportFact(label: String(localized: "import.income"), value: ImportWording.number(data.income))
            ImportFact(label: String(localized: "import.transfers"), value: ImportWording.number(data.transfers))
            ImportFact(label: String(localized: "import.accounts"), value: ImportWording.number(data.accounts))
            ImportFact(label: String(localized: "import.categories"), value: ImportWording.categories(data))
            ImportFact(label: String(localized: "import.budgets"), value: ImportWording.number(data.budgets))
            if data.people > 0 {
                ImportFact(label: String(localized: "import.people"), value: ImportWording.number(data.people))
            }
            if data.bills > 0 {
                ImportFact(label: String(localized: "import.bills"), value: ImportWording.number(data.bills))
            }
        }
    }

    private var checks: some View {
        let note: String = data.allMatch ? String(localized: "import.allMatch") : String(localized: "import.needsLook")
        return VStack(alignment: .leading, spacing: 0) {
            ImportSectionLabel(title: String(localized: "import.checks"), note: note)
            ImportBullet(
                text: ImportWording.checkAccounts(data), mark: data.accountsBad == 0 ? "\u{2713}" : "!",
                markColor: data.accountsBad == 0 ? Palette.green : Palette.orange
            )
            if data.monthChecks > 0 {
                ImportBullet(
                    text: ImportWording.checkMonths(data), mark: data.monthsBad == 0 ? "\u{2713}" : "!",
                    markColor: data.monthsBad == 0 ? Palette.green : Palette.orange
                )
            }
        }
    }

    private var lists: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !data.staysBehind.isEmpty {
                let title: String = data.appName.isEmpty ? String(localized: "import.staysBehind") : String(format: String(localized: "import.staysIn"), data.appName)
                ImportSectionLabel(title: title)
                ForEach(0..<data.staysBehind.count, id: \.self) { index in
                    ImportBullet(text: ImportWording.issue(data.staysBehind[index]))
                }
            }
            if !data.worthKnowing.isEmpty {
                ImportSectionLabel(title: String(localized: "import.worthKnowing"), note: String(localized: "import.fixLater"))
                ForEach(0..<data.worthKnowing.count, id: \.self) { index in
                    ImportBullet(text: ImportWording.issue(data.worthKnowing[index]))
                }
            }
        }
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            Button(String(format: String(localized: "import.importButton"), ImportWording.number(data.entries)), action: onImport)
                .buttonStyle(InkButtonStyle(filled: true))
            if overExisting {
                Text(LocalizedStringKey(data.bringsPeopleOrBills ? "import.replacesDetail" : "import.replacesSimple"))
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
            }
            Button("import.chooseDifferent", action: onChooseDifferent)
                .buttonStyle(InkButtonStyle(filled: false))
        }
        .padding(.top, 20)
    }
}

// MARK: Done

struct ImportDoneContent: View {
    let data: ImportReviewData
    let onOpen: () -> Void

    var body: some View {
        let title: String = data.appName.isEmpty ? String(localized: "import.doneTitle") : String(format: String(localized: "import.doneTitleFrom"), data.appName)
        let steps: [String] = ImportWording.nextSteps(data)
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.title2.weight(.bold))
            Text(String(format: String(localized: "import.doneBody"), ImportWording.number(data.entries)))
                .font(.body)
                .foregroundColor(Palette.ink2)
            ForEach(0..<steps.count, id: \.self) { index in
                ImportBullet(text: steps[index])
            }
            Button("import.openKassa", action: onOpen)
                .buttonStyle(InkButtonStyle(filled: true))
                .padding(.top, 20)
        }
        .padding(.top, 24)
    }
}
