import SwiftUI
import KassaShared

/// What is typed on Edit details; the rules are :domain's `LoanDetailsForm` (through `LoanDetailsInput`).
struct LoanDetailsFields: Equatable {
    var name: String
    var dueDay: String
    var payFromId: Int64
    var interestCategoryId: Int64
    var counts: Bool
    var principalCategoryId: Int64
    var escrow: String
    var fee: String
    var earlyCharge: String
    var original: String
    var startDay: Int

    init(_ i: LoanDetailsInput) {
        name = i.name
        dueDay = i.dueDay
        payFromId = i.payFromId
        interestCategoryId = i.interestCategoryId
        counts = i.counts
        principalCategoryId = i.principalCategoryId
        escrow = i.escrow
        fee = i.fee
        earlyCharge = i.earlyCharge
        original = i.original
        startDay = Int(i.startDay)
    }

    func input() -> LoanDetailsInput {
        return LoanDetailsInput(
            name: name, dueDay: dueDay, payFromId: payFromId, interestCategoryId: interestCategoryId, counts: counts,
            principalCategoryId: principalCategoryId, escrow: escrow, fee: fee, earlyCharge: earlyCharge, original: original,
            startDay: Int32(startDay)
        )
    }
}

/// Which part of Edit details is showing.
enum LoanDetailsStage: Equatable {
    case form
    case account
    case date
    case interestCategory
    case principalCategory
}

/// Edit details of a loan: name, due day, where it is paid from, escrow, fee, early repayment charge, the interest category, whether the
/// whole payment counts as spending (and its principal category), the original amount and the start. The checks are :domain's.
struct LoanDetailsSheet: View {
    @ObservedObject var model: AppModel
    let request: LoanDetailsRequest

    @State private var fields: LoanDetailsFields
    @State private var stage: LoanDetailsStage = .form
    @State private var problems: [String: String] = [:]
    @State private var principalDefault = ""
    @State private var query = ""
    @State private var submitted = false
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: LoanDetailsRequest) {
        self.model = model
        self.request = request
        _fields = State(initialValue: LoanDetailsFields(request.data.input))
    }

    private var data: LoanDetailsSheetData { return request.data }

    private func category(_ id: Int64) -> CategoryChoiceData? {
        if id <= 0 { return nil }
        return model.picks?.categoryChoice(id: id)
    }

    private var startBinding: Binding<Int> {
        return Binding<Int>(get: { fields.startDay > 0 ? fields.startDay : model.today }, set: { day in fields.startDay = day })
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                LoanDetailsContent(
                    data: data, fields: $fields, problems: problems, showProblems: submitted, error: error, busy: busy,
                    payFrom: model.picks?.accountChoice(id: fields.payFromId > 0 ? fields.payFromId : -1),
                    interestChoice: category(fields.interestCategoryId), principalChoice: category(fields.principalCategoryId),
                    principalDefault: principalDefault,
                    onPickAccount: { query = ""; stage = .account }, onPickDay: { stage = .date },
                    onPickInterest: { stage = .interestCategory }, onPickPrincipal: { stage = .principalCategory },
                    onSave: { save() }, onClose: { model.closeLoanDetailsSheet() }
                )
            case .account:
                accountPicker
            case .date:
                DayPickerStage(epochDay: startBinding, onDone: { stage = .form })
            case .interestCategory:
                CategoryChooser(
                    ledger: model.picks, title: Wording.loanCostCategory(data.interestWord), selectedId: fields.interestCategoryId,
                    defaultText: data.interestDefaultName,
                    onPick: { id in fields.interestCategoryId = id; stage = .form }, onBack: { stage = .form }
                )
            case .principalCategory:
                CategoryChooser(
                    ledger: model.picks, title: String(localized: "loanPay.principalCategory"), selectedId: fields.principalCategoryId,
                    defaultText: String(format: String(localized: "loanPay.sameAsInterest"), principalDefault),
                    onPick: { id in fields.principalCategoryId = id; stage = .form }, onBack: { stage = .form }
                )
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { refresh() }
        .onChange(of: fields) { _ in error = nil; refresh() }
    }

    private var accountPicker: some View {
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

    private func refresh() {
        model.loanDetailsChecks(accountId: data.accountId, input: fields.input()) { found, name in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
            principalDefault = name
        }
    }

    private func save() {
        if busy { return }
        submitted = true
        if !problems.isEmpty { return }
        busy = true
        model.saveLoanDetails(accountId: data.accountId, input: fields.input()) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closeLoanDetailsSheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }
}

/// The Edit details form, drawn from what it is given (so a screenshot can draw it).
struct LoanDetailsContent: View {
    let data: LoanDetailsSheetData
    @Binding var fields: LoanDetailsFields
    let problems: [String: String]
    let showProblems: Bool
    let error: String?
    let busy: Bool
    let payFrom: AccountChoiceData?
    let interestChoice: CategoryChoiceData?
    let principalChoice: CategoryChoiceData?
    let principalDefault: String
    let onPickAccount: () -> Void
    let onPickDay: () -> Void
    let onPickInterest: () -> Void
    let onPickPrincipal: () -> Void
    let onSave: () -> Void
    let onClose: () -> Void

    private func problem(_ field: String) -> String? {
        if !showProblems { return nil }
        return problems[field]
    }

    private func filtered(_ text: Binding<String>, _ filter: @escaping (String) -> String) -> Binding<String> {
        return Binding<String>(get: { text.wrappedValue }, set: { raw in text.wrappedValue = filter(raw) })
    }

    private var nameBinding: Binding<String> {
        return filtered($fields.name, { FieldBridge.shared.typedName(raw: $0) })
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(localized: "loanDetails.title"), onBack: onClose)
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "loanDetails.name", problem: problem("NAME")) {
                        TextField("loanDetails.name", text: nameBinding)
                    }
                    FormField(label: "loanDetails.dueDay", problem: problem("DUE_DAY")) {
                        TextField(data.dueDayAnchor, text: filtered($fields.dueDay, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }))
                            .keyboardType(.numberPad)
                    }
                    AccountChoiceTile(label: "loan.payFrom", choice: payFrom, action: onPickAccount)
                    FormField(label: "loanDetails.escrow", problem: problem("ESCROW")) {
                        TextField("loanDetails.escrow", text: filtered($fields.escrow, { FieldBridge.shared.typedAmount(raw: $0) }))
                            .keyboardType(.decimalPad)
                    }
                    FormField(label: "loanDetails.fee", problem: problem("FEE")) {
                        TextField("loanDetails.fee", text: filtered($fields.fee, { FieldBridge.shared.typedAmount(raw: $0) }))
                            .keyboardType(.decimalPad)
                    }
                    FormField(label: "loanDetails.earlyCharge", problem: problem("EARLY_CHARGE")) {
                        TextField("loanDetails.earlyCharge", text: filtered($fields.earlyCharge, { FieldBridge.shared.typedRate(raw: $0) }))
                            .keyboardType(.decimalPad)
                    }
                    CategoryChoiceTile(
                        label: Wording.loanCostCategory(data.interestWord), choice: interestChoice,
                        defaultText: data.interestDefaultName, action: onPickInterest
                    )
                    SwitchLine(
                        title: String(localized: "loanPay.countWhole"), detail: String(localized: "loanDetails.countDetail"),
                        isOn: $fields.counts
                    )
                    if fields.counts {
                        CategoryChoiceTile(
                            label: String(localized: "loanPay.principalCategory"), choice: principalChoice,
                            defaultText: String(format: String(localized: "loanPay.sameAsInterest"), principalDefault),
                            action: onPickPrincipal
                        )
                    }
                    FormField(label: "loanDetails.original", problem: problem("ORIGINAL")) {
                        TextField("loanDetails.original", text: filtered($fields.original, { FieldBridge.shared.typedAmount(raw: $0) }))
                            .keyboardType(.decimalPad)
                    }
                    EntryTile(label: "loanDetails.start", value: startText, selected: false, action: onPickDay) {
                        EmptyTileIcon()
                    }
                    if let message = error {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: onSave) { Text("loanDetails.save") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    private var startText: String {
        if fields.startDay <= 0 { return "" }
        return KassaDates.shortDateYear(epochDay: fields.startDay)
    }
}
