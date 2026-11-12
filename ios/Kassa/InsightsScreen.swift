import SwiftUI
import KassaShared

/// Insights for one budget month: the spending hero (with "Up to <date>" and the same stretch of the month before, for the current
/// month) and By category, largest first. Read-only. Every figure, share, order and colour comes from the shared rules
/// (`LedgerView.insights`); this only lays them out. Patterns tiles are not here yet.
struct InsightsScreen: View {
    let data: InsightsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    /// Months to move: -1 is the month before, 1 the month after.
    let onShift: (Int) -> Void
    let onOpenCategory: (Int64) -> Void
    /// Batch J: Patterns tiles, the week view and the pages they open (`AppModel+Read.swift`). Left out, Insights is as it was.
    var read: InsightsRead? = nil

    var body: some View {
        ScrollView {
            InsightsContent(
                data: data, hidden: hidden, onToggleHidden: onToggleHidden, onShift: onShift, onOpenCategory: onOpenCategory,
                read: read
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The screen without its scroll view, so a screenshot can draw it.
struct InsightsContent: View {
    let data: InsightsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    let onOpenCategory: (Int64) -> Void
    var read: InsightsRead? = nil

    /// By category shows the biggest few until "All N" is tapped.
    @State private var showAll: Bool = false

    private static let collapsedCount: Int = 5

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TabTop(title: "tab.insights", hidden: hidden, onToggle: onToggleHidden)
            if let extra = read {
                periodPicker(extra)
            }
            if let extra = read, extra.showWeek, let week = extra.week {
                WeekContent(
                    data: week, hidden: hidden, today: extra.today,
                    onOpenSpending: { extra.onOpenSpending(false) },
                    onOpenCategory: onOpenCategory, onOpenDetail: extra.onOpenDetail, onOpen: extra.onOpenEntry
                )
            } else {
                monthBody
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    private var monthBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            MonthSwitcher(
                label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
                canForward: !data.isCurrentMonth,
                canBack: data.hasEarlier,
                onShift: onShift
            )
            heroButton
            if let extra = read, let patterns = extra.patterns {
                PatternsSection(tiles: patterns.tiles, onOpen: extra.onOpenDetail)
            }
            sectionHeader
            lines
        }
    }

    @ViewBuilder
    private var heroButton: some View {
        if let extra = read {
            Button(action: { extra.onOpenSpending(true) }) { hero }
                .buttonStyle(.plain)
        } else {
            hero
        }
    }

    private func periodPicker(_ extra: InsightsRead) -> some View {
        let binding = Binding<Int>(get: { extra.showWeek ? 1 : 0 }, set: { index in extra.onSetWeek(index == 1) })
        let options: [LocalizedStringKey] = ["insights.month", "insights.thisWeek"]
        return SegmentedPill(options: options, selection: binding)
            .accessibilityIdentifier("insightsPeriod")
    }

    // MARK: Hero

    private var hero: some View {
        let month = KassaDates.monthYear(year: Int(data.year), month: Int(data.month))
        return SpendHero(hidden: hidden) {
            Text(String(format: String(localized: "insights.spentIn"), month))
                .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroGreenInk)
                .lineLimit(1)
            Text(data.totalText)
                .scaledNumeral(44)
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            if data.hasCompare {
                HStack(spacing: 6) {
                    Text(String(format: String(localized: "insights.upTo"), KassaDates.shortDate(epochDay: Int(data.compareDay))))
                        .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
                        .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroGreenInk)
                        .lineLimit(1)
                    Text(data.compareText)
                        .font(Font.kassa(14, .semibold, relativeTo: .subheadline).monospacedDigit())
                        .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroGreenInk)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    // MARK: By category

    private var allLink: LocalizedStringKey? {
        if data.lines.count <= InsightsContent.collapsedCount { return nil }
        if showAll { return "insights.showLess" }
        return LocalizedStringKey(String(format: String(localized: "insights.allCount"), data.lines.count))
    }

    private var sectionHeader: some View {
        return SectionLabel(title: "insights.byCategory", link: allLink, onLink: { showAll = !showAll })
            .padding(.top, 6)
            .overlay(alignment: .bottom) { Hairline() }
    }

    private var shownLines: [InsightLineData] {
        let all: [InsightLineData] = data.lines
        if showAll || all.count <= InsightsContent.collapsedCount { return all }
        return Array(all.prefix(InsightsContent.collapsedCount))
    }

    @ViewBuilder
    private var lines: some View {
        if data.lines.isEmpty {
            // While hidden the empty month says nothing: a sentence here would show that the hidden total is zero.
            if !hidden {
                Text("insights.noSpending")
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
        } else {
            ForEach(shownLines, id: \.id) { line in
                Button(action: { onOpenCategory(line.id) }) {
                    InsightLineView(line: line)
                }
                .buttonStyle(.plain)
                Hairline()
            }
        }
    }
}

/// The tonal block at the top of Insights: green, grey while amounts are hidden so the tint never gives a figure away.
struct SpendHero<Content: View>: View {
    let hidden: Bool
    let content: Content

    init(hidden: Bool, @ViewBuilder content: () -> Content) {
        self.hidden = hidden
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            content
        }
        .padding(.top, 18)
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(hidden ? Palette.heroGrey : Palette.heroGreen))
    }
}

/// What batch J adds to Insights: the Patterns, the week view, and what taps do. The app builds it from `AppModel`; a screenshot builds it
/// from a `LedgerView` and ignores the taps.
struct InsightsRead {
    let patterns: PatternsData?
    let week: WeekData?
    let showWeek: Bool
    let today: Int
    let onSetWeek: (Bool) -> Void
    /// A Patterns or week tile: the kind of detail page (`InsightDetailData.kind`).
    let onOpenDetail: (String) -> Void
    /// The hero: the spending page of the month (true) or of the week (false).
    let onOpenSpending: (Bool) -> Void
    var onOpenEntry: ((EntryRowData) -> Void)? = nil
}

/// A category line as in the design: name and amount, a coloured bar under them (orange when over budget), and a small line for the
/// share and the budget. No share or bar while hidden.
struct InsightLineView: View {
    let line: InsightLineData

    private var name: String {
        return line.name.isEmpty ? String(localized: "entry.uncategorized") : line.name
    }

    private var share: String? {
        if line.percent < 0 { return nil }
        return String(format: String(localized: "insights.percent"), Int(line.percent))
    }

    private var subLine: String {
        var parts: [String] = []
        if let text = share { parts.append(text) }
        if !line.refText.isEmpty {
            parts.append(String(format: String(localized: "insights.ofBudget"), line.refText))
        }
        return parts.joined(separator: " \u{B7} ")
    }

    private var barColor: Color {
        if line.over { return Palette.orange }
        return line.id == 0 ? Palette.ink2 : Color(argb: line.color)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(Font.kassa(17, .semibold, relativeTo: .body))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                    if !subLine.isEmpty {
                        Text(subLine)
                            .font(Font.kassa(12, .regular, relativeTo: .caption))
                            .foregroundColor(Palette.ink3)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Text(line.amountText)
                    .font(Font.kassa(17, .bold, relativeTo: .body).monospacedDigit())
                    .foregroundColor(line.over ? Palette.orange : Palette.ink)
                    .lineLimit(1)
            }
            bar
        }
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var bar: some View {
        let fraction = Double(line.barPermille) / 1000.0
        return Capsule()
            .fill(Palette.track)
            .frame(height: 8)
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule()
                        .fill(barColor)
                        .frame(width: proxy.size.width * CGFloat(fraction))
                }
            }
    }
}

/// One category in one month: its total and every entry, newest day first, rows as in Activity. Has its own back button and month
/// arrows (they move the same month Insights shows).
struct InsightsCategoryScreen: View {
    let data: CategoryPageData
    let hidden: Bool
    let today: Int
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    let onBack: () -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil

    var body: some View {
        ScrollView {
            InsightsCategoryContent(
                data: data, hidden: hidden, today: today, lazy: true,
                onToggleHidden: onToggleHidden, onShift: onShift, onBack: onBack, onOpen: onOpen
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The category page without its scroll view. [lazy] is false for a screenshot, which cannot draw lazy stacks.
struct InsightsCategoryContent: View {
    let data: CategoryPageData
    let hidden: Bool
    let today: Int
    let lazy: Bool
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    let onBack: () -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil

    private var name: String {
        return data.name.isEmpty ? String(localized: "entry.uncategorized") : data.name
    }

    private var month: String {
        return KassaDates.monthYear(year: Int(data.year), month: Int(data.month))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            top
            MonthSwitcher(label: month, canForward: !data.isCurrentMonth, canBack: data.hasEarlier, onShift: onShift)
            if data.found {
                hero
                entriesHeader
                entries
            } else {
                Text("insights.categoryGone")
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 32)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    private var top: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(name)
                .font(Font.kassa(24, .bold, relativeTo: .title2))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer(minLength: 8)
            EyeButton(hidden: hidden, onToggle: onToggleHidden)
        }
        .padding(.top, 8)
    }

    private var hero: some View {
        return VStack(alignment: .leading, spacing: 8) {
            Text(String(format: String(localized: "insights.spentIn"), month))
                .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
                .foregroundColor(Palette.heroGreyInk)
                .lineLimit(1)
            Text(data.totalText)
                .scaledNumeral(44)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .padding(.top, 18)
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Palette.heroGrey))
    }

    private var entriesHeader: some View {
        HStack {
            Text("insights.entries")
                .kassa(.label)
                .foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Text(String(Int(data.entryCount)))
                .font(.footnote)
                .monospacedDigit()
                .foregroundColor(Palette.ink2)
        }
        .padding(.top, 14)
        .padding(.bottom, 6)
        .overlay(alignment: .bottom) { Hairline() }
    }

    @ViewBuilder
    private var entries: some View {
        if data.days.isEmpty {
            Text(String(format: String(localized: "insights.nothingIn"), name, month))
                .font(.body)
                .foregroundColor(Palette.ink2)
                .padding(.vertical, 16)
        } else if lazy {
            LazyVStack(alignment: .leading, spacing: 0) { days }
        } else {
            VStack(alignment: .leading, spacing: 0) { days }
        }
    }

    private var days: some View {
        ForEach(data.days, id: \.epochDay) { day in
            DaySection(day: day, today: today, onOpen: onOpen)
        }
    }
}
