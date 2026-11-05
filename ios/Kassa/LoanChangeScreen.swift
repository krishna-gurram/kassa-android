import SwiftUI
import KassaShared

/// What is typed on the Change terms page; the rules are :domain's `LoanChangeForm` (through `LoanChangeInput`).
struct LoanChangeFields: Equatable {
    var kind: String
    var day: Int
    var rate: String
    var payment: String
    var termMonths: String
    var type: String
    var kept: String
    var basis: String

    init(_ i: LoanChangeInput) {
        kind = i.kind
        day = Int(i.day)
        rate = i.rate
        payment = i.payment
        termMonths = i.termMonths
        type = i.type
        kept = i.kept
        basis = i.basis
    }

    func input() -> LoanChangeInput {
        return LoanChangeInput(
            kind: kind, day: Int32(day), rate: rate, payment: payment, termMonths: termMonths, type: type, kept: kept, basis: basis
        )
    }
}

/// Change terms: a new rate, payment, interest type or a refinance, from a date (never before the loan began). The preview under the
/// form is `LoanEngine`'s: what the schedule will really do from that date.
struct LoanChangeScreen: View {
    @ObservedObject var model: AppModel
    let request: LoanChangeRequest

    @State private var fields: LoanChangeFields
    @State private var preview: LoanChangePreviewData?
    @State private var pickingDate = false
    @State private var submitted = false
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: LoanChangeRequest) {
        self.model = model
        self.request = request
        _fields = State(initialValue: LoanChangeFields(request.data.input))
    }

    private var data: LoanChangeSheetData { return request.data }

    private var dayBinding: Binding<Int> {
        return Binding<Int>(get: { fields.day }, set: { day in fields.day = day })
    }

    var body: some View {
        VStack(spacing: 0) {
            if pickingDate {
                DayPickerStage(epochDay: dayBinding, onDone: { pickingDate = false })
            } else {
                LoanChangeContent(
                    data: data, fields: $fields, preview: preview, showProblems: submitted, error: error, busy: busy,
                    onPickDay: { pickingDate = true }, onSave: { save() }, onClose: { model.closeLoanChangeSheet() }
                )
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { refresh() }
        .onChange(of: fields) { _ in error = nil; refresh() }
    }

    private func refresh() {
        model.loanChangePreview(accountId: data.accountId, input: fields.input()) { shown in preview = shown }
    }

    private func save() {
        if busy { return }
        submitted = true
        if let found = preview, !found.problems.isEmpty { return }
        busy = true
        model.saveLoanChange(accountId: data.accountId, input: fields.input()) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closeLoanChangeSheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }
}

/// The Change terms form, drawn from what it is given (so a screenshot can draw it).
struct LoanChangeContent: View {
    let data: LoanChangeSheetData
    @Binding var fields: LoanChangeFields
    let preview: LoanChangePreviewData?
    let showProblems: Bool
    let error: String?
    let busy: Bool
    let onPickDay: () -> Void
    let onSave: () -> Void
    let onClose: () -> Void

    private let kinds: [String] = ["RATE", "PAYMENT", "TYPE", "REFINANCE"]
    private let types: [String] = ["REDUCING", "DAILY", "FLAT", "INTEREST_ONLY"]

    private func problem(_ field: String) -> String? {
        if !showProblems { return nil }
        guard let found = preview else { return nil }
        for item in found.problems where item.field == field { return item.message }
        return nil
    }

    private func filtered(_ text: Binding<String>, _ filter: @escaping (String) -> String) -> Binding<String> {
        return Binding<String>(get: { text.wrappedValue }, set: { raw in text.wrappedValue = filter(raw) })
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(format: String(localized: "loanChange.title"), data.name), onBack: onClose)
            ScrollView {
                VStack(spacing: 12) {
                    kindChips
                    fieldsForKind
                    dateTile
                    if let text = problem("DATE") {
                        Text(text).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    previewPanel
                    if let message = error {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: onSave) { Text("loanChange.save") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    private var kindChips: some View {
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(kinds, id: \.self) { name in
                    FilterChip(title: NSLocalizedString("loanChange.kind." + name, comment: ""), selected: fields.kind == name, action: { fields.kind = name })
                }
            }
        }
    }

    private var rateLabel: String {
        return String(format: String(localized: "loanChange.newRate"), data.interestWord.lowercased())
    }

    @ViewBuilder
    private var fieldsForKind: some View {
        if fields.kind == "RATE" {
            rateField
            keptChips
        } else if fields.kind == "PAYMENT" {
            paymentField
            basisChips
        } else if fields.kind == "TYPE" {
            typeChips
            paymentField
            termField
            basisChips
        } else {
            rateField
            termField
            paymentField
            basisChips
        }
    }

    private var rateField: some View {
        return FormField(label: LocalizedStringKey(rateLabel), problem: problem("RATE")) {
            TextField(rateLabel, text: filtered($fields.rate, { FieldBridge.shared.typedRate(raw: $0) })).keyboardType(.decimalPad)
        }
    }

    private var paymentField: some View {
        return FormField(label: "loanChange.payment", problem: problem("PAYMENT")) {
            TextField("loanChange.payment", text: filtered($fields.payment, { FieldBridge.shared.typedAmount(raw: $0) })).keyboardType(.decimalPad)
        }
    }

    private var termField: some View {
        return FormField(label: "loanChange.term", problem: problem("TERM")) {
            TextField("loanChange.term", text: filtered($fields.termMonths, { FieldBridge.shared.typedDigits(raw: $0, max: 3) })).keyboardType(.numberPad)
        }
    }

    private var keptChips: some View {
        return VStack(alignment: .leading, spacing: 6) {
            Text("loanChange.lenderKept").font(.footnote).foregroundColor(Palette.ink2)
            HStack(spacing: 8) {
                FilterChip(title: String(localized: "loanChange.kept.PAYMENT"), selected: fields.kept == "PAYMENT", action: { fields.kept = "PAYMENT" })
                FilterChip(title: String(localized: "loanChange.kept.TERM"), selected: fields.kept == "TERM", action: { fields.kept = "TERM" })
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var basisChips: some View {
        if data.hasEscrow {
            HStack(spacing: 8) {
                FilterChip(title: String(localized: "loan.basis.PRINCIPAL_AND_INTEREST"), selected: fields.basis == "PRINCIPAL_AND_INTEREST", action: { fields.basis = "PRINCIPAL_AND_INTEREST" })
                FilterChip(title: String(localized: "loan.basis.INCLUDES_ESCROW"), selected: fields.basis == "INCLUDES_ESCROW", action: { fields.basis = "INCLUDES_ESCROW" })
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var typeChips: some View {
        return VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(types, id: \.self) { name in
                        FilterChip(title: NSLocalizedString("loan.kind." + name, comment: ""), selected: fields.type == name, action: { fields.type = name })
                    }
                }
            }
            if let text = problem("TYPE") {
                Text(text).font(.footnote).foregroundColor(Palette.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var dateTile: some View {
        let value: String = KassaDates.shortDateYear(epochDay: fields.day)
        return EntryTile(label: "loanChange.from", value: value, selected: false, action: onPickDay) {
            EmptyTileIcon()
        }
    }

    @ViewBuilder
    private var previewPanel: some View {
        if let shown = preview, shown.shown {
            VStack(spacing: 4) {
                SummaryLine(label: String(localized: "loanChange.paymentFromThen"), value: shown.paymentText, strong: true)
                if !shown.changeText.isEmpty {
                    SummaryLine(label: String(localized: "loanChange.change"), value: shown.changeText)
                }
                if !shown.splitPrincipalInterestText.isEmpty {
                    SummaryLine(label: String(localized: "loanChange.principalInterest"), value: shown.splitPrincipalInterestText)
                }
                if !shown.splitEscrowText.isEmpty {
                    SummaryLine(label: String(localized: "loanChange.escrow"), value: shown.splitEscrowText)
                }
                if shown.showPaymentsLeft {
                    SummaryLine(label: String(localized: "loanChange.paymentsLeft"), value: String(shown.paymentsLeft))
                }
                if shown.payoffDay > 0 {
                    SummaryLine(label: String(localized: "loanChange.paidOff"), value: KassaDates.shortDateYear(epochDay: Int(shown.payoffDay)))
                } else {
                    SummaryLine(label: String(localized: "loanChange.paidOff"), value: String(localized: "loan.noPayoff"))
                }
                if !shown.interestLeftText.isEmpty {
                    SummaryLine(label: String(localized: "loanChange.interestLeft"), value: shown.interestLeftText)
                }
                if !shown.warning.isEmpty {
                    Text(shown.warning).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                }
                suggestion(shown)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
        }
    }

    @ViewBuilder
    private func suggestion(_ shown: LoanChangePreviewData) -> some View {
        if !shown.suggestedPaymentText.isEmpty && fields.payment != shown.suggestedPaymentText {
            Button(action: { fields.payment = shown.suggestedPaymentText }) {
                Text("loanChange.useSuggested").font(.footnote.weight(.semibold))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 48)
        }
    }
}
