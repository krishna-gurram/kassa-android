import SwiftUI
import KassaShared

/// An investment account's page (batch H, functional first; the look comes with the full revamp): total value with the gain on what was put
/// in, the cash, the holdings by value, transfers waiting to be explained, and every cash move by month with the cash right after it. All
/// figures, words' parts and colours are `LedgerView.investPage`'s; while Hide balances is on there is no percent and no direction.
/// Without its scroll view, so a screenshot can draw it.
struct InvestPageContent: View {
    let data: InvestPageData
    let hidden: Bool
    let today: Int
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    let onSetClosed: (Bool, @escaping (WriteOutcome) -> Void) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil
    var onOpenHolding: (Int64) -> Void = { _ in }
    var onAddShares: () -> Void = {}
    var onUpdatePrices: () -> Void = {}
    var onTrade: () -> Void = {}
    var onAnswer: (Int64) -> Void = { _ in }
    var onClear: (Int64, @escaping (WriteOutcome) -> Void) -> Void = { _, _ in }
    /// "Edit": name, last four digits and cash balance (batch Q).
    var onEdit: (() -> Void)? = nil

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack, onEdit: onEdit)
            hero
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            waitingSection
            holdingsSection
            history
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    // MARK: Hero

    private var hero: some View {
        PageHero(tint: Palette.chip) {
            HeroCaption(text: String(localized: "accountPage.totalValue"))
            HeroFigure(text: data.totalValueText, color: Palette.figure(data.totalValueTone))
            gainLine
            HStack(spacing: 8) {
                firstBox
                FigureBox(label: "invest.cash", value: data.cashText, color: Palette.ink)
            }
            HStack(spacing: 8) {
                primaryButton
                Button(action: onTrade) { Text("invest.buyOrSell") }
                    .buttonStyle(InkButtonStyle(filled: false))
            }
        }
    }

    @ViewBuilder
    private var gainLine: some View {
        if data.gainKind != "NONE" {
            Text(gainText)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Palette.figure(data.gainTone))
                .lineLimit(2)
        }
    }

    private var gainText: String {
        if data.gainKind == "MASKED" {
            return String(format: String(localized: "invest.gainMasked"), data.gainAmountText)
        }
        if data.gainKind == "UP" {
            return String(format: String(localized: "invest.gainUp"), data.gainAmountText, data.gainPercentText)
        }
        return String(format: String(localized: "invest.gainDown"), data.gainAmountText, data.gainPercentText)
    }

    @ViewBuilder
    private var firstBox: some View {
        if data.firstBoxIsPutIn {
            FigureBox(label: "invest.youPutIn", value: data.firstBoxText, color: Palette.ink)
        } else {
            FigureBox(label: "invest.holdings", value: data.firstBoxText, color: Palette.ink)
        }
    }

    @ViewBuilder
    private var primaryButton: some View {
        if data.holdings.isEmpty {
            Button(action: onAddShares) { Text("invest.addSharesYouOwn") }
                .buttonStyle(InkButtonStyle(filled: true))
        } else {
            Button(action: onUpdatePrices) { Text("invest.updatePrices") }
                .buttonStyle(InkButtonStyle(filled: true))
        }
    }

    // MARK: Waiting transfers

    @ViewBuilder
    private var waitingSection: some View {
        if !data.waiting.isEmpty {
            GroupLabel(text: String(localized: "invest.waitingGroup"))
            ForEach(data.waiting, id: \.id) { item in
                WaitingTransferCard(item: item, onAnswer: onAnswer, onClear: onClear)
            }
        }
    }

    // MARK: Holdings

    @ViewBuilder
    private var holdingsSection: some View {
        if !data.holdings.isEmpty {
            HStack {
                Text("invest.holdingsHeader")
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(pricesNote)
                    .font(.caption)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            .padding(.top, 8)
            Hairline()
            ForEach(data.holdings, id: \.id) { holding in
                holdingRow(holding)
            }
            Button(action: onAddShares) {
                Text("invest.addSharesYouOwn")
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minHeight: 48)
            }
        }
    }

    private var pricesNote: String {
        if data.priceDays > 14 {
            return String(format: String(localized: "invest.pricesDaysOld"), Int(data.priceDays))
        }
        return String(format: String(localized: "invest.pricesFrom"), KassaDates.shortDate(epochDay: Int(data.oldestPriceDay)))
    }

    private func holdingSubtitle(_ holding: HoldingRowData) -> String {
        if holding.avgCostText.isEmpty {
            return String(format: String(localized: "invest.sharesCount"), holding.sharesText)
        }
        return String(format: String(localized: "invest.sharesAvg"), holding.sharesText, holding.avgCostText)
    }

    private func holdingRow(_ holding: HoldingRowData) -> some View {
        Button(action: { onOpenHolding(holding.id) }) {
            VStack(spacing: 0) {
                HStack(alignment: .center, spacing: 12) {
                    ZStack {
                        Circle().fill(Palette.chip)
                        Text(String(holding.ticker.prefix(2)))
                            .font(.footnote.weight(.semibold))
                    }
                    .frame(width: 36, height: 36)
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(holding.ticker)
                            .font(.body.weight(.semibold))
                            .lineLimit(1)
                        Text(holdingSubtitle(holding))
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(holding.valueText)
                            .font(.body.weight(.semibold))
                            .monospacedDigit()
                            .lineLimit(1)
                        profitLine(holding)
                    }
                    Image(systemName: "chevron.right")
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                }
                .padding(.vertical, 8)
                .frame(minHeight: 56)
                Hairline()
            }
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func profitLine(_ holding: HoldingRowData) -> some View {
        if holding.hasProfit {
            Text(holding.profitText)
                .font(.footnote)
                .monospacedDigit()
                .foregroundColor(Palette.figure(holding.profitTone))
                .lineLimit(1)
        } else {
            Text("invest.addAverageCost")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
        }
    }

    // MARK: Cash moves

    @ViewBuilder
    private var history: some View {
        if !data.hasHistory {
            Text("invest.noMoneyYet")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(data.months.enumerated()), id: \.offset) { item in
                    monthSection(item.element)
                }
            }
        }
    }

    private func monthSection(_ month: InvestMonthData) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            GroupLabel(text: String(format: String(localized: "invest.moneyInOut"), KassaDates.monthYear(year: Int(month.year), month: Int(month.month))))
            Hairline()
            ForEach(month.entries, id: \.row.id) { entry in
                EntryRowView(
                    entry: entry.row, showDivider: entry.row.id != month.entries.last?.row.id, onOpen: onOpen,
                    noteOverride: String(format: String(localized: "invest.cashAfter"), entry.cashAfterText)
                )
            }
        }
    }
}

/// A transfer into the account waiting to be explained: "Where did it go?", with Answer (the sheet) and Clear (stop asking; nothing else changes).
struct WaitingTransferCard: View {
    let item: WaitingTransferData
    let onAnswer: (Int64) -> Void
    let onClear: (Int64, @escaping (WriteOutcome) -> Void) -> Void

    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("invest.waitingTitle")
                        .font(.body.weight(.semibold))
                    Text(String(format: String(localized: "invest.waitingKicker"), KassaDates.shortDate(epochDay: Int(item.day))))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                }
                Spacer(minLength: 8)
                Text(item.amountText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            HStack(spacing: 8) {
                Button(action: { onAnswer(item.id) }) { Text("invest.answer") }
                    .buttonStyle(InkButtonStyle(filled: true))
                Button(action: clear) { Text("invest.clear") }
                    .buttonStyle(InkButtonStyle(filled: false))
            }
            if let text = message {
                Text(text)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).stroke(Palette.line, lineWidth: 1))
    }

    private func clear() {
        onClear(item.id) { outcome in
            switch outcome {
            case .saved:
                message = nil
            case .refused(_, let text):
                message = text
            case .failed(let text):
                message = text
            }
        }
    }
}
