import SwiftUI
import KassaShared

/// The pages opened over the tabs (batch J): the spending page of a month or week, a day, a store, and the detail page behind a
/// Patterns or week tile. Functional first; every figure is the shared rules'.

/// A page's top: a back button, its title and the eye.
struct ReadTop: View {
    let title: String
    let hidden: Bool
    let onBack: () -> Void
    let onToggleHidden: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(title)
                .font(.title2.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer(minLength: 8)
            EyeButton(hidden: hidden, onToggle: onToggleHidden)
        }
        .padding(.top, 8)
    }
}

/// The page on top of the read stack, over the whole tab view, with its own scroll.
struct ReadHost: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollView {
            if let page = model.readPage, page.route == model.readStack.last {
                ReadPageContent(
                    page: page, hidden: model.hideAmounts, today: model.today,
                    onBack: { model.closeRead() },
                    onToggleHidden: { model.toggleHidden() },
                    onShift: { periods in model.shiftSpending(periods) },
                    onOpenCategory: { id, back in model.openCategory(id, monthsBack: back) },
                    onOpenRead: { route in model.openRead(route) },
                    onOpenEntry: { row in model.openEntry(id: row.id) }
                )
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            } else {
                ProgressView().padding(.top, 80)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// Picks the layout of the open page.
struct ReadPageContent: View {
    let page: ReadPage
    let hidden: Bool
    let today: Int
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    let onOpenCategory: (Int64, Int) -> Void
    let onOpenRead: (ReadRoute) -> Void
    let onOpenEntry: (EntryRowData) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let spending = page.spending {
                SpendingPageContent(
                    data: spending, hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden, onShift: onShift,
                    onOpenCategory: onOpenCategory
                )
            } else if let day = page.day {
                DayPageContent(
                    data: day, hidden: hidden, today: today, onBack: onBack, onToggleHidden: onToggleHidden, onOpen: onOpenEntry
                )
            } else if let store = page.store {
                StorePageContent(
                    data: store, hidden: hidden, today: today, onBack: onBack, onToggleHidden: onToggleHidden, onOpen: onOpenEntry
                )
            } else if let detail = page.detail {
                DetailPageContent(
                    data: detail, hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden, onOpenCategory: onOpenCategory,
                    onOpenRead: onOpenRead, onOpenEntry: onOpenEntry
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 48)
    }
}

// MARK: Spending

struct SpendingPageContent: View {
    let data: SpendingPageData
    let hidden: Bool
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    let onOpenCategory: (Int64, Int) -> Void

    private var title: String {
        if data.income { return String(localized: "activity.income") }
        return String(localized: "activity.spending")
    }

    private var label: String {
        if data.month { return KassaDates.monthYear(year: Int(data.periodYear), month: Int(data.periodMonth)) }
        let from = KassaDates.shortDate(epochDay: Int(data.fromDay))
        let to = KassaDates.shortDate(epochDay: Int(data.toDay))
        return String(format: String(localized: "insights.range"), from, to)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: title, hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            MonthSwitcher(label: label, canForward: data.back > 0, canBack: data.canBack, onShift: onShift)
            VStack(alignment: .leading, spacing: 8) {
                Text(data.totalText)
                    .font(.largeTitle.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(Palette.figure(data.tone))
                Text(String(format: String(localized: "insights.entryCount"), Int(data.entryCount)))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20).fill(Palette.heroTint(data.tone)))
            ReadHeader(title: "insights.byCategory")
            if data.lines.isEmpty && !hidden {
                Text("insights.noSpending").font(.footnote).foregroundColor(Palette.ink2)
            }
            ForEach(data.lines, id: \.id) { line in
                Button(action: { onOpenCategory(line.id, Int(data.categoryBack)) }) { CatLineRow(line: line) }
                    .buttonStyle(.plain)
                Hairline()
            }
        }
    }
}

// MARK: Day and store

struct DayPageContent: View {
    let data: DayPageData
    let hidden: Bool
    let today: Int
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    let onOpen: (EntryRowData) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(
                title: Wording.dayLabel(epochDay: Int(data.epochDay), today: today), hidden: hidden, onBack: onBack,
                onToggleHidden: onToggleHidden
            )
            HStack {
                Text("insights.spentThatDay").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(data.spentText).font(.title3.weight(.semibold)).monospacedDigit().lineLimit(1)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(Palette.chip))
            if data.entries.isEmpty {
                Text("activity.noneThatDay").font(.body).foregroundColor(Palette.ink2).padding(.vertical, 24)
            }
            ForEach(data.entries, id: \.id) { entry in
                EntryRowView(entry: entry, showDivider: true, onOpen: onOpen)
            }
        }
    }
}

struct StorePageContent: View {
    let data: StorePageData
    let hidden: Bool
    let today: Int
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    let onOpen: (EntryRowData) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: data.name, hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            HStack {
                Text("insights.figure.thisMonth").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(data.monthText).font(.title3.weight(.semibold)).monospacedDigit().lineLimit(1)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(Palette.chip))
            ForEach(data.days, id: \.epochDay) { day in
                DaySection(day: day, today: today, onOpen: onOpen)
            }
        }
    }
}

// MARK: Detail

struct DetailPageContent: View {
    let data: InsightDetailData
    let hidden: Bool
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    let onOpenCategory: (Int64, Int) -> Void
    let onOpenRead: (ReadRoute) -> Void
    let onOpenEntry: (EntryRowData) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: ReadWording.detailTitle(data.kind), hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            ForEach(Array(data.figures.enumerated()), id: \.offset) { pair in
                figure(pair.element)
            }
            barsRow
            ranks
            creeps
            repeats
            weekdays
            dues
            entries
        }
    }

    private func figure(_ item: FigureData) -> some View {
        return HStack {
            Text(ReadWording.figureLabel(item.kind)).font(.body)
            Spacer(minLength: 8)
            Text(item.text).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 48)
    }

    @ViewBuilder
    private var barsRow: some View {
        if !data.bars.isEmpty {
            BarsWithLabels(bars: data.bars)
        }
    }

    @ViewBuilder
    private var ranks: some View {
        ForEach(data.ranks, id: \.key) { rank in
            Button(action: { openRank(rank) }) { RankRow(rank: rank) }
                .buttonStyle(.plain)
            Hairline()
        }
    }

    private func openRank(_ rank: RankRowData) {
        if data.kind == "STORES" {
            onOpenRead(ReadRoute.store(rank.key))
        } else {
            onOpenCategory(rank.categoryId, 0)
        }
    }

    @ViewBuilder
    private var creeps: some View {
        ForEach(data.creeps, id: \.categoryId) { creep in
            Button(action: { onOpenCategory(creep.categoryId, 0) }) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(creep.name).font(.body).lineLimit(1)
                        Spacer(minLength: 8)
                        Text(creep.riseText).font(.body.weight(.semibold)).monospacedDigit().foregroundColor(Palette.orange).lineLimit(1)
                    }
                    BarsWithLabels(bars: creep.bars)
                }
                .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            Hairline()
        }
    }

    @ViewBuilder
    private var repeats: some View {
        ForEach(data.repeats, id: \.cadence) { group in
            ReadHeader(title: LocalizedStringKey(group.label))
            ForEach(group.rows, id: \.name) { row in
                Button(action: { onOpenRead(ReadRoute.store(row.name)) }) { RepeatRow(row: row) }
                    .buttonStyle(.plain)
                Hairline()
            }
        }
    }

    @ViewBuilder
    private var weekdays: some View {
        ForEach(data.weekdays, id: \.weekday) { row in
            HStack {
                Text(ReadWording.weekdayName(Int(row.weekday))).font(.body)
                Text(row.topName).font(.footnote).foregroundColor(Palette.ink2).lineLimit(1)
                Spacer(minLength: 8)
                Text(row.valueText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
            }
            .padding(.vertical, 8)
            .frame(minHeight: 48)
            Hairline()
        }
    }

    @ViewBuilder
    private var dues: some View {
        ForEach(data.dues, id: \.accountId) { due in
            HStack {
                Text(due.name).font(.body).lineLimit(1)
                Text(KassaDates.shortDate(epochDay: Int(due.dueDay))).font(.footnote).foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text(due.payableText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
            }
            .padding(.vertical, 8)
            .frame(minHeight: 48)
            Hairline()
        }
    }

    @ViewBuilder
    private var entries: some View {
        ForEach(data.entries, id: \.entry.id) { row in
            EntryRowView(entry: row.entry, showDivider: true, onOpen: onOpenEntry)
        }
    }
}

struct RankRow: View {
    let rank: RankRowData

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(rank.name).font(.body).lineLimit(1)
                Spacer(minLength: 8)
                Text(rank.valueText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
            }
            ShareBar(permille: Int(rank.permille), color: rank.color == 0 ? Palette.ink2 : Color(argb: rank.color), over: false)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct RepeatRow: View {
    let row: RepeatRowData

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(row.name).font(.body).lineLimit(1)
                Spacer(minLength: 8)
                Text(row.typicalText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(Palette.figure(row.typicalTone))
                    .lineLimit(1)
            }
            HStack(spacing: 8) {
                Text(KassaDates.shortDate(epochDay: Int(row.nextDay))).font(.footnote).foregroundColor(Palette.ink2)
                if row.tag == "CHANGE" {
                    Text(arrow + row.tagText)
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundColor(Palette.figure(row.tagTone))
                }
                Spacer(minLength: 8)
                Text(row.perYearText).font(.footnote).monospacedDigit().foregroundColor(Palette.ink2).lineLimit(1)
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// The arrow is the shared figure's (none while hidden).
    private var arrow: String {
        if row.tagArrow > 0 { return "\u{2191} " }
        if row.tagArrow < 0 { return "\u{2193} " }
        return ""
    }
}

/// Bars with a label under each (a month or a weekday).
struct BarsWithLabels: View {
    let bars: [BarData]

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(Array(bars.enumerated()), id: \.offset) { pair in
                column(pair.element)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func label(_ bar: BarData) -> String {
        if bar.labelKind == "WEEKDAY" { return ReadWording.weekdayLetter(Int(bar.labelValue)) }
        let month = Int(bar.labelValue) % 100
        let symbols = Calendar.current.shortMonthSymbols
        if month < 1 || month > symbols.count { return "" }
        return symbols[month - 1]
    }

    private func column(_ bar: BarData) -> some View {
        return VStack(spacing: 4) {
            Text(bar.valueText).font(.caption2).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            Capsule()
                .fill(bar.highlight ? Palette.ink : Palette.ink2.opacity(0.5))
                .frame(width: 12, height: max(4, 48 * CGFloat(bar.permille) / 1000.0))
            Text(label(bar)).font(.caption2).foregroundColor(Palette.ink2).lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .bottom)
        .accessibilityElement(children: .combine)
    }
}
