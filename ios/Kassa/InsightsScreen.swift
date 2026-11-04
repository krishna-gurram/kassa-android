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
        let binding = Binding<Bool>(get: { extra.showWeek }, set: { week in extra.onSetWeek(week) })
        return Picker("", selection: binding) {
            Text("insights.month").tag(false)
            Text("insights.week").tag(true)
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("insightsPeriod")
    }

    // MARK: Hero

    private var hero: some View {
        let month = KassaDates.monthYear(year: Int(data.year), month: Int(data.month))
        return VStack(alignment: .leading, spacing: 8) {
            Text(String(format: String(localized: "insights.spentIn"), month))
                .font(.footnote.weight(.semibold))
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
            Text(data.totalText)
                .font(.largeTitle.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .foregroundColor(Palette.figure(data.tone))
            if data.hasCompare {
                HStack(spacing: 8) {
                    Text(String(format: String(localized: "insights.upTo"), KassaDates.shortDate(epochDay: Int(data.compareDay))))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                    Text(data.compareText)
                        .font(.footnote.weight(.semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.heroTint(data.tone)))
    }

    // MARK: By category

    private var sectionHeader: some View {
        Text("insights.byCategory")
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundColor(Palette.ink2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 14)
            .padding(.bottom, 6)
            .overlay(alignment: .bottom) { Hairline() }
    }

    @ViewBuilder
    private var lines: some View {
        if data.lines.isEmpty {
            // While hidden the empty month says nothing: a sentence here would show that the hidden total is zero.
            if !hidden {
                Text("insights.noSpending")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
        } else {
            ForEach(data.lines, id: \.id) { line in
                Button(action: { onOpenCategory(line.id) }) {
                    InsightLineView(line: line)
                }
                .buttonStyle(.plain)
                Hairline()
            }
        }
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

/// The icon, name and amount of a category line, its share, and a bar against the largest line. No share or bar while hidden.
struct InsightLineView: View {
    let line: InsightLineData

    private var name: String {
        return line.name.isEmpty ? String(localized: "entry.uncategorized") : line.name
    }

    private var choice: CategoryChoiceData {
        return CategoryChoiceData(
            id: line.id, name: name, parentName: "", iconKind: line.iconKind, iconValue: line.iconValue,
            color: line.color, hasSubcategories: false
        )
    }

    private var share: String? {
        if line.percent < 0 { return nil }
        return String(format: String(localized: "insights.percent"), Int(line.percent))
    }

    private var barColor: Color {
        return line.id == 0 ? Palette.ink2 : Color(argb: line.color)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                CategoryIconView(choice: choice, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.body)
                        .lineLimit(1)
                    if !line.refText.isEmpty {
                        Text(String(format: String(localized: "insights.ofBudget"), line.refText))
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                if let text = share {
                    Text(text)
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                }
                Text(line.amountText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(line.over ? Palette.orange : Palette.ink)
                    .lineLimit(1)
            }
            bar
                .padding(.leading, 44)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var bar: some View {
        let fraction = Double(line.barPermille) / 1000.0
        return Capsule()
            .fill(Palette.chip)
            .frame(height: 6)
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule()
                        .fill(line.over ? Palette.orange : barColor)
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
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(name)
                .font(.title2.weight(.bold))
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
                .font(.footnote.weight(.semibold))
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
            Text(data.totalText)
                .font(.largeTitle.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.chip))
    }

    private var entriesHeader: some View {
        HStack {
            Text("insights.entries")
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
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
