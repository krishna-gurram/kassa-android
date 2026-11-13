import SwiftUI
import KassaShared

/// What is typed on the payment sheet, as plain Swift values, and the `LoanPaymentInput` the shared rules read. The rules are :domain's
/// `LoanPaymentForm` (the suggested split, the part that follows the one typed, the problems); this only keeps the text and the picks.
struct LoanPaymentFields: FormDraft {
    var amount: String
    var day: Int
    var principal: String
    var interest: String
    var edit: String
    var fromId: Int64
    var categoryId: Int64
    var estimate: Int64
    var principalCategoryId: Int64
    var countOverride: String
    var escrowCategoryId: Int64

    init(_ i: LoanPaymentInput) {
        amount = i.amount
        day = Int(i.day)
        principal = i.principal
        interest = i.interest
        edit = i.edit
        fromId = i.fromId
        categoryId = i.categoryId
        estimate = i.estimate
        principalCategoryId = i.principalCategoryId
        countOverride = i.countOverride
        escrowCategoryId = i.escrowCategoryId
    }

    func input() -> LoanPaymentInput {
        return LoanPaymentInput(
            amount: amount, day: Int32(day), principal: principal, interest: interest, edit: edit, fromId: fromId,
            categoryId: categoryId, estimate: estimate, principalCategoryId: principalCategoryId, countOverride: countOverride,
            escrowCategoryId: escrowCategoryId
        )
    }

    /// Takes what the rules worked out after [action]; the field the person is typing in keeps its text unless it is still what was sent.
    mutating func take(_ next: LoanPaymentInput, action: String, sent: String) {
        edit = next.edit
        estimate = next.estimate
        countOverride = next.countOverride
        if action != "PRINCIPAL" || principal == sent { principal = next.principal }
        if action != "INTEREST" || interest == sent { interest = next.interest }
    }
}

/// Which part of the payment sheet is showing.
enum LoanPaymentStage: Equatable {
    case form
    case account
    case date
    case interestCategory
    case principalCategory
    case costCategory
}

/// Record a payment on a loan, or open one already recorded (edit or delete the whole payment). The amount starts as the scheduled
/// payment only because the person opened this; the account starts empty unless the loan names one. Kassa suggests the principal and
/// interest from the terms and the date; typing in one makes the other follow, so they always add up (all of that is :domain's).
struct LoanPaymentSheet: View {
    @ObservedObject var model: AppModel
    let request: LoanPaymentRequest

    @State private var fields: LoanPaymentFields
    @State private var stage: LoanPaymentStage = .form
    @State private var preview: LoanPaymentPreviewData?
    @State private var query = ""
    @State private var submitted = false
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    init(model: AppModel, request: LoanPaymentRequest) {
        self.model = model
        self.request = request
        _fields = State(initialValue: LoanPaymentFields(request.data.input))
    }

    private var data: LoanPaymentSheetData { return request.data }
    private var isEditing: Bool { return data.editingId > 0 }

    private func category(_ id: Int64) -> CategoryChoiceData? {
        if id <= 0 { return nil }
        return model.picks?.categoryChoice(id: id)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                form
            case .account:
                accountPicker
            case .date:
                DayPickerStage(epochDay: dayBinding, onDone: { stage = .form })
            case .interestCategory:
                CategoryChooser(
                    ledger: model.picks, title: Wording.loanCostCategory(data.interestWord), selectedId: fields.categoryId,
                    defaultText: preview?.interestDefaultName ?? "",
                    onPick: { id in fields.categoryId = id; stage = .form }, onBack: { stage = .form }, onAdd: model.subcategoryAdder
                )
            case .principalCategory:
                CategoryChooser(
                    ledger: model.picks, title: String(localized: "loanPay.principalCategory"), selectedId: fields.principalCategoryId,
                    defaultText: principalDefaultText,
                    onPick: { id in fields.principalCategoryId = id; stage = .form }, onBack: { stage = .form }, onAdd: model.subcategoryAdder
                )
            case .costCategory:
                CategoryChooser(
                    ledger: model.picks, title: costTitle, selectedId: fields.escrowCategoryId,
                    defaultText: preview?.costDefaultName ?? "",
                    onPick: { id in fields.escrowCategoryId = id; stage = .form }, onBack: { stage = .form }, onAdd: model.subcategoryAdder
                )
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { refresh() }
        .onChange(of: fields) { _ in error = nil; refresh() }
        .keepsDraft(FormDraftStore.key("loanPayment", data.editingId > 0 ? data.editingId : -data.accountId), current: fields, apply: { kept in
            var next = kept
            next.fromId = model.keepAccount(kept.fromId)
            next.categoryId = model.keepCategory(kept.categoryId)
            next.principalCategoryId = model.keepCategory(kept.principalCategoryId)
            next.escrowCategoryId = model.keepCategory(kept.escrowCategoryId)
            fields = next
        })
        .alert(Text("loanPay.deleteTitle"), isPresented: $confirmDelete) {
            Button("loanPay.keep", role: .cancel) {}
            Button("loanPay.deleteConfirm", role: .destructive) { delete() }
        } message: {
            Text(String(format: String(localized: "loanPay.deleteBody"), data.interestWord.lowercased()))
        }
    }

    private var principalDefaultText: String {
        guard let shown = preview else { return "" }
        return Wording.loanSameAs(kind: shown.principalDefaultKind, name: shown.principalDefaultName)
    }

    private var costTitle: String {
        if preview?.costIsEscrow ?? true { return String(localized: "loanPay.escrowCategory") }
        return String(localized: "loanPay.chargeCategory")
    }

    private var dayBinding: Binding<Int> {
        return Binding<Int>(
            get: { fields.day },
            set: { day in chooseDay(day) }
        )
    }

    private func chooseDay(_ day: Int) {
        fields.day = day
        send("DAY", String(day))
    }

    private var accountPicker: some View {
        let empty = AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        let pick: AccountPickData = model.picks?.payFromPick(query: query) ?? empty
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "loanPay.paidFrom"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: fields.fromId, query: $query, filter: .constant("ALL"),
                onPick: { id in fields.fromId = id; stage = .form }
            )
            .padding(.horizontal, 16)
            Button("account.noPayFrom") { fields.fromId = 0; stage = .form }
                .buttonStyle(InkButtonStyle(filled: false))
                .padding(16)
        }
    }

    private var form: some View {
        let shown = preview
        return LoanPaymentSheetContent(
            data: data, fields: fields, preview: shown, showProblems: submitted, error: error, busy: busy,
            payFrom: model.picks?.accountChoice(id: fields.fromId > 0 ? fields.fromId : -1),
            interestChoice: category(fields.categoryId), principalChoice: category(fields.principalCategoryId),
            costChoice: category(fields.escrowCategoryId), today: model.today,
            onAmount: { text in fields.amount = text; send("AMOUNT", text) },
            onPrincipal: { text in fields.principal = text; send("PRINCIPAL", text) },
            onInterest: { text in fields.interest = text; send("INTEREST", text) },
            onCounts: { on in send("COUNTS", on ? "1" : "0") },
            onDay: { day in chooseDay(day) },
            onOtherDay: { stage = .date },
            onPickAccount: { query = ""; stage = .account },
            onPickInterest: { stage = .interestCategory },
            onPickPrincipal: { stage = .principalCategory },
            onPickCost: { stage = .costCategory },
            onSave: { save() },
            onDelete: { confirmDelete = true },
            onClose: { model.closeLoanPaymentSheet() }
        )
    }

    // MARK: Rules

    /// Asks the shared rules for the form after one edit; the result only fills what the person is not typing in.
    private func send(_ action: String, _ text: String) {
        let sent = fields.input()
        model.loanPaymentApply(data: data, input: sent, action: action, text: text) { next in
            var merged = fields
            merged.take(next, action: action, sent: text)
            if merged != fields { fields = merged }
        }
    }

    private func refresh() {
        model.loanPaymentPreview(data: data, input: fields.input()) { shown in preview = shown }
    }

    private func save() {
        if busy { return }
        submitted = true
        if let found = preview, !found.problems.isEmpty { return }
        busy = true
        error = nil
        model.saveLoanPayment(data: data, input: fields.input()) { outcome in
            finished(outcome)
        }
    }

    private func delete() {
        if busy { return }
        busy = true
        model.deleteLoanPayment(id: data.editingId) { outcome in
            finished(outcome)
        }
    }

    private func finished(_ outcome: WriteOutcome) {
        busy = false
        switch outcome {
        case .saved:
            model.closeLoanPaymentSheet()
        case .refused(_, let message):
            error = message
        case .failed(let message):
            error = message
        }
    }
}

/// The payment sheet's form, drawn from what it is given (so a screenshot can draw it): amount, date, the account it is paid from, the
/// split into principal and interest, the categories, the count-as-spending switch and the summary.
struct LoanPaymentSheetContent: View {
    let data: LoanPaymentSheetData
    let fields: LoanPaymentFields
    let preview: LoanPaymentPreviewData?
    let showProblems: Bool
    let error: String?
    let busy: Bool
    let payFrom: AccountChoiceData?
    let interestChoice: CategoryChoiceData?
    let principalChoice: CategoryChoiceData?
    let costChoice: CategoryChoiceData?
    let today: Int
    let onAmount: (String) -> Void
    let onPrincipal: (String) -> Void
    let onInterest: (String) -> Void
    let onCounts: (Bool) -> Void
    let onDay: (Int) -> Void
    let onOtherDay: () -> Void
    let onPickAccount: () -> Void
    let onPickInterest: () -> Void
    let onPickPrincipal: () -> Void
    let onPickCost: () -> Void
    let onSave: () -> Void
    let onDelete: () -> Void
    let onClose: () -> Void

    private var isEditing: Bool { return data.editingId > 0 }

    private func problem(_ field: String) -> String? {
        if !showProblems { return nil }
        guard let found = preview else { return nil }
        for item in found.problems where item.field == field { return item.message }
        return nil
    }

    private func amountBinding(_ text: String, _ change: @escaping (String) -> Void) -> Binding<String> {
        return Binding<String>(
            get: { text },
            set: { raw in change(FieldBridge.shared.typedAmount(raw: raw)) }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "loanPay.amount", problem: problem("AMOUNT")) {
                        TextField("loanPay.amount", text: amountBinding(fields.amount, onAmount)).keyboardType(.decimalPad)
                    }
                    DayChips(epochDay: fields.day, today: today, onChoose: onDay, onOther: onOtherDay)
                    AccountChoiceTile(label: "loanPay.paidFrom", choice: payFrom, action: onPickAccount)
                    if let text = problem("FROM") {
                        Text(text).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    splitFields
                    categoryRows
                    spendingSwitch
                    summary
                    if let message = error {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: onSave) { Text(isEditing ? "loanPay.savePayment" : "loanPay.recordPayment") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    private var header: some View {
        HStack(spacing: 4) {
            Button(action: onClose) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(titleText).font(.headline).lineLimit(1)
            Spacer()
            if isEditing {
                Button("loanPay.delete", action: onDelete)
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.orange)
                    .frame(minHeight: 48)
                    .padding(.trailing, 8)
            }
        }
        .padding(.horizontal, 8)
    }

    private var titleText: String {
        if isEditing { return String(format: String(localized: "loanPay.editTitle"), data.loanName) }
        return String(format: String(localized: "loanPay.title"), data.loanName)
    }

    private var splitFields: some View {
        let word: String = preview?.interestWord ?? data.interestWord
        return VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                FormField(label: "loanPay.principal", problem: problem("PRINCIPAL")) {
                    TextField("loanPay.principal", text: amountBinding(fields.principal, onPrincipal)).keyboardType(.decimalPad)
                }
                FormField(label: LocalizedStringKey(word), problem: nil) {
                    TextField(LocalizedStringKey(word), text: amountBinding(fields.interest, onInterest)).keyboardType(.decimalPad)
                }
            }
            if let shown = preview, !shown.hint.isEmpty {
                Text(shown.hint).font(.footnote).foregroundColor(Palette.ink2)
            }
        }
    }

    @ViewBuilder
    private var categoryRows: some View {
        let word: String = preview?.interestWord ?? data.interestWord
        CategoryChoiceTile(
            label: Wording.loanCostCategory(word), choice: interestChoice, defaultText: preview?.interestDefaultName ?? "",
            action: onPickInterest
        )
        if let shown = preview, shown.counts {
            CategoryChoiceTile(
                label: String(localized: "loanPay.principalCategory"), choice: principalChoice,
                defaultText: Wording.loanSameAs(kind: shown.principalDefaultKind, name: shown.principalDefaultName),
                action: onPickPrincipal
            )
        }
        if let shown = preview, shown.hasCosts {
            CategoryChoiceTile(
                label: shown.costIsEscrow ? String(localized: "loanPay.escrowCategory") : String(localized: "loanPay.chargeCategory"),
                choice: costChoice, defaultText: shown.costDefaultName, action: onPickCost
            )
        }
    }

    private var spendingSwitch: some View {
        let counts: Bool = preview?.counts ?? false
        let loanCounts: Bool = preview?.loanCounts ?? false
        let detail: String = loanCounts ? String(localized: "loanPay.countOnForLoan") : String(localized: "loanPay.countJustThis")
        return SwitchLine(
            title: String(localized: "loanPay.countWhole"), detail: detail,
            isOn: Binding<Bool>(get: { counts }, set: { on in onCounts(on) })
        )
    }

    @ViewBuilder
    private var summary: some View {
        if let shown = preview {
            VStack(spacing: 4) {
                SummaryLine(label: String(localized: "loanPay.scheduled"), value: shown.scheduledText)
                SummaryLine(label: String(localized: "loanPay.extraToPrincipal"), value: shown.extraText)
                if shown.showCharge {
                    SummaryLine(label: String(localized: "loanPay.earlyCharge"), value: shown.chargeText)
                }
                if shown.showEscrow {
                    SummaryLine(label: String(localized: "loanPay.escrow"), value: shown.escrowText)
                }
                SummaryLine(label: String(localized: "loanPay.balanceAfter"), value: shown.balanceAfterText)
                SummaryLine(label: String(localized: "loanPay.countsAsSpending"), value: shown.countedText, strong: true)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
        }
    }
}
