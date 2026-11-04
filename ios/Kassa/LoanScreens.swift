import SwiftUI
import KassaShared

/// A label on the left and a figure on the right, one line of a loan page's lists.
struct LoanLine: View {
    let label: String
    let value: String
    var color: Color = Palette.ink
    var detail: String = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(label)
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(value)
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundColor(color)
                    if !detail.isEmpty {
                        Text(detail)
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .lineLimit(1)
                    }
                }
            }
            .padding(.vertical, 10)
            .frame(minHeight: 48)
            Hairline()
        }
        .accessibilityElement(children: .combine)
    }
}

/// The progress of paying a loan off: [permille] of 1000 repaid. Only drawn when the original amount is known and amounts are shown.
struct LoanProgressBar: View {
    let permille: Int

    var body: some View {
        GeometryReader { box in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.ground.opacity(0.6))
                Capsule()
                    .fill(Palette.ink)
                    .frame(width: box.size.width * CGFloat(max(0, min(1000, permille))) / 1000.0)
            }
        }
        .frame(height: 8)
        .accessibilityHidden(true)
    }
}

/// A loan account's page: what is outstanding, the payment and the payoff, interest paid and left, the next payments, the terms with
/// their dated changes, and the month's entries. Every figure is :domain's (`LoanSummary`, `LoanEngine`) through `LedgerView.loanPage`;
/// this only lays them out. Recording a payment and changing terms come with the next stage. Without its scroll view, so a screenshot
/// can draw it.
struct LoanPageContent: View {
    let data: LoanPageData
    let hidden: Bool
    let today: Int
    let showAll: Bool
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    let onShift: (Int) -> Void
    let onSetClosed: (Bool, @escaping (WriteOutcome) -> Void) -> Void
    let onCorrect: () -> Void
    let onShowAll: (Bool) -> Void
    let onRemoveChange: (Int64, @escaping (WriteOutcome) -> Void) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil
    var onRecord: () -> Void = {}
    var onChangeTerms: () -> Void = {}
    var onEditDetails: () -> Void = {}

    @State private var removing: LoanChangeData?
    @State private var removeFailure: String?

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack)
            hero
            paymentLines
            scheduleSection
            termsSection
            actions
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            entriesSection
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
        .alert(item: $removing) { change in
            Alert(
                title: Text("loan.removeChangeTitle"),
                message: Text(change.summary),
                primaryButton: .destructive(Text("loan.removeChange")) { removeChange(change) },
                secondaryButton: .cancel(Text("loan.keepChange"))
            )
        }
    }

    // MARK: Hero

    private var hero: some View {
        PageHero(tint: Palette.heroTint(data.heroTone)) {
            HeroCaption(text: String(localized: "loan.outstanding"))
            HeroFigure(text: data.balanceText, color: Palette.figure(data.balanceTone))
            if data.hasProgress {
                LoanProgressBar(permille: Int(data.permille))
                Text(String(format: String(localized: "loan.repaidOf"), Int(data.percent), data.originalText))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    // MARK: Payment

    @ViewBuilder
    private var paymentLines: some View {
        VStack(spacing: 0) {
            if data.paying {
                let paymentLabel: String = data.hasEscrow ? String(localized: "loan.paymentWithEscrow") : String(localized: "loan.payment")
                LoanLine(
                    label: paymentLabel, value: data.paymentText, detail: dueDetail
                )
                LoanLine(label: String(localized: "loan.paidOff"), value: Wording.payoffLine(day: Int(data.payoffDay)))
            }
            LoanLine(label: Wording.interestPaidLabel(data.profit), value: data.interestPaidText)
            if data.paying {
                LoanLine(label: Wording.interestLeftLabel(data.profit), value: data.interestLeftText)
            }
        }
        if data.doesntCover {
            Text("loan.doesntCover")
                .font(.footnote)
                .foregroundColor(Palette.orange)
        }
    }

    private var dueDetail: String {
        if data.nextPaymentDay <= 0 { return "" }
        return Wording.dueLine(day: Int(data.nextPaymentDay))
    }

    // MARK: Schedule

    @ViewBuilder
    private var scheduleSection: some View {
        if !data.rows.isEmpty {
            GroupLabel(text: String(localized: "loan.schedule"))
            VStack(spacing: 0) {
                ForEach(data.rows, id: \.day) { row in
                    scheduleRow(row)
                }
            }
            if Int(data.scheduleCount) > data.rows.count || showAll {
                Button(action: { onShowAll(!showAll) }) {
                    Text(showAll ? "loan.showFewer" : "loan.showFull")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
            }
        }
    }

    private func scheduleRow(_ row: LoanRowData) -> some View {
        return VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(KassaDates.shortDateYear(epochDay: Int(row.day)))
                    .font(.subheadline)
                    .lineLimit(1)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(row.principalText)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                    Text(String(format: String(localized: "loan.rowInterest"), row.interestText, row.balanceText))
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .padding(.vertical, 8)
            .frame(minHeight: 48)
            Hairline()
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Terms

    private var termsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            GroupLabel(text: String(localized: "loan.terms"))
            LoanLine(label: String(localized: "loan.interestType"), value: Wording.loanTypeTitle(data.interestType))
            LoanLine(label: Wording.rateLabel(data.profit), value: data.rateText)
            LoanLine(
                label: data.hasEscrow ? String(localized: "loan.principalAndInterest") : String(localized: "loan.payment"),
                value: data.termPaymentText
            )
            if data.hasEscrow {
                LoanLine(label: String(localized: "loan.escrow"), value: data.escrowText)
            }
            if data.termMonths > 0 {
                LoanLine(label: String(localized: "loan.term"), value: Wording.termMonthsLine(Int(data.termMonths)))
            }
            if data.dueDay > 0 {
                LoanLine(label: String(localized: "loan.dueDayField"), value: Wording.dueDayLine(Int(data.dueDay)))
            }
            ForEach(data.changes, id: \.id) { change in
                changeRow(change)
            }
            if let text = removeFailure {
                Text(text).font(.footnote).foregroundColor(Palette.orange)
            }
        }
    }

    private func changeRow(_ change: LoanChangeData) -> some View {
        return Button(action: { removing = change }) {
            LoanLine(
                label: String(format: String(localized: "loan.changeFrom"), KassaDates.shortDateYear(epochDay: Int(change.day))),
                value: change.summary
            )
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("loan.removeChangeHint"))
    }

    private func removeChange(_ change: LoanChangeData) {
        onRemoveChange(change.id) { outcome in
            switch outcome {
            case .saved:
                removeFailure = nil
            case .refused(_, let text):
                removeFailure = text
            case .failed(let text):
                removeFailure = text
            }
        }
    }

    // MARK: Actions and entries

    private var actions: some View {
        VStack(spacing: 8) {
            actionButton("loan.recordPayment", filled: true, action: onRecord)
            actionButton("loan.changeTerms", filled: false, action: onChangeTerms)
            actionButton("loan.editDetails", filled: false, action: onEditDetails)
            actionButton("correction.open", filled: false, action: onCorrect)
        }
    }

    private func actionButton(_ key: LocalizedStringKey, filled: Bool, action: @escaping () -> Void) -> some View {
        return Button(action: action) {
            Text(key)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(InkButtonStyle(filled: filled))
    }

    @ViewBuilder
    private var entriesSection: some View {
        MonthSwitcher(
            label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
            canForward: !data.isCurrentMonth, canBack: data.hasEarlier, onShift: onShift
        )
        if data.entryCount == 0 {
            Text("accountPage.nothingThisMonth")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(data.days, id: \.epochDay) { day in
                    DaySection(day: day, today: today, onOpen: onOpen)
                }
            }
        }
    }
}

extension LoanChangeData: Identifiable {}
