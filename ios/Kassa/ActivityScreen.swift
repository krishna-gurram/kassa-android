import SwiftUI
import KassaShared

/// One month of entries, newest day first, with the month's Spending and Income. Read-only. The totals, the grouping, the
/// amounts and their colours come from the shared rules (`LedgerView.activity`); this only lays them out.
struct ActivityScreen: View {
    let data: ActivityData
    let hidden: Bool
    let today: Int
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    /// A tap on an editable row. Nil draws rows that do nothing.
    var onOpen: ((EntryRowData) -> Void)? = nil
    /// Batch J: search, the Spending and Income filters, the month picker and day pages (`AppModel+Read.swift`).
    var read: ActivityRead? = nil

    var body: some View {
        ScrollView {
            ActivityContent(
                data: data, hidden: hidden, today: today, lazy: true,
                onToggleHidden: onToggleHidden, onShift: onShift, onOpen: onOpen, read: read
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The screen without its scroll view. [lazy] is true in the app (a long month builds rows as they scroll) and false for a
/// screenshot, which cannot draw lazy stacks.
struct ActivityContent: View {
    let data: ActivityData
    let hidden: Bool
    let today: Int
    let lazy: Bool
    let onToggleHidden: () -> Void
    let onShift: (Int) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil
    var read: ActivityRead? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TabTop(title: "activity.title", hidden: hidden, onToggle: onToggleHidden)
            if let extra = read {
                SearchField(text: extra.query)
            }
            if let extra = read, let found = extra.search {
                SearchResults(data: found, hidden: hidden, onOpen: onOpen)
            } else {
                monthBody
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var monthBody: some View {
        VStack(alignment: .leading, spacing: 12) {
            MonthSwitcher(
                label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
                canForward: !data.isCurrentMonth,
                onShift: onShift,
                onLabel: read?.onOpenPicker
            )
            totals
            dayList
        }
    }

    private var totals: some View {
        let filter = read?.filter ?? "ALL"
        return HStack(spacing: 8) {
            Button(action: { read?.onTapFilter("EXPENSE") }) {
                TotalTile(label: "activity.spending", value: data.spendingText, color: Palette.ink, selected: filter == "EXPENSE")
            }
            .buttonStyle(.plain)
            .disabled(read == nil)
            Button(action: { read?.onTapFilter("INCOME") }) {
                TotalTile(
                    label: "activity.income", value: data.incomeText, color: Palette.figure(data.incomeTone), selected: filter == "INCOME"
                )
            }
            .buttonStyle(.plain)
            .disabled(read == nil)
        }
    }

    @ViewBuilder
    private var dayList: some View {
        if data.days.isEmpty {
            Text("activity.noneThisMonth")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 32)
        } else if lazy {
            LazyVStack(alignment: .leading, spacing: 0) { days }
        } else {
            VStack(alignment: .leading, spacing: 0) { days }
        }
    }

    private var days: some View {
        ForEach(data.days, id: \.epochDay) { day in
            DaySection(day: day, today: today, onOpen: onOpen, onOpenDay: read?.onOpenDay)
        }
    }
}

/// What batch J adds to Activity. The app builds it from `AppModel`; a screenshot builds it from a `LedgerView`.
struct ActivityRead {
    /// ALL, EXPENSE or INCOME (`EntryFilter`).
    let filter: String
    let query: Binding<String>
    /// The search result while something is typed, else nil.
    let search: SearchData?
    let onTapFilter: (String) -> Void
    let onOpenPicker: () -> Void
    let onOpenDay: (Int) -> Void
}

/// The search box: what is typed is text, never a pattern (the rules escape it).
struct SearchField: View {
    let text: Binding<String>

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(Palette.ink2)
            TextField("activity.searchHint", text: text)
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .accessibilityIdentifier("activitySearch")
            if !text.wrappedValue.isEmpty {
                Button(action: { text.wrappedValue = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(Palette.ink2)
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityLabel(Text("a11y.clearSearch"))
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
    }
}

/// The hits of a search over every month, newest month first. While amounts are hidden the spent line is the mask.
struct SearchResults: View {
    let data: SearchData
    let hidden: Bool
    var onOpen: ((EntryRowData) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(String(format: String(localized: "activity.hits"), Int(data.count)))
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                if data.hasSpent {
                    Text(data.spentText).font(.footnote.weight(.semibold)).monospacedDigit().lineLimit(1)
                }
            }
            .padding(.vertical, 6)
            if data.months.isEmpty {
                Text("activity.noMatch").font(.body).foregroundColor(Palette.ink2).padding(.vertical, 24)
            }
            ForEach(data.months, id: \.id) { month in
                ReadHeader(title: LocalizedStringKey(KassaDates.monthYear(year: Int(month.year), month: Int(month.month))))
                ForEach(month.entries, id: \.entry.id) { row in
                    EntryRowView(entry: row.entry, showDivider: true, onOpen: onOpen)
                }
            }
        }
    }
}

extension SearchMonthData {
    /// yyyymm, for lists.
    var id: Int { return Int(year) * 100 + Int(month) }
}

/// The month on show with an arrow at each edge. The forward arrow is off on the current month: nothing is later.
struct MonthSwitcher: View {
    let label: String
    let canForward: Bool
    /// False when nothing was entered before this month (Insights); Activity always allows going back.
    var canBack: Bool = true
    let onShift: (Int) -> Void
    /// Opens the month picker; nil makes the label plain text.
    var onLabel: (() -> Void)? = nil

    var body: some View {
        HStack {
            arrow("chevron.left", "a11y.previousMonth", enabled: canBack, by: -1)
            Spacer(minLength: 8)
            labelView
            Spacer(minLength: 8)
            arrow("chevron.right", "a11y.nextMonth", enabled: canForward, by: 1)
        }
    }

    @ViewBuilder
    private var labelView: some View {
        if let open = onLabel {
            Button(action: open) {
                Text(label)
                    .font(.headline)
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(minHeight: 48)
            }
            .accessibilityHint(Text("a11y.chooseMonth"))
        } else {
            Text(label)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    private func arrow(_ symbol: String, _ description: LocalizedStringKey, enabled: Bool, by months: Int) -> some View {
        Button(action: { onShift(months) }) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(enabled ? Palette.ink : Palette.ink2.opacity(0.4))
                .frame(minWidth: 48, minHeight: 48)
        }
        .disabled(!enabled)
        .accessibilityLabel(Text(description))
    }
}

/// A total: a small label over a figure.
struct TotalTile: View {
    let label: LocalizedStringKey
    let value: String
    let color: Color
    /// The filter that is on: the tile is drawn raised.
    var selected: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundColor(Palette.ink2)
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .foregroundColor(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(selected ? Palette.chip : Color.clear))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A day: its header (name and what was spent) and its entries.
struct DaySection: View {
    let day: DayData
    let today: Int
    var onOpen: ((EntryRowData) -> Void)? = nil
    /// A tap on the day's header opens the day page (batch J); nil leaves the header plain.
    var onOpenDay: ((Int) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Hairline()
            ForEach(day.entries, id: \.id) { entry in
                EntryRowView(entry: entry, showDivider: entry.id != day.entries.last?.id, onOpen: onOpen)
            }
        }
    }

    @ViewBuilder
    private var header: some View {
        if let open = onOpenDay {
            Button(action: { open(Int(day.epochDay)) }) { headerLine }
                .buttonStyle(.plain)
        } else {
            headerLine
        }
    }

    private var headerLine: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(Wording.dayLabel(epochDay: Int(day.epochDay), today: today))
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(day.spentText)
                    .font(.footnote.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .padding(.top, 18)
            .padding(.bottom, 6)
            .frame(minHeight: 48, alignment: .bottom)
            .accessibilityElement(children: .combine)
        }
    }
}

/// One entry: a badge, the title and the line under it, the amount and a small note under that.
struct EntryRowView: View {
    let entry: EntryRowData
    let showDivider: Bool
    var onOpen: ((EntryRowData) -> Void)? = nil
    /// Replaces the small note under the amount (the investment page says "Cash <x>" there).
    var noteOverride: String? = nil

    private var opensSomething: Bool {
        if entry.editable || entry.personPayment || entry.loanPaymentId > 0 { return true }
        return entry.kind.name == "Correction" || entry.kind.name == "Buy" || entry.kind.name == "Sell"
    }

    var body: some View {
        // Expenses, income, transfers and card payments open the entry page (`EntryRowData.editable`) and a payment to or from a person
        // opens its own sheet (`personPayment`). Any entry that belongs to a loan payment (`loanPaymentId`, whatever its kind) opens the
        // payment sheet; a buy or sale opens the trade sheet; a balance correction opens its sheet.
        if opensSomething, let open = onOpen {
            Button(action: { open(entry) }) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }

    private var content: some View {
        let base = Wording.entry(entry)
        let words = EntryWords(title: base.title, subtitle: base.subtitle, note: noteOverride ?? base.note)
        return VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                EntryBadge(entry: entry)
                VStack(alignment: .leading, spacing: 2) {
                    Text(words.title)
                        .font(.body)
                        .lineLimit(1)
                    if !words.subtitle.isEmpty {
                        Text(words.subtitle)
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(entry.amountText)
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundColor(Palette.figure(entry.amountTone, muted: entry.amountMuted))
                        .lineLimit(1)
                    amountNote(words)
                }
            }
            .padding(.vertical, 8)
            .frame(minHeight: 56)
            if showDivider {
                Hairline()
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func amountNote(_ words: EntryWords) -> some View {
        HStack(spacing: 4) {
            if !entry.tag.isEmpty {
                Text(entry.tag)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(Capsule().fill(Palette.chip))
            }
            if !words.note.isEmpty {
                Text(words.note)
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
        }
    }
}

/// The round badge of an entry: the category's emoji or letter on its colour, or a symbol for kinds without a category.
struct EntryBadge: View {
    let entry: EntryRowData

    private var hasCategory: Bool { !entry.badgeText.isEmpty }

    private var fill: Color {
        if hasCategory { return Color(argb: entry.badgeColor).opacity(0.22) }
        return Palette.chip
    }

    var body: some View {
        ZStack {
            Circle().fill(fill)
            if hasCategory {
                Text(entry.badgeText)
                    .font(.system(size: 18))
            } else {
                Image(systemName: Wording.symbol(entry.kind))
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(Palette.ink2)
            }
        }
        .frame(width: 36, height: 36)
        .accessibilityHidden(true)
    }
}
