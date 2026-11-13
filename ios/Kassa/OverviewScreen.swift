import SwiftUI
import KassaShared

/// The Overview: spending or income of the current month (or this week, from the phone's first day of the week) with its three largest
/// categories, then the pinned accounts. Read-only.
/// Every figure, order and colour comes from the shared rules (`LedgerView.overview`); this only lays them out.
struct OverviewScreen: View {
    let data: OverviewData
    let hidden: Bool
    let onToggleHidden: () -> Void
    /// Opens the Accounts tab.
    let onAllAccounts: () -> Void
    /// The due notices and Upcoming rows (batch I); empty by default, so a screen that has none (a screenshot) still compiles.
    var notices: [NoticeData] = []
    var upcoming: [UpcomingData] = []
    var onOpenNotices: () -> Void = {}
    var onOpenCard: (Int64) -> Void = { _ in }
    /// Batch J: a top category opens Insights' category page; the total opens the spending (or income) page: income first, then month.
    var onOpenCategory: (Int64, Int) -> Void = { _, _ in }
    var onOpenSpending: (Bool, Bool) -> Void = { _, _ in }
    /// Coach's "Your week in 3 points" card: its first line (nil keeps the card away) and what a tap does.
    var coachLine: String? = nil
    var onOpenCoach: () -> Void = {}

    var body: some View {
        ScrollView {
            OverviewContent(
                data: data, hidden: hidden, onToggleHidden: onToggleHidden, onAllAccounts: onAllAccounts,
                notices: notices, upcoming: upcoming, onOpenNotices: onOpenNotices, onOpenCard: onOpenCard,
                onOpenCategory: onOpenCategory, onOpenSpending: onOpenSpending, coachLine: coachLine, onOpenCoach: onOpenCoach
            )
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
    /// The due notices and Upcoming rows (batch I); empty by default, so a screen that has none (a screenshot) still compiles.
    var notices: [NoticeData] = []
    var upcoming: [UpcomingData] = []
    var onOpenNotices: () -> Void = {}
    var onOpenCard: (Int64) -> Void = { _ in }
    var onOpenCategory: (Int64, Int) -> Void = { _, _ in }
    var onOpenSpending: (Bool, Bool) -> Void = { _, _ in }
    var coachLine: String? = nil
    var onOpenCoach: () -> Void = {}

    /// Which flow the hero shows. Spending first, as on Android.
    @State private var showIncome = false
    /// Month first, as on Android: it is what budgets are measured in. The menu in the hero picks this week.
    @State private var showWeek = false
    @State private var choosingPeriod = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            TabTop(title: "tab.overview", hidden: hidden, onToggle: onToggleHidden)
            if !notices.isEmpty {
                WaitingPill(count: notices.count, onOpen: onOpenNotices)
            }
            VStack(alignment: .leading, spacing: 8) {
                hero
                if !flow.top.isEmpty {
                    CategoryTiles(items: flow.top, onTap: tileTap)
                        .accessibilityIdentifier("topCategoryTiles")
                }
            }
            if let line = coachLine {
                CoachTeaserCard(line: line, onOpen: onOpenCoach)
            }
            UpcomingSection(rows: upcoming, hidden: hidden, onOpenCard: onOpenCard, onSeeAll: notices.isEmpty ? nil : onOpenNotices)
            accountsHeader
            pinRows
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 96)
    }

    private var flow: OverviewFlowData {
        if showWeek { return showIncome ? data.weekIncome : data.weekSpending }
        return showIncome ? data.income : data.spending
    }

    /// How many months back a category opens on: this period from the month view, the month the week starts in from "This week"
    /// (the rule is :shared's `weekCategoryBack`, as the spending page's).
    private var categoryBack: Int {
        return showWeek ? Int(data.weekCategoryBack) : 0
    }

    /// A spending tile opens the category's page; income tiles only show (as the legend before them).
    private var tileTap: ((TopCategoryData) -> Void)? {
        if showIncome { return nil }
        let back: Int = categoryBack
        return { item in onOpenCategory(item.id, back) }
    }

    private var monthTitle: String {
        return KassaDates.monthYear(year: Int(data.year), month: Int(data.month))
    }

    private var periodTitle: String {
        return showWeek ? String(localized: "overview.thisWeek") : KassaDates.monthName(year: Int(data.year), month: Int(data.month))
    }

    // MARK: Hero

    private var hero: some View {
        let tone: Tone = flow.tone
        let inkColor: Color = hidden ? Palette.heroGreyInk : Palette.heroInk(tone)
        return TonalHero(tone: tone, hidden: hidden) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 16) {
                    heroTabs
                    Spacer(minLength: 8)
                    periodButton
                }
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .top, spacing: 16) { heroTabs }
                    periodButton
                }
            }
            Button(action: { onOpenSpending(showIncome, !showWeek) }) {
                AmountText(text: flow.totalText, hidden: hidden, size: 52, color: Palette.ink, centsColor: hidden ? Palette.ink : Palette.heroInk(tone))
                    .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
            if flow.top.isEmpty {
                Text(showIncome ? "overview.noIncome" : "overview.noSpending")
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(inkColor)
            }
        }
    }

    @ViewBuilder
    private var heroTabs: some View {
        HeroTab(title: "overview.spending", selected: !showIncome, inkColor: heroInk, action: { showIncome = false })
        HeroTab(title: "overview.income", selected: showIncome, inkColor: heroInk, action: { showIncome = true })
    }

    private var heroInk: Color {
        return hidden ? Palette.heroGreyInk : Palette.heroInk(flow.tone)
    }

    private var periodButton: some View {
        return Button(action: { choosingPeriod = true }) {
            HStack(spacing: 6) {
                Text(periodTitle)
                    .font(Font.kassa(15, .semibold, relativeTo: .subheadline))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                Image(systemName: "chevron.down")
                    .font(.system(size: 12, weight: .semibold))
            }
            .foregroundColor(Palette.ink)
            .padding(.horizontal, 10)
            .frame(minHeight: 48)
            .background(Capsule().fill(Palette.raised.opacity(0.6)))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("overview.choosePeriod"))
        .confirmationDialog(Text("overview.choosePeriod"), isPresented: $choosingPeriod, titleVisibility: .visible) {
            Button(monthTitle) { showWeek = false }
            Button("overview.thisWeek") { showWeek = true }
        }
    }

    // MARK: Accounts

    private var accountsHeader: some View {
        return SectionLabel(title: "overview.accounts", link: "overview.allAccounts", onLink: onAllAccounts)
    }

    private var pinPairs: [[PinRowData]] {
        var out: [[PinRowData]] = []
        var index: Int = 0
        while index < data.pins.count {
            var pair: [PinRowData] = [data.pins[index]]
            if index + 1 < data.pins.count { pair.append(data.pins[index + 1]) }
            out.append(pair)
            index += 2
        }
        return out
    }

    @ViewBuilder
    private var pinRows: some View {
        if data.pins.isEmpty {
            Text("overview.noAccounts")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .padding(.vertical, 12)
        } else {
            ForEach(0..<pinPairs.count, id: \.self) { row in
                HStack(alignment: .top, spacing: 32) {
                    AccountCell(pin: pinPairs[row][0], hidden: hidden, onTap: onAllAccounts)
                    if pinPairs[row].count > 1 {
                        AccountCell(pin: pinPairs[row][1], hidden: hidden, onTap: onAllAccounts)
                    } else {
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
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
