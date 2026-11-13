import SwiftUI
import KassaShared

/// Coach simulator screens: the "Can I afford it?" form, its answer, and "Simulate my month". Every figure, verdict, day and kind comes
/// from `LedgerView.coachSim`; the sentences are made in `CoachSimWording`. Nothing here writes anything: Kassa never moves money or adds
/// an entry from these pages. There is no "Paid from" field on purpose: v1 replays total cash and the rules ignore accounts.

// MARK: Small parts

/// A row of items that wraps onto the next line (a lazy grid would not draw in the screenshot renderer).
struct SimFlow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width: CGFloat = proposal.width ?? CGFloat.infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(ProposedViewSize.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(ProposedViewSize.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// A page's top with a back button and a title, and no eye (the form's amounts are typed ones and never masked).
struct SimBar: View {
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(title)
                .kassa(.title)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
        }
        .padding(.top, 8)
    }
}

/// The hero: a tonal block, green above the cushion, orange when tight or short, grey for everything else and while amounts are hidden.
struct SimHero: View {
    let text: SimHeroText
    let hidden: Bool

    private var tint: Color {
        if hidden { return Palette.heroGrey }
        switch text.tone {
        case .green: return Palette.heroGreen
        case .orange: return Palette.heroOrange
        case .grey: return Palette.heroGrey
        }
    }

    private var ink: Color {
        if hidden { return Palette.heroGreyInk }
        switch text.tone {
        case .green: return Palette.heroGreenInk
        case .orange: return Palette.heroOrangeInk
        case .grey: return Palette.heroGreyInk
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(text.label)
                .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
                .foregroundColor(ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if !text.figure.isEmpty {
                AmountText(text: text.figure, hidden: hidden, size: 44, color: Palette.ink, centsColor: hidden ? Palette.ink : ink)
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .padding(.top, 2)
            }
            if !text.sub.isEmpty {
                Text(text.sub)
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, text.figure.isEmpty ? 6 : 4)
            }
            if !text.note.isEmpty {
                Text(text.note)
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
        }
        .padding(.top, 16)
        .padding(.horizontal, 20)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(tint))
        .accessibilityElement(children: .combine)
    }
}

/// One thing that happens: a kind badge, its name and detail, and its signed figure. A card or loan payment coming due is orange, money in
/// green, everything else ink.
struct SimEventRow: View {
    let event: CoachSimEventData
    let withDate: Bool

    private var figureColor: Color {
        if event.inflow { return Palette.green }
        return CoachSimWording.isAgainst(event.kind) ? Palette.orange : Palette.ink
    }

    var body: some View {
        let detail: String = CoachSimWording.eventDetail(event, withDate: withDate)
        return HStack(spacing: 14) {
            Badge(symbol: CoachSimWording.symbol(event.kind), tint: CoachSimWording.tint(event.kind))
            VStack(alignment: .leading, spacing: 2) {
                Text(CoachSimWording.eventTitle(event))
                    .font(Font.kassa(17, .regular, relativeTo: .body))
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if !detail.isEmpty {
                    Text(detail)
                        .font(Font.kassa(13, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            Text(CoachSimWording.figure(event))
                .font(Font.kassa(17, .semibold, relativeTo: .body).monospacedDigit())
                .foregroundColor(figureColor)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Chart

/// The chart's geometry in one place, so the lines and the dot at the lowest point agree.
enum SimChartMath {

    static let inset: CGFloat = 8

    /// The point for the [index]th of [count] values, in [rect] (a little room is kept above and below).
    static func point(index: Int, count: Int, value: Int64, low: Int64, high: Int64, in rect: CGRect) -> CGPoint {
        let inner: CGRect = rect.insetBy(dx: 0, dy: inset)
        let span: Double = Double(max(high - low, 1))
        let across: CGFloat = count > 1 ? CGFloat(index) / CGFloat(count - 1) : 0
        let x: CGFloat = inner.minX + inner.width * across
        let y: CGFloat = inner.maxY - inner.height * CGFloat(Double(value - low) / span)
        return CGPoint(x: x, y: y)
    }
}

struct SimLineShape: Shape {
    let values: [Int64]
    let low: Int64
    let high: Int64

    func path(in rect: CGRect) -> Path {
        var path = Path()
        if values.count < 2 { return path }
        for (index, value) in values.enumerated() {
            let point: CGPoint = SimChartMath.point(index: index, count: values.count, value: value, low: low, high: high, in: rect)
            if index == 0 {
                path.move(to: point)
            } else {
                path.addLine(to: point)
            }
        }
        return path
    }
}

struct SimRuleShape: Shape {
    let value: Int64
    let low: Int64
    let high: Int64

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let y: CGFloat = SimChartMath.point(index: 0, count: 1, value: value, low: low, high: high, in: rect).y
        path.move(to: CGPoint(x: rect.minX, y: y))
        path.addLine(to: CGPoint(x: rect.maxX, y: y))
        return path
    }
}

/// The month's cash as a line: solid with the changes, dashed without them, a dashed rule at the cushion and a dot at the lowest point.
/// The bridge sends no chart while amounts are hidden, so there is nothing to give a size away.
struct SimChart: View {
    let days: [Int]
    let balances: [Int64]
    let baseline: [Int64]
    let cushion: Int64
    let lowDay: Int
    let dot: Color
    let summary: String

    private var low: Int64 {
        var all: [Int64] = balances + baseline
        if cushion > 0 { all.append(cushion) }
        return all.min() ?? 0
    }

    private var high: Int64 {
        var all: [Int64] = balances + baseline
        if cushion > 0 { all.append(cushion) }
        return all.max() ?? 1
    }

    private var lowIndex: Int? {
        if lowDay < 0 { return nil }
        return days.firstIndex(of: lowDay)
    }

    var body: some View {
        let lo: Int64 = low
        let hi: Int64 = high
        return VStack(alignment: .leading, spacing: 4) {
            GeometryReader { geo in
                ZStack {
                    if cushion > 0 {
                        SimRuleShape(value: cushion, low: lo, high: hi)
                            .stroke(Palette.ink3, style: StrokeStyle(lineWidth: 1.2, dash: [2, 4]))
                    }
                    if baseline != balances {
                        SimLineShape(values: baseline, low: lo, high: hi)
                            .stroke(Palette.ink3, style: StrokeStyle(lineWidth: 2, dash: [5, 4]))
                    }
                    SimLineShape(values: balances, low: lo, high: hi)
                        .stroke(Palette.ink, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                    if let index = lowIndex, index < balances.count {
                        Circle()
                            .fill(dot)
                            .frame(width: 10, height: 10)
                            .position(
                                SimChartMath.point(
                                    index: index, count: balances.count, value: balances[index], low: lo, high: hi,
                                    in: CGRect(origin: CGPoint.zero, size: geo.size)
                                )
                            )
                    }
                }
            }
            .frame(height: 140)
            HStack {
                if let first = days.first {
                    Text(KassaDates.shortDate(epochDay: first))
                }
                Spacer(minLength: 8)
                if let last = days.last {
                    Text(KassaDates.shortDate(epochDay: last))
                }
            }
            .font(Font.kassa(12, .regular, relativeTo: .caption))
            .foregroundColor(Palette.ink3)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(summary))
    }
}

// MARK: Answer

/// The answer to "Can I afford it?": the verdict hero, the chart, what moves the month, and the way back to the form.
struct SimAnswerContent: View {
    let data: CoachSimData
    let hidden: Bool
    let cushionCents: Int64
    let symbol: String
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    var onTryAnother: () -> Void = {}
    var onSeeDays: () -> Void = {}

    private var hero: SimHeroText {
        return CoachSimWording.hero(data, cushionCents: cushionCents)
    }

    private var dotColor: Color {
        return data.verdict == "ABOVE_CUSHION" ? Palette.green : Palette.orange
    }

    private var chartDays: [Int] { return data.chartDays.map { $0.intValue } }
    private var chartBalances: [Int64] { return data.chartBalances.map { $0.int64Value } }
    private var baselineBalances: [Int64] { return data.baselineBalances.map { $0.int64Value } }

    var body: some View {
        let moves: [CoachSimEventData] = CoachSimWording.whatMoves(data)
        return VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: String(localized: "coachsim.answer.title"), hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            SimHero(text: hero, hidden: hidden)
            if CoachSimWording.hasMonth(data.verdict) && !chartBalances.isEmpty {
                SimChart(
                    days: chartDays, balances: chartBalances, baseline: baselineBalances, cushion: cushionCents,
                    lowDay: Int(data.lowestDay), dot: dotColor, summary: CoachSimWording.chartSummary(data, symbol: symbol)
                )
                .padding(.top, 6)
                legend
            }
            if !moves.isEmpty {
                SectionLabel(title: "coachsim.answer.moves", link: "coachsim.answer.seeDays", onLink: onSeeDays)
                    .padding(.top, 6)
                Hairline()
                VStack(spacing: 0) {
                    ForEach(0..<moves.count, id: \.self) { index in
                        SimEventRow(event: moves[index], withDate: true)
                        Hairline()
                    }
                }
            }
            Button(action: onTryAnother) { Text("coachsim.answer.another") }
                .buttonStyle(InkButtonStyle(filled: false))
                .padding(.top, 8)
                .accessibilityIdentifier("simTryAnother")
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 48)
    }

    @ViewBuilder
    private var legend: some View {
        if CoachSimWording.baselineDiffers(data) {
            Text("coachsim.legend.changes")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
        }
        if cushionCents > 0 {
            Text(String(format: String(localized: "coachsim.legend.cushion"), data.cushionText))
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
        }
    }
}

// MARK: Month

/// A what-if chip on the month list: "As planned" (on while no what-if is applied) or an applied change that a tap takes away.
struct SimChipButton: View {
    let label: String
    let on: Bool
    let removable: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(label)
                    .font(Font.kassa(15, .semibold, relativeTo: .subheadline))
                    .lineLimit(1)
                    .truncationMode(.tail)
                if removable {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .accessibilityHidden(true)
                }
            }
            .foregroundColor(on ? Palette.onInk : Palette.ink2)
            .padding(.horizontal, 16)
            .frame(minHeight: 40)
            .background(Capsule().fill(on ? Palette.ink : Palette.chip))
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : [.isButton])
        .accessibilityHint(removable ? Text("coachsim.chip.removeHint") : Text(""))
    }
}

/// "Simulate my month": the what-if chips, then every day with the cash at its end and what happened, the lowest day marked.
struct SimMonthContent: View {
    let data: CoachSimData
    let hidden: Bool
    let applied: [SimChip]
    let cushionCents: Int64
    let symbol: String
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    var onRemove: (SimChip) -> Void = { _ in }
    var onClear: () -> Void = {}

    var body: some View {
        let groups: [SimDayGroup] = CoachSimWording.dayGroups(data)
        return VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: String(localized: "coachsim.month.title"), hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            SimFlow(spacing: 8) {
                SimChipButton(label: String(localized: "coachsim.month.asPlanned"), on: applied.isEmpty, removable: false, action: onClear)
                ForEach(applied) { chip in
                    SimChipButton(label: CoachSimWording.chipLabel(chip), on: true, removable: true, action: { onRemove(chip) })
                }
            }
            if CoachSimWording.hasMonth(data.verdict) {
                summary
                VStack(spacing: 0) {
                    ForEach(0..<groups.count, id: \.self) { index in
                        dayBlock(groups[index])
                    }
                }
                Text("coachsim.fine")
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink3)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            } else {
                SimHero(text: CoachSimWording.hero(data, cushionCents: cushionCents), hidden: hidden)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 48)
    }

    private var summary: some View {
        let day: String = data.lowestDay >= 0 ? KassaDates.weekdayDate(epochDay: Int(data.lowestDay)) : ""
        let line: String = String(format: String(localized: "coachsim.month.summary"), data.lowestText, day)
        return Text(line)
            .font(Font.kassa(14, .regular, relativeTo: .footnote))
            .foregroundColor(Palette.ink2)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func dayBlock(_ group: SimDayGroup) -> some View {
        let isLowest: Bool = Int(data.lowestDay) == group.day
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text(KassaDates.weekdayDate(epochDay: group.day))
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                if let balance = group.balance {
                    let text: String = KassaCore.shared.formatMoney(cents: balance, symbol: symbol)
                    Text(isLowest ? String(format: String(localized: "coachsim.month.lowest"), text) : text)
                        .font(Font.kassa(12, .semibold, relativeTo: .caption).monospacedDigit())
                        .foregroundColor(isLowest ? Palette.orange : Palette.ink2)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 22)
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Palette.line, lineWidth: 1))
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 14)
            .padding(.bottom, 4)
            .accessibilityElement(children: .combine)
            ForEach(0..<group.events.count, id: \.self) { index in
                SimEventRow(event: group.events[index], withDate: false)
                Hairline()
            }
        }
    }
}

// MARK: Form

/// "Can I afford it?": the amount, the day, and what else to try. One ink button, "Check my month". The numbers typed here are the
/// person's own and are never masked; there is no eye.
struct SimAskPage: View {
    @ObservedObject var model: AppModel
    @Binding var kept: String

    private enum Target {
        case spend
        case extra
        case delay
        case payday
    }

    private enum Panel: Equatable {
        case none
        case pad(Target)
        case calendar(Target)
    }

    private enum Editor {
        case none
        case delay
        case payday
        case extra
    }

    @State private var panel: Panel = .none
    @State private var editor: Editor = .none
    @State private var editDay: Int = 0
    @State private var editKey: String = ""
    @State private var editName: String = ""
    @State private var editTyped: String = ""

    private var today: Int { return KassaDates.todayEpochDay() }
    private var last: Int { return today + model.simHorizon }
    private var symbol: String { return model.picks?.currencySymbol() ?? "" }
    private var decimalMark: String { return String(EntryBridge.shared.display(typed: "0.").dropFirst()) }

    private var movable: [CoachSimMovableData] {
        return model.simReady(for: ReadRoute.coachAsk)?.movable ?? []
    }

    private var purchaseDay: Int {
        return model.simDraft.resolvedDay(today: today, horizon: model.simHorizon)
    }

    private func chosenMovable() -> CoachSimMovableData? {
        if editKey.isEmpty { return nil }
        return movable.first(where: { $0.key == editKey })
    }

    private func amountText(_ typed: String) -> String {
        return symbol + EntryBridge.shared.display(typed: typed)
    }

    // MARK: Body

    var body: some View {
        VStack(spacing: 0) {
            SimBar(title: String(localized: "coachsim.ask.title"), onBack: { leave() })
                .padding(.horizontal, 16)
            switch panel {
            case .none:
                form
            case .pad(let target):
                padPanel(target)
                    .padding(.horizontal, 20)
            case .calendar(let target):
                calendarPanel(target)
                    .padding(.horizontal, 20)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { model.restoreSimDraft(kept) }
        .onChange(of: model.simDraft) { draft in kept = draft.encoded }
    }

    private func leave() {
        if panel != .none {
            panel = .none
            return
        }
        model.closeRead()
    }

    // MARK: The form

    private var form: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    EntryTile(
                        label: "coachsim.ask.amount", value: amountText(model.simDraft.typed), selected: false,
                        action: { panel = .pad(.spend) }
                    )
                    .accessibilityIdentifier("simAmount")
                    EntryTile(
                        label: "coachsim.ask.on", value: KassaDates.weekdayDate(epochDay: purchaseDay), selected: false,
                        action: { panel = .calendar(.spend) }
                    )
                    Text("coachsim.ask.orTry")
                        .kassa(.label)
                        .foregroundColor(Palette.ink2)
                        .padding(.top, 14)
                    SimFlow(spacing: 8) {
                        FilterChip(title: String(localized: "coachsim.try.delay"), selected: editor == .delay, action: { choose(.delay) })
                        FilterChip(title: String(localized: "coachsim.try.payday"), selected: editor == .payday, action: { choose(.payday) })
                        FilterChip(title: String(localized: "coachsim.try.extra"), selected: editor == .extra, action: { choose(.extra) })
                    }
                    editorView
                    if !model.simDraft.extras.isEmpty {
                        addedList
                    }
                    Text("coachsim.ask.note")
                        .font(Font.kassa(13, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 16)
            }
            Button(action: { model.checkSimMonth(symbol: symbol) }) { Text("coachsim.ask.check") }
                .buttonStyle(InkButtonStyle(filled: true))
                .padding(16)
                .accessibilityIdentifier("simCheck")
        }
    }

    private func choose(_ next: Editor) {
        if editor == next {
            editor = .none
            return
        }
        editor = next
        editKey = ""
        editName = ""
        editTyped = ""
        editDay = next == .payday ? min(today + 7, last) : purchaseDay
    }

    private var addedList: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("coachsim.ask.added")
                .kassa(.label)
                .foregroundColor(Palette.ink2)
                .padding(.top, 10)
            SimFlow(spacing: 8) {
                ForEach(model.simDraft.extras) { chip in
                    SimChipButton(label: CoachSimWording.chipLabel(chip), on: true, removable: true, action: { model.removeSimExtra(chip) })
                }
            }
        }
    }

    // MARK: Editors under "Or try"

    @ViewBuilder
    private var editorView: some View {
        switch editor {
        case .none:
            EmptyView()
        case .delay:
            delayEditor
        case .payday:
            paydayEditor
        case .extra:
            extraEditor
        }
    }

    private var delayEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            if movable.isEmpty {
                Text("coachsim.delay.none")
                    .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                    .foregroundColor(Palette.ink2)
            } else {
                Text("coachsim.delay.pick")
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
                ForEach(0..<movable.count, id: \.self) { index in
                    movableRow(movable[index])
                }
                if let chosen = chosenMovable() {
                    EntryTile(
                        label: "coachsim.delay.to", value: KassaDates.weekdayDate(epochDay: editDay), selected: false,
                        action: { panel = .calendar(.delay) }
                    )
                    addButton(enabled: editDay > Int(chosen.day) && editDay <= last) {
                        model.addSimExtra(SimChips.delay(key: chosen.key, name: chosen.name, toDay: editDay))
                        editor = .none
                    }
                }
            }
        }
    }

    private func movableRow(_ item: CoachSimMovableData) -> some View {
        let possible: Bool = Int(item.day) < last
        let on: Bool = editKey == item.key
        let title: String = item.name.isEmpty ? CoachSimWording.kindLabel(item.kind) : item.name
        return Button(action: {
            editKey = item.key
            editName = item.name
            editDay = min(max(Int(item.day) + 1, today + 1), last)
        }) {
            HStack(spacing: 12) {
                Badge(symbol: CoachSimWording.symbol(item.kind), tint: CoachSimWording.tint(item.kind))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Font.kassa(17, .regular, relativeTo: .body))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Text(String(format: String(localized: "coachsim.delay.due"), KassaDates.shortDate(epochDay: Int(item.day)), item.amountText))
                        .font(Font.kassa(13, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                if on {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(Palette.ink)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.raised))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? Palette.ink : Palette.line, lineWidth: on ? 2 : 1))
            .opacity(possible ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!possible)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private var paydayEditor: some View {
        VStack(alignment: .leading, spacing: 8) {
            EntryTile(
                label: "coachsim.payday.on", value: KassaDates.weekdayDate(epochDay: editDay), selected: false,
                action: { panel = .calendar(.payday) }
            )
            addButton(enabled: editDay >= today && editDay <= last) {
                model.addSimExtra(SimChips.payday(toDay: editDay))
                editor = .none
            }
        }
    }

    private var extraEditor: some View {
        let cents: Int64 = EntryBridge.shared.cents(typed: editTyped)
        return VStack(alignment: .leading, spacing: 8) {
            EntryTile(
                label: "coachsim.extra.amount", value: amountText(editTyped), selected: false,
                action: { panel = .pad(.extra) }
            )
            EntryTile(
                label: "coachsim.extra.from", value: KassaDates.weekdayDate(epochDay: editDay), selected: false,
                action: { panel = .calendar(.extra) }
            )
            addButton(enabled: cents > 0 && editDay >= today && editDay <= last) {
                model.addSimExtra(SimChips.extra(cents: cents, fromDay: editDay, shown: amountText(editTyped)))
                editor = .none
                editTyped = ""
            }
        }
    }

    private func addButton(enabled: Bool, action: @escaping () -> Void) -> some View {
        return Button(action: action) { Text("coachsim.add") }
            .buttonStyle(InkButtonStyle(filled: false))
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.5)
    }

    // MARK: Panels

    private func typedOf(_ target: Target) -> String {
        return target == .spend ? model.simDraft.typed : editTyped
    }

    private func press(_ target: Target, _ key: String) {
        let next = EntryBridge.shared.press(typed: typedOf(target), key: key)
        if target == .spend {
            model.simDraft.typed = next
        } else {
            editTyped = next
        }
    }

    private func padPanel(_ target: Target) -> some View {
        let title: LocalizedStringKey = target == .spend ? "coachsim.ask.amount" : "coachsim.extra.amount"
        return VStack(spacing: 8) {
            HStack {
                Text(title)
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Button(action: { panel = .none }) {
                    Text("coachsim.done")
                        .font(Font.kassa(16, .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityIdentifier("simDone")
            }
            Text(amountText(typedOf(target)))
                .scaledNumeral(44)
                .foregroundColor(typedOf(target).isEmpty ? Palette.ink3 : Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity, alignment: .leading)
            Spacer(minLength: 0)
            NumberPad(decimalMark: decimalMark, onKey: { key in press(target, key) })
                .padding(.bottom, 8)
        }
    }

    /// The days that can be picked for a target: today to the end of the horizon, and a delayed payment only after its own day.
    private func range(_ target: Target) -> ClosedRange<Int> {
        if target == .delay, let chosen = chosenMovable() {
            let from: Int = min(max(Int(chosen.day) + 1, today + 1), last)
            return from...last
        }
        return today...last
    }

    private func selectedDay(_ target: Target) -> Int {
        return target == .spend ? purchaseDay : editDay
    }

    private func pickDay(_ target: Target, _ day: Int) {
        if target == .spend {
            model.simDraft.day = day
        } else {
            editDay = day
        }
        panel = .none
    }

    private func calendarTitle(_ target: Target) -> LocalizedStringKey {
        switch target {
        case .delay: return "coachsim.delay.to"
        case .payday: return "coachsim.payday.on"
        case .extra: return "coachsim.extra.from"
        case .spend: return "coachsim.ask.on"
        }
    }

    private func calendarPanel(_ target: Target) -> some View {
        return VStack(spacing: 8) {
            HStack {
                Text(calendarTitle(target))
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Button(action: { panel = .none }) {
                    Text("coachsim.done")
                        .font(Font.kassa(16, .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48)
                }
            }
            EntryCalendar(
                selected: selectedDay(target), today: today, range: range(target),
                onPick: { day in pickDay(target, day) }
            )
            Spacer(minLength: 0)
        }
    }
}

// MARK: Host

/// The simulator page on top of the read stack. The form owns its scroll and number pad; the answer and the month list scroll.
struct SimHost: View {
    @ObservedObject var model: AppModel
    let route: ReadRoute
    @Binding var kept: String

    private var symbol: String { return model.picks?.currencySymbol() ?? "" }

    var body: some View {
        Group {
            if route == ReadRoute.coachAsk {
                SimAskPage(model: model, kept: $kept)
            } else if route == ReadRoute.coachAnswer {
                scroller { data in answer(data) }
            } else {
                scroller { data in month(data) }
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private func scroller<Content: View>(@ViewBuilder _ content: @escaping (CoachSimData) -> Content) -> some View {
        return ScrollView {
            if let data = model.simReady(for: route) {
                content(data)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
            } else {
                ProgressView().padding(.top, 80)
            }
        }
    }

    private func answer(_ data: CoachSimData) -> some View {
        return SimAnswerContent(
            data: data, hidden: model.hideAmounts, cushionCents: model.simCushion, symbol: symbol,
            onBack: { model.closeRead() }, onToggleHidden: { model.toggleHidden() },
            onTryAnother: { model.closeRead() }, onSeeDays: { model.openSimMonthKeeping() }
        )
    }

    private func month(_ data: CoachSimData) -> some View {
        return SimMonthContent(
            data: data, hidden: model.hideAmounts, applied: model.simApplied, cushionCents: model.simCushion, symbol: symbol,
            onBack: { model.closeRead() }, onToggleHidden: { model.toggleHidden() },
            onRemove: { chip in model.removeSimApplied(chip) }, onClear: { model.clearSimApplied() }
        )
    }
}
