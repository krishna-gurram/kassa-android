import SwiftUI
import KassaShared

/// What is typed on the loan setup form, as plain Swift values, and the `LoanInput` the shared rules read. The rules are :domain's
/// `LoanForm` (which fields a kind needs, what each says when wrong, the payment and the live calculation); this only keeps the text.
struct LoanDraft: Equatable {
    var stage: String
    var locked: Bool
    var kind: String
    var contract: String
    var amount: String
    var rate: String
    var termMonths: String
    var firstDay: Int
    var balance: String
    var payment: String
    var nextDay: Int
    var paymentsLeft: String
    var original: String
    var startDay: Int
    var dueDay: String
    var payFromId: Int64
    var escrow: String
    var fee: String
    var earlyCharge: String
    var interestCategoryId: Int64
    var basis: String

    init(_ i: LoanInput) {
        stage = i.stage
        locked = i.locked
        kind = i.kind
        contract = i.contract
        amount = i.amount
        rate = i.rate
        termMonths = i.termMonths
        firstDay = Int(i.firstDay)
        balance = i.balance
        payment = i.payment
        nextDay = Int(i.nextDay)
        paymentsLeft = i.paymentsLeft
        original = i.original
        startDay = Int(i.startDay)
        dueDay = i.dueDay
        payFromId = i.payFromId
        escrow = i.escrow
        fee = i.fee
        earlyCharge = i.earlyCharge
        interestCategoryId = i.interestCategoryId
        basis = i.basis
    }

    /// A new loan form: the domain's starting values for [today].
    static func starting(today: Int) -> LoanDraft {
        return LoanDraft(LoanBridge.shared.starting(todayEpochDay: Int32(today), payFromId: 0))
    }

    func input() -> LoanInput {
        return LoanInput(
            stage: stage, locked: locked, kind: kind, contract: contract, amount: amount, rate: rate, termMonths: termMonths,
            firstDay: Int32(firstDay), balance: balance, payment: payment, nextDay: Int32(nextDay), paymentsLeft: paymentsLeft,
            original: original, startDay: Int32(startDay), dueDay: dueDay, payFromId: payFromId, escrow: escrow, fee: fee,
            earlyCharge: earlyCharge, interestCategoryId: interestCategoryId, basis: basis
        )
    }

    var isNew: Bool { return stage == "NEW" }
    var isProfit: Bool { return kind == "ISLAMIC" }
    /// A flat-rate loan under way needs its payments left (or its original amount).
    var needsPaymentsLeft: Bool {
        if kind == "FLAT" { return true }
        return kind == "ISLAMIC" && contract == "MURABAHA"
    }
}

/// Which date of the loan form is being picked.
enum LoanDayTarget: String {
    case first
    case next
    case start
}

/// The fields of the loan setup form, shared by Add account (Loan or mortgage) and a loan's "Add terms". Works on [draft]; [problems]
/// are the sentences from `LoanBridge.problems`, shown under a field once it is touched or after the first try. The pay-from and date
/// pickers are the caller's (they replace the form), reached through [onPickPayFrom] and [onPickDay].
struct LoanFormBody: View {
    @Binding var draft: LoanDraft
    let problems: [String: String]
    let submitted: Bool
    let preview: LoanPreviewData?
    let today: Int
    let payFromName: String
    let payFromType: String
    let onPickPayFrom: () -> Void
    let onPickDay: (LoanDayTarget) -> Void

    @State private var moreOpen = false

    private func shown(_ field: String, _ text: String) -> String? {
        if submitted || !text.isEmpty { return problems[field] }
        return nil
    }

    var body: some View {
        VStack(spacing: 12) {
            if !draft.locked {
                stageChips
            }
            kindChips
            if draft.isNew {
                newFields
            } else {
                partwayFields
            }
            moreSection
            previewLines
        }
    }

    // MARK: Stage and kind

    private var stageChips: some View {
        HStack(spacing: 8) {
            FilterChip(title: String(localized: "loan.stage.new"), selected: draft.isNew, action: { draft.stage = "NEW" })
            FilterChip(title: String(localized: "loan.stage.partway"), selected: !draft.isNew, action: { draft.stage = "PARTWAY" })
            Spacer(minLength: 0)
        }
    }

    private var kindChips: some View {
        let kinds: [String] = LoanBridge.shared.kinds()
        let contracts: [String] = LoanBridge.shared.contracts()
        return VStack(alignment: .leading, spacing: 6) {
            Text("loan.interestType").font(.caption).foregroundColor(Palette.ink2)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(kinds, id: \.self) { key in
                        FilterChip(title: Wording.loanKindTitle(key), selected: draft.kind == key, action: { draft.kind = key })
                    }
                }
            }
            Text(Wording.loanKindHint(draft.kind)).font(.footnote).foregroundColor(Palette.ink2)
            if draft.kind == "ISLAMIC" {
                HStack(spacing: 8) {
                    ForEach(contracts, id: \.self) { key in
                        FilterChip(title: Wording.loanContractTitle(key), selected: draft.contract == key, action: { draft.contract = key })
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Fields

    private func amountField(_ label: LocalizedStringKey, _ field: String, _ text: Binding<String>) -> some View {
        return FormField(label: label, problem: shown(field, text.wrappedValue)) {
            TextField(label, text: filtered(text, { FieldBridge.shared.typedAmount(raw: $0) }))
                .keyboardType(.decimalPad)
        }
    }

    private func rateField(_ label: String) -> some View {
        return FormField(label: LocalizedStringKey(label), problem: shown("RATE", draft.rate)) {
            TextField(label, text: filtered($draft.rate, { FieldBridge.shared.typedRate(raw: $0) }))
                .keyboardType(.decimalPad)
        }
    }

    private func digitsField(_ label: LocalizedStringKey, _ field: String, _ text: Binding<String>, _ max: Int32) -> some View {
        return FormField(label: label, problem: shown(field, text.wrappedValue)) {
            TextField(label, text: filtered(text, { FieldBridge.shared.typedDigits(raw: $0, max: max) }))
                .keyboardType(.numberPad)
        }
    }

    private func filtered(_ text: Binding<String>, _ filter: @escaping (String) -> String) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in text.wrappedValue = filter(raw) }
        )
    }

    private func dayTile(_ label: LocalizedStringKey, _ day: Int, _ target: LoanDayTarget) -> some View {
        let value: String = day > 0 ? KassaDates.shortDateYear(epochDay: day) : ""
        return EntryTile(label: label, value: value, selected: false, action: { onPickDay(target) }) {
            Image(systemName: "calendar").frame(width: 32, height: 32).foregroundColor(Palette.ink2)
        }
    }

    @ViewBuilder
    private var newFields: some View {
        amountField("loan.amountBorrowed", "AMOUNT", $draft.amount)
        rateField(Wording.rateLabel(draft.isProfit))
        digitsField("loan.termMonthsField", "TERM", $draft.termMonths, 3)
        dayTile("loan.firstPayment", draft.firstDay, .first)
        amountField("loan.paymentOptional", "PAYMENT", $draft.payment)
    }

    @ViewBuilder
    private var partwayFields: some View {
        amountField("loan.stillOwed", "BALANCE", $draft.balance)
        rateField(Wording.rateLabel(draft.isProfit))
        amountField("loan.paymentField", "PAYMENT", $draft.payment)
        dayTile("loan.nextPayment", draft.nextDay, .next)
        if draft.needsPaymentsLeft {
            digitsField("loan.paymentsLeft", "PAYMENTS_LEFT", $draft.paymentsLeft, 3)
        }
        amountField("loan.originalOptional", "ORIGINAL", $draft.original)
        if !draft.original.isEmpty {
            dayTile("loan.startedOn", draft.startDay, .start)
        }
    }

    // MARK: More

    private var moreSection: some View {
        VStack(spacing: 12) {
            Button(action: { moreOpen.toggle() }) {
                HStack {
                    Text("loan.more").font(.subheadline.weight(.semibold)).foregroundColor(Palette.ink)
                    Spacer()
                    Image(systemName: moreOpen ? "chevron.up" : "chevron.down").font(.footnote).foregroundColor(Palette.ink2)
                }
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if moreOpen {
                digitsField("loan.dueDayField", "DUE_DAY", $draft.dueDay, 2)
                EntryTile(label: "loan.payFrom", value: payFromName, selected: false, action: onPickPayFrom) {
                    if draft.payFromId > 0 {
                        AccountIconView(type: payFromType, size: 32)
                    } else {
                        EmptyTileIcon()
                    }
                }
                amountField("loan.escrow", "ESCROW", $draft.escrow)
                if !draft.escrow.isEmpty {
                    basisChips
                }
                amountField("loan.fee", "FEE", $draft.fee)
                rateEarly
            }
        }
    }

    private var basisChips: some View {
        let bases: [String] = LoanBridge.shared.bases()
        return HStack(spacing: 8) {
            ForEach(bases, id: \.self) { key in
                FilterChip(title: Wording.loanBasisTitle(key), selected: draft.basis == key, action: { draft.basis = key })
            }
            Spacer(minLength: 0)
        }
    }

    private var rateEarly: some View {
        return FormField(label: "loan.earlyCharge", problem: shown("EARLY_CHARGE", draft.earlyCharge)) {
            TextField("loan.earlyCharge", text: filtered($draft.earlyCharge, { FieldBridge.shared.typedRate(raw: $0) }))
                .keyboardType(.decimalPad)
        }
    }

    // MARK: Live calculation

    @ViewBuilder
    private var previewLines: some View {
        if let data = preview, data.shown {
            VStack(alignment: .leading, spacing: 6) {
                if draft.isNew {
                    previewLine("loan.preview.payment", data.paymentText)
                    previewLine("loan.preview.totalInterest", data.totalInterestText)
                } else {
                    if data.paymentsLeft >= 0 {
                        previewLine("loan.preview.paymentsLeft", String(data.paymentsLeft))
                    }
                    if data.payoffDay > 0 {
                        previewLine("loan.preview.paidOff", KassaDates.shortDateYear(epochDay: Int(data.payoffDay)))
                    }
                    if !data.interestLeftText.isEmpty {
                        previewLine("loan.preview.interestLeft", data.interestLeftText)
                    }
                }
                if !data.warning.isEmpty {
                    Text(data.warning).font(.footnote).foregroundColor(Palette.orange)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
        }
    }

    private func previewLine(_ label: LocalizedStringKey, _ value: String) -> some View {
        return HStack {
            Text(label).font(.subheadline).foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Text(value).font(.subheadline.weight(.semibold)).monospacedDigit().foregroundColor(Palette.ink)
        }
    }

    // MARK: Dates

    /// The day the loan form's [target] holds.
    static func day(_ draft: LoanDraft, _ target: LoanDayTarget) -> Int {
        switch target {
        case .first: return draft.firstDay
        case .next: return draft.nextDay
        case .start: return draft.startDay > 0 ? draft.startDay : KassaDates.todayEpochDay()
        }
    }

    /// A binding to the date of [target] for `DayPickerStage`.
    static func dayBinding(_ draft: Binding<LoanDraft>, _ target: LoanDayTarget) -> Binding<Int> {
        return Binding<Int>(
            get: { LoanFormBody.day(draft.wrappedValue, target) },
            set: { day in
                switch target {
                case .first: draft.wrappedValue.firstDay = day
                case .next: draft.wrappedValue.nextDay = day
                case .start: draft.wrappedValue.startDay = day
                }
            }
        )
    }
}
