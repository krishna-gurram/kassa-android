import SwiftUI
import KassaShared

private func loanNowMillis() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
}

// MARK: - Add terms

/// "Add terms" on a loan that has none: the setup form for a loan already under way. The terms save with the "Loan interest" category
/// (and "Escrow" when there is escrow) in one transaction, and the account keeps its entries and balance. All rules are :domain's.
struct LoanTermsSheet: View {
    @ObservedObject var model: AppModel
    let request: TermsRequest

    enum TermsStage: Equatable {
        case form
        case payFrom
        case day(LoanDayTarget)
    }

    @State private var draft: LoanDraft
    @State private var stage: TermsStage = .form
    @State private var problems: [String: String] = [:]
    @State private var submitted = false
    @State private var preview: LoanPreviewData?
    @State private var failure: String?
    @State private var busy = false
    @State private var query = ""

    init(model: AppModel, request: TermsRequest) {
        self.model = model
        self.request = request
        _draft = State(initialValue: LoanDraft(request.start))
    }

    private var payFromChoice: AccountChoiceData? {
        if draft.payFromId <= 0 { return nil }
        return model.picks?.accountChoice(id: draft.payFromId)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                form
            case .payFrom:
                payFromPicker
            case .day(let target):
                DayPickerStage(epochDay: LoanFormBody.dayBinding($draft, target), onDone: { stage = .form })
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .keepsDraft(FormDraftStore.key("loanTerms", request.accountId), current: draft, apply: { kept in
            var next = kept
            next.locked = draft.locked
            next.payFromId = model.keepAccount(kept.payFromId)
            next.interestCategoryId = model.keepCategory(kept.interestCategoryId)
            draft = next
        })
    }

    private var form: some View {
        return VStack(spacing: 0) {
            SheetBar(title: String(format: String(localized: "loan.addTermsTitle"), request.name), onBack: { model.closeTermsSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    LoanFormBody(
                        draft: $draft, problems: problems, submitted: submitted, preview: preview, today: model.today,
                        payFromName: payFromChoice?.name ?? "", payFromType: payFromChoice?.type ?? "",
                        onPickPayFrom: { query = ""; stage = .payFrom },
                        onPickDay: { target in stage = .day(target) }
                    )
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            Button("loan.saveTerms") { save() }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
        .onChange(of: draft) { _ in failure = nil; refresh() }
        .onAppear { refresh() }
    }

    private var payFromPicker: some View {
        let pick: AccountPickData = model.picks?.payFromPick(query: query) ?? AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "account.payFrom"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: draft.payFromId, query: $query, filter: .constant("ALL"),
                onPick: { id in draft.payFromId = id; stage = .form }
            )
            .padding(.horizontal, 16)
            Button("account.noPayFrom") { draft.payFromId = 0; stage = .form }
                .buttonStyle(InkButtonStyle(filled: false))
                .padding(16)
        }
    }

    private func refresh() {
        let input = draft.input()
        model.termsProblems(loan: input) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
        }
        model.loanPreview(loan: input) { data in preview = data }
    }

    private func save() {
        submitted = true
        busy = true
        let input = draft.input()
        model.termsProblems(loan: input) { found in
            if !found.isEmpty {
                var next: [String: String] = [:]
                for item in found { next[item.field] = item.message }
                problems = next
                busy = false
                return
            }
            model.addLoanTerms(accountId: request.accountId, loan: input) { outcome in
                busy = false
                switch outcome {
                case .saved: model.closeTermsSheet()
                case .refused(_, let message): failure = message
                case .failed(let message): failure = message
                }
            }
        }
    }
}

// MARK: - Balance correction

/// What is typed on the balance correction sheet: the figure the account should show, and "below zero" for an asset.
struct CorrectionDraft: FormDraft {
    var typed: String
    var below: Bool
}

/// Corrects an account's balance to what the bank or lender shows: it asks for that figure (a debt as what is owed), shows what Kassa
/// shows and the difference, and saves one dated correction entry; earlier entries stay as they are. Opened from an account page or
/// from a correction row in Activity (then it edits or deletes that correction). The size and kind of the entry are :domain's
/// `BalanceCorrection`. A new correction on a closed account is refused by the store.
struct CorrectionSheet: View {
    @ObservedObject var model: AppModel
    let request: CorrectionRequest

    @State private var typed: String
    @State private var below: Bool
    @State private var preview = CorrectionPreviewData(differenceText: "", problem: "")
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    init(model: AppModel, request: CorrectionRequest) {
        self.model = model
        self.request = request
        let shown = request.data.initialShown
        let start: String = request.data.editingId > 0 ? EntryBridge.shared.typedOf(cents: abs(shown)) : ""
        _typed = State(initialValue: start)
        _below = State(initialValue: shown < 0)
    }

    private var data: CorrectionSheetData { return request.data }
    private var symbol: String { return model.picks?.currencySymbol() ?? "" }
    private var isEditing: Bool { return data.editingId > 0 }

    /// What the account should show, in cents: negative only for an asset marked "below zero".
    private var typedShown: Int64 {
        let cents = EntryBridge.shared.cents(typed: typed)
        if below && !data.debt { return -cents }
        return cents
    }

    private var sheetTitleFormat: String {
        if isEditing { return String(localized: "correction.editTitle") }
        return String(localized: "correction.title")
    }

    private var amountLabel: LocalizedStringKey {
        if data.isLoan { return "correction.lenderOwed" }
        if data.debt { return "correction.owedNow" }
        return "correction.balanceNow"
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(
                title: String(format: sheetTitleFormat, data.accountName),
                onBack: { model.closeCorrectionSheet() }
            )
            ScrollView {
                VStack(spacing: 12) {
                    kassaLine
                    Text(amountLabel).font(.caption).foregroundColor(Palette.ink2).frame(maxWidth: .infinity, alignment: .leading)
                    AmountEntry(symbol: symbol, typed: $typed)
                    if !data.debt {
                        Toggle(isOn: $below) { Text("correction.belowZero") }
                            .frame(minHeight: 48)
                    }
                    differenceLines
                    if let message = error {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text("correction.save") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            if isEditing {
                Button(action: { confirmDelete = true }) { Text("correction.delete") }
                    .buttonStyle(InkButtonStyle(filled: false))
                    .disabled(busy)
                    .padding(16)
            } else {
                Spacer().frame(height: 16)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onChange(of: typed) { _ in error = nil; refresh() }
        .onChange(of: below) { _ in error = nil; refresh() }
        .onAppear { refresh() }
        .keepsDraft(
            FormDraftStore.key("correction", data.editingId > 0 ? data.editingId : -data.accountId),
            current: CorrectionDraft(typed: typed, below: below),
            apply: { kept in typed = AmountPadText.clean(kept.typed); below = kept.below }
        )
        .alert(Text("correction.deleteTitle"), isPresented: $confirmDelete) {
            Button("correction.keep", role: .cancel) {}
            Button("correction.deleteConfirm", role: .destructive) { delete() }
        } message: {
            Text("correction.deleteBody")
        }
    }

    private var kassaLine: some View {
        return HStack {
            Text("correction.kassaShows").font(.subheadline).foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Text(data.balanceInKassaText).font(.subheadline.weight(.semibold)).monospacedDigit().foregroundColor(Palette.ink)
        }
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var differenceLines: some View {
        if !preview.differenceText.isEmpty {
            HStack {
                Text("correction.difference").font(.subheadline).foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(preview.differenceText).font(.subheadline.weight(.semibold)).monospacedDigit().foregroundColor(Palette.ink)
            }
            .frame(minHeight: 48)
            .accessibilityElement(children: .combine)
        }
        if !preview.problem.isEmpty && !typed.isEmpty {
            Text(preview.problem).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func refresh() {
        model.correctionPreview(data: data, typedShown: typedShown, typed: !typed.isEmpty) { found in preview = found }
    }

    private func save() {
        if typed.isEmpty {
            error = String(localized: "correction.enterBalance")
            return
        }
        busy = true
        model.saveCorrection(data: data, typedShown: typedShown, epochMillis: loanNowMillis()) { outcome in
            busy = false
            switch outcome {
            case .saved: model.closeCorrectionSheet()
            case .refused(_, let message): error = message
            case .failed(let message): error = message
            }
        }
    }

    private func delete() {
        busy = true
        model.deleteCorrection(id: data.editingId) { outcome in
            busy = false
            switch outcome {
            case .saved: model.closeCorrectionSheet()
            case .refused(_, let message): error = message
            case .failed(let message): error = message
            }
        }
    }
}
