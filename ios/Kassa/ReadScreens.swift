import SwiftUI
import KassaShared

/// Batch J, functional first (a redesign comes later): Insights' Patterns tiles, the week view, and the pages they open (spending, day,
/// store, detail). Every figure, share, order and colour comes from the shared rules (`ReadViews`); these views only lay them out.
/// While amounts are hidden the shared figures are the mask, with no bars, no arrows and no over-budget mark, so nothing here has to
/// hide anything itself.

enum ReadWording {

    /// The name of an ISO weekday (1 Monday ... 7 Sunday) in the phone's language.
    static func weekdayName(_ iso: Int) -> String {
        let names = Calendar.current.weekdaySymbols
        let index = iso % 7
        if index < 0 || index >= names.count { return "" }
        return names[index]
    }

    static func weekdayLetter(_ iso: Int) -> String {
        let names = Calendar.current.veryShortWeekdaySymbols
        let index = iso % 7
        if index < 0 || index >= names.count { return "" }
        return names[index]
    }

    static func detailKind(forTile kind: String) -> String {
        switch kind {
        case "BIG": return "BIG_MONTH"
        case "STORE": return "STORES"
        case "SMALL": return "SMALL"
        case "CREEP": return "CREEP"
        case "REPEAT": return "REPEATING"
        default: return "WEEKDAYS"
        }
    }

    static func tileTitle(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case "BIG": return "insights.tile.big"
        case "STORE": return "insights.tile.store"
        case "SMALL": return "insights.tile.small"
        case "CREEP": return "insights.tile.creep"
        case "REPEAT": return "insights.tile.repeat"
        default: return "insights.tile.busiest"
        }
    }

    static func detailTitle(_ kind: String) -> String {
        switch kind {
        case "BIG_MONTH": return String(localized: "insights.tile.big")
        case "BIG_WEEK": return String(localized: "insights.tile.big")
        case "STORES": return String(localized: "insights.tile.store")
        case "SMALL": return String(localized: "insights.tile.small")
        case "CREEP": return String(localized: "insights.tile.creep")
        case "REPEATING": return String(localized: "insights.tile.repeat")
        case "WEEKDAYS": return String(localized: "insights.tile.busiest")
        default: return String(localized: "insights.detail.dues")
        }
    }

    static func figureLabel(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case "TOTAL": return "insights.figure.total"
        case "CARDS": return "insights.figure.cards"
        case "THIS_MONTH": return "insights.figure.thisMonth"
        case "BUYS": return "insights.figure.buys"
        case "EACH_UNDER": return "insights.figure.eachUnder"
        case "A_MONTH": return "insights.figure.aMonth"
        default: return "insights.figure.aYear"
        }
    }

    /// The small line of a pattern tile.
    static func tileSub(_ tile: PatternTileData) -> String {
        switch tile.subKind {
        case "TOP": return String(format: String(localized: "insights.sub.top"), Int(tile.subCount))
        case "BUYS": return String(format: String(localized: "insights.sub.buys"), Int(tile.subCount))
        case "MONTHS": return String(format: String(localized: "insights.sub.months"), Int(tile.subCount))
        case "CHANGED": return String(format: String(localized: "insights.sub.changed"), Int(tile.subCount))
        case "CHARGES": return String(format: String(localized: "insights.sub.charges"), Int(tile.subCount))
        case "STEADY": return String(localized: "insights.sub.steady")
        case "AMOUNT": return tile.subText
        default: return ""
        }
    }

    static func tileValue(_ tile: PatternTileData) -> String {
        if !tile.hasValue {
            return tile.kind == "CREEP" ? String(localized: "insights.none") : String(localized: "insights.noneYet")
        }
        if tile.kind == "BUSIEST" && tile.valueWeekday > 0 { return weekdayName(Int(tile.valueWeekday)) }
        return tile.valueText
    }

    /// "Up 12.40" style text for the week change tiles.
    static func refText(_ line: CatLineData) -> String {
        if line.refKind == "BUDGET" { return String(format: String(localized: "insights.ofBudget"), line.refText) }
        if line.refKind == "LAST_WEEK" { return String(format: String(localized: "insights.lastWeek"), line.refText) }
        return ""
    }
}

// MARK: Small drawings

/// Bars as a row of capsules against the tallest ([permille] 0 to 1000). Nothing when there are none (always so while hidden).
struct MiniBars: View {
    let bars: [Int]
    let highlight: Int
    let color: Color
    var height: CGFloat = 28

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(Array(bars.enumerated()), id: \.offset) { pair in
                bar(index: pair.offset, permille: pair.element)
            }
        }
        .frame(height: height, alignment: .bottom)
        .accessibilityHidden(true)
    }

    private func bar(index: Int, permille: Int) -> some View {
        let fraction = max(0.08, CGFloat(permille) / 1000.0)
        let strong = highlight < 0 || index == highlight
        return RoundedRectangle(cornerRadius: 2)
            .fill(color.opacity(strong ? 1 : 0.35))
            .frame(width: 8, height: height * fraction)
    }
}

/// A horizontal share bar. [over] is orange (the shared rule decides; never set while hidden).
struct ShareBar: View {
    let permille: Int
    let color: Color
    let over: Bool

    var body: some View {
        let fraction = min(1.0, Double(permille) / 1000.0)
        return Capsule()
            .fill(Palette.chip)
            .frame(height: 6)
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule()
                        .fill(over ? Palette.orange : color)
                        .frame(width: proxy.size.width * CGFloat(fraction))
                }
            }
    }
}

/// A section label with a hairline under it.
struct ReadHeader: View {
    let title: LocalizedStringKey
    var trailing: String = ""

    var body: some View {
        HStack {
            Text(title)
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            if !trailing.isEmpty {
                Text(trailing)
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
        }
        .padding(.top, 14)
        .padding(.bottom, 6)
        .overlay(alignment: .bottom) { Hairline() }
    }
}

// MARK: Patterns

/// The six Patterns tiles of Insights, two to a row. Each opens its page.
struct PatternsSection: View {
    let tiles: [PatternTileData]
    let onOpen: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ReadHeader(title: "insights.patterns")
            // Two to a row in plain stacks (a screenshot cannot draw lazy grids).
            ForEach(rowStarts, id: \.self) { start in
                HStack(spacing: 8) {
                    tileButton(start)
                    tileButton(start + 1)
                }
            }
        }
    }

    private var rowStarts: [Int] {
        return Array(stride(from: 0, to: tiles.count, by: 2))
    }

    @ViewBuilder
    private func tileButton(_ index: Int) -> some View {
        if index < tiles.count {
            Button(action: { onOpen(ReadWording.detailKind(forTile: tiles[index].kind)) }) {
                PatternTileView(tile: tiles[index])
            }
            .buttonStyle(.plain)
        } else {
            Color.clear.frame(maxWidth: .infinity)
        }
    }
}

struct PatternTileView: View {
    let tile: PatternTileData

    private var barColor: Color {
        if tile.attention { return Palette.orange }
        if tile.barColor != 0 { return Color(argb: tile.barColor) }
        return Palette.ink2
    }

    /// Kotlin's `List<Int>` reaches Swift as boxed numbers.
    private var bars: [Int] {
        return tile.bars.map { number in Int(number.intValue) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(ReadWording.tileTitle(tile.kind))
                .font(.caption.weight(.semibold))
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
            Text(ReadWording.tileValue(tile))
                .font(.headline)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(ReadWording.tileSub(tile))
                .font(.footnote)
                .foregroundColor(tile.attention ? Palette.orange : Palette.ink2)
                .lineLimit(1)
            MiniBars(bars: bars, highlight: Int(tile.highlight), color: barColor)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.chip))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Week

/// The week view of Insights: total against the same stretch of last week, the day strip, the categories, up most and down most, due
/// and big purchases. Hero tap opens the spending page of the week.
struct WeekContent: View {
    let data: WeekData
    let hidden: Bool
    let today: Int
    let onOpenSpending: () -> Void
    let onOpenCategory: (Int64) -> Void
    let onOpenDetail: (String) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            hero
            strip
            ReadHeader(title: "insights.byCategory")
            ForEach(data.lines, id: \.id) { line in
                Button(action: { onOpenCategory(line.id) }) { CatLineRow(line: line) }
                    .buttonStyle(.plain)
                Hairline()
            }
            changes
            dueTile
            bigTile
            largest
        }
    }

    private var hero: some View {
        Button(action: onOpenSpending) {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(format: String(localized: "insights.weekOf"), KassaDates.shortDate(epochDay: Int(data.startDay))))
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Text(data.totalText)
                    .font(.largeTitle.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                HStack(spacing: 8) {
                    Text(String(format: String(localized: "insights.sameStretch"), ReadWording.weekdayName(Int(data.compareWeekday))))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                    Text(data.compareText)
                        .font(.footnote.weight(.semibold))
                        .monospacedDigit()
                        .lineLimit(1)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20).fill(Palette.chip))
        }
        .buttonStyle(.plain)
    }

    private var strip: some View {
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(data.days, id: \.epochDay) { day in
                WeekDayCell(day: day)
            }
        }
    }

    @ViewBuilder
    private var changes: some View {
        if data.up.found || data.down.found {
            ReadHeader(title: "insights.vsLastWeek")
            if data.up.found { changeRow(data.up, key: "insights.upMost") }
            if data.down.found { changeRow(data.down, key: "insights.downMost") }
        }
    }

    private func changeRow(_ change: WeekChangeData, key: LocalizedStringKey) -> some View {
        return Button(action: { onOpenCategory(change.categoryId) }) {
            HStack {
                Text(key).font(.footnote).foregroundColor(Palette.ink2)
                Text(change.name).font(.body).lineLimit(1)
                Spacer(minLength: 8)
                Text(change.changeText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .padding(.vertical, 8)
            .frame(minHeight: 48)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var dueTile: some View {
        if data.dueCount > 0 {
            Button(action: { onOpenDetail("DUES") }) {
                HStack {
                    Text("insights.dueThisWeek").font(.body)
                    Spacer(minLength: 8)
                    Text(data.dueText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
                }
                .padding(.vertical, 8)
                .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
            Hairline()
        }
    }

    @ViewBuilder
    private var bigTile: some View {
        if data.bigCount > 0 {
            Button(action: { onOpenDetail("BIG_WEEK") }) {
                HStack {
                    Text("insights.tile.big").font(.body)
                    Spacer(minLength: 8)
                    Text(data.bigText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
                }
                .padding(.vertical, 8)
                .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
            Hairline()
        }
    }

    @ViewBuilder
    private var largest: some View {
        if !data.largest.isEmpty {
            ReadHeader(title: "insights.largestBuys")
            ForEach(data.largest, id: \.entry.id) { row in
                EntryRowView(entry: row.entry, showDivider: true, onOpen: onOpen)
            }
        }
    }
}

struct WeekDayCell: View {
    let day: WeekDayData

    var body: some View {
        VStack(spacing: 4) {
            Text(day.hasValue ? day.valueText : "")
                .font(.caption2)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Capsule()
                .fill(day.isToday ? Palette.ink : Palette.ink2.opacity(0.5))
                .frame(width: 10, height: max(4, 48 * CGFloat(day.permille) / 1000.0))
            Text(ReadWording.weekdayLetter(Int(day.weekday)))
                .font(.caption2.weight(day.isToday ? .bold : .regular))
                .foregroundColor(Palette.ink2)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .bottom)
        .accessibilityElement(children: .combine)
    }
}

/// A category line of the week view or the spending page: icon, name, amount, what it is against, and a bar. Orange only when the
/// shared rule says over (never while hidden).
struct CatLineRow: View {
    let line: CatLineData

    private var name: String {
        return line.name.isEmpty ? String(localized: "entry.uncategorized") : line.name
    }

    private var choice: CategoryChoiceData {
        return CategoryChoiceData(
            id: line.id, name: name, parentName: "", iconKind: line.iconKind, iconValue: line.iconValue,
            color: line.color, hasSubcategories: false
        )
    }

    private var barColor: Color {
        return line.id == 0 ? Palette.ink2 : Color(argb: line.color)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                CategoryIconView(choice: choice, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(.body).lineLimit(1)
                    let reference = ReadWording.refText(line)
                    if !reference.isEmpty {
                        Text(reference).font(.footnote).foregroundColor(Palette.ink2).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Text(line.amountText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(line.over ? Palette.orange : Palette.ink)
                    .lineLimit(1)
            }
            ShareBar(permille: Int(line.barPermille), color: barColor, over: line.over)
                .padding(.leading, 44)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
