import SwiftUI
import KassaShared

/// The Overview: spending or income of the current month with its three largest categories, then the pinned accounts. Read-only.
/// Every figure, order and colour comes from the shared rules (`LedgerView.overview`); this only lays them out.
struct OverviewScreen: View {
    let data: OverviewData
    let hidden: Bool
    let onToggleHidden: () -> Void
    /// Opens the Accounts tab.
    let onAllAccounts: () -> Void

    var body: some View {
        ScrollView {
            OverviewContent(data: data, hidden: hidden, onToggleHidden: onToggleHidden, onAllAccounts: onAllAccounts)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The screen without its scroll view, so a screenshot can draw it.
struct OverviewContent: View {
    let data: OverviewData
    let hidden: Bool
    let onToggleHidden: () -> Void
    let onAllAccounts: () -> Void

    /// Which flow the hero shows. Spending first, as on Android.
    @State private var showIncome = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TabTop(title: "tab.overview", hidden: hidden, onToggle: onToggleHidden)
            hero
            topRows
            accountsHeader
            pinRows
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    private var flow: OverviewFlowData {
        return showIncome ? data.income : data.spending
    }

    // MARK: Hero

    private var hero: some View {
        let tone: Tone = showIncome ? data.income.tone : data.spending.tone
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                flowTab("overview.spending", selected: !showIncome, income: false)
                flowTab("overview.income", selected: showIncome, income: true)
                Spacer(minLength: 8)
                Text(KassaDates.monthYear(year: Int(data.year), month: Int(data.month)))
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            Text(flow.totalText)
                .font(.largeTitle.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .foregroundColor(Palette.figure(tone))
            if flow.top.isEmpty {
                Text(showIncome ? "overview.noIncome" : "overview.noSpending")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.heroTint(tone)))
    }

    private func flowTab(_ title: LocalizedStringKey, selected: Bool, income: Bool) -> some View {
        Button(action: { showIncome = income }) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(selected ? Palette.ink : Palette.ink2)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Top categories

    @ViewBuilder
    private var topRows: some View {
        ForEach(flow.top, id: \.id) { item in
            TopCategoryRow(item: item)
        }
    }

    // MARK: Accounts

    private var accountsHeader: some View {
        HStack {
            Text("overview.accounts")
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Button(action: onAllAccounts) {
                Text("overview.allAccounts")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 14)
        .overlay(alignment: .bottom) { Hairline() }
    }

    @ViewBuilder
    private var pinRows: some View {
        if data.pins.isEmpty {
            Text("overview.noAccounts")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .padding(.vertical, 12)
        } else {
            ForEach(Array(data.pins.enumerated()), id: \.offset) { entry in
                PinRowView(pin: entry.element)
                Hairline()
            }
        }
    }
}

/// One of the top categories: its round badge, name and amount.
struct TopCategoryRow: View {
    let item: TopCategoryData

    var body: some View {
        let name = item.name.isEmpty ? String(localized: "entry.uncategorized") : item.name
        let choice = CategoryChoiceData(
            id: item.id, name: name, parentName: "", iconKind: item.iconKind, iconValue: item.iconValue,
            color: item.color, hasSubcategories: false
        )
        return HStack(spacing: 12) {
            CategoryIconView(choice: choice, size: 32)
            Text(name)
                .font(.body)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(item.amountText)
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
        }
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }
}

/// A pinned account (or People): the name and its one main figure, with what is due on a card under it.
struct PinRowView: View {
    let pin: PinRowData

    private var title: String {
        return pin.isPeople ? String(localized: "accounts.group.people") : pin.name
    }

    private var dueLine: String? {
        if pin.dueKind.name == "Overdue" {
            return String(format: String(localized: "overview.cardOverdue"), pin.dueAmountText)
        }
        if pin.dueKind.name == "Due" {
            let when = KassaDates.shortDate(epochDay: Int(pin.dueDay))
            return String(format: String(localized: "overview.cardDue"), pin.dueAmountText, when)
        }
        return nil
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(title)
                .font(.body)
                .lineLimit(2)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(pin.amountText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(Palette.figure(pin.amountTone))
                    .lineLimit(1)
                if let line = dueLine {
                    Text(line)
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 10)
        .frame(minHeight: 48, alignment: .top)
        .accessibilityElement(children: .combine)
    }
}
