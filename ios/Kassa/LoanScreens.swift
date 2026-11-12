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
                    .font(Font.kassa(16, .regular, relativeTo: .body))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(value)
                        .font(Font.kassa(16, .bold, relativeTo: .body).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundColor(color)
                    if !detail.isEmpty {
                        Text(detail)
                            .font(Font.kassa(13, .regular, relativeTo: .footnote))
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
                Capsule().fill(Palette.track)
                Capsule()
                    .fill(Palette.ink)
                    .frame(width: box.size.width * CGFloat(max(0, min(1000, permille))) / 1000.0)
            }
        }
        .frame(height: 10)
        .accessibilityHidden(true)
    }
}

/// A small figure under a label on a chip: Payment, Due and Paid off under a loan's hero.
struct StatTile: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
            Text(value)
                .font(Font.kassa(17, .bold, relativeTo: .body).monospacedDigit())
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Palette.chip))
        .accessibilityElement(children: .combine)
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
            tiles
            interestSection
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
        TonalHero(tone: data.heroTone, hidden: true) {
            HeroLabel(text: "loan.outstanding", tone: data.heroTone, hidden: true)
            HeroAmount(text: data.balanceText, tone: data.balanceTone, hidden: hidden, grey: true)
            if data.hasProgress {
                LoanProgressBar(permille: Int(data.permille))
                    .padding(.top, 6)
                Text(String(format: String(localized: "d6.loan.paidOf"), Int(data.percent), data.originalText))
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.heroGreyInk)
                    .padding(.top, 6)
            }
        }
    }

    // MARK: Stat tiles and interest

    @ViewBuilder
    private var tiles: some View {
        if data.paying {
            HStack(spacing: 10) {
                StatTile(label: tileLabel, value: data.paymentText)
                StatTile(label: LocalizedStringKey("d6.loan.dueLabel"), value: dueTileText)
                StatTile(label: LocalizedStringKey("loan.paidOff"), value: payoffTileText)
            }
        }
        if data.doesntCover {
            Text("loan.doesntCover")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.orange)
        }
    }

    private var tileLabel: LocalizedStringKey {
        if data.hasEscrow { return LocalizedStringKey("loan.paymentWithEscrow") }
        return LocalizedStringKey("loan.payment")
    }

    private var dueTileText: String {
        if data.nextPaymentDay <= 0 { return "\u{2013}" }
        return KassaDates.shortDate(epochDay: Int(data.nextPaymentDay))
    }

    private var payoffTileText: String {
        if data.payoffDay <= 0 { return String(localized: "loan.noPayoff") }
        let parts: (year: Int, month: Int) = KassaDates.yearMonth(epochDay: Int(data.payoffDay))
        return KassaDates.monthYearShort(year: parts.year, month: parts.month)
    }

    private var interestTitle: LocalizedStringKey {
        if data.profit { return LocalizedStringKey("loan.profit") }
        return LocalizedStringKey("loan.interest")
    }

    private var interestSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionLabel(title: interestTitle)
            Hairline()
            LoanLine(label: String(localized: "d6.loan.paidSoFar"), value: data.interestPaidText)
            if data.paying {
                LoanLine(label: String(localized: "d6.loan.stillToPay"), value: data.interestLeftText)
            }
        }
    }

    // MARK: Schedule

    private var hasMoreRows: Bool {
        return Int(data.scheduleCount) > data.rows.count || showAll
    }

    private var scheduleLink: LocalizedStringKey? {
        if !hasMoreRows { return nil }
        if showAll { return LocalizedStringKey("loan.showFewer") }
        return LocalizedStringKey("d6.loan.showFull")
    }

    @ViewBuilder
    private var scheduleSection: some View {
        if !data.rows.isEmpty {
            VStack(spacing: 0) {
                SectionLabel(title: "loan.schedule", link: scheduleLink, onLink: { onShowAll(!showAll) })
                Hairline()
            }
            VStack(spacing: 0) {
                ForEach(data.rows, id: \.day) { row in
                    scheduleRow(row)
                }
            }
        }
    }

    private func scheduleRow(_ row: LoanRowData) -> some View {
        return VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(KassaDates.shortDateYear(epochDay: Int(row.day)))
                    .font(Font.kassa(16, .regular, relativeTo: .body))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(row.principalText)
                        .font(Font.kassa(16, .bold, relativeTo: .body).monospacedDigit())
                        .lineLimit(1)
                    Text(String(format: String(localized: "loan.rowInterest"), row.interestText, row.balanceText))
                        .font(Font.kassa(12, .regular, relativeTo: .caption))
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
            SectionLabel(title: "loan.terms")
            Hairline()
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
                Text(text).font(Font.kassa(13, .regular, relativeTo: .footnote)).foregroundColor(Palette.orange)
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
        HStack(spacing: 12) {
            PagePill(title: "loan.recordPayment", filled: true, action: onRecord)
            moreMenu
        }
    }

    private var moreMenu: some View {
        Menu {
            Button(action: onChangeTerms) { Text("loan.changeTerms") }
            Button(action: onEditDetails) { Text("loan.editDetails") }
            Button(action: onCorrect) { Text("correction.open") }
        } label: {
            Text("loan.more")
                .font(Font.kassa(16, .semibold))
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .padding(.horizontal, 24)
                .frame(minHeight: 56)
                .background(Capsule().fill(Palette.chip))
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    @ViewBuilder
    private var entriesSection: some View {
        MonthSwitcher(
            label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
            canForward: !data.isCurrentMonth, canBack: data.hasEarlier, onShift: onShift
        )
        if data.entryCount == 0 {
            Text("accountPage.nothingThisMonth")
                .font(Font.kassa(15, .regular, relativeTo: .body))
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
