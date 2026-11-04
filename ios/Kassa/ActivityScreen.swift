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

    var body: some View {
        ScrollView {
            ActivityContent(
                data: data, hidden: hidden, today: today, lazy: true,
                onToggleHidden: onToggleHidden, onShift: onShift, onOpen: onOpen
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

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TabTop(title: "activity.title", hidden: hidden, onToggle: onToggleHidden)
            MonthSwitcher(
                label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
                canForward: !data.isCurrentMonth,
                onShift: onShift
            )
            totals
            dayList
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var totals: some View {
        HStack(spacing: 8) {
            TotalTile(label: "activity.spending", value: data.spendingText, color: Palette.ink)
            TotalTile(label: "activity.income", value: data.incomeText, color: Palette.figure(data.incomeTone))
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
            DaySection(day: day, today: today, onOpen: onOpen)
        }
    }
}

/// The month on show with an arrow at each edge. The forward arrow is off on the current month: nothing is later.
struct MonthSwitcher: View {
    let label: String
    let canForward: Bool
    /// False when nothing was entered before this month (Insights); Activity always allows going back.
    var canBack: Bool = true
    let onShift: (Int) -> Void

    var body: some View {
        HStack {
            arrow("chevron.left", "a11y.previousMonth", enabled: canBack, by: -1)
            Spacer(minLength: 8)
            Text(label)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 8)
            arrow("chevron.right", "a11y.nextMonth", enabled: canForward, by: 1)
        }
    }

    private func arrow(_ symbol: String, _ description: LocalizedStringKey, enabled: Bool, by months: Int) -> some View {
        Button(action: { onShift(months) }) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(enabled ? Palette.ink : Palette.ink2.opacity(0.4))
                .frame(minWidth: 44, minHeight: 44)
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
        .accessibilityElement(children: .combine)
    }
}

/// A day: its header (name and what was spent) and its entries.
struct DaySection: View {
    let day: DayData
    let today: Int
    var onOpen: ((EntryRowData) -> Void)? = nil

    var body: some View {
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
            .accessibilityElement(children: .combine)
            Hairline()
            ForEach(day.entries, id: \.id) { entry in
                EntryRowView(entry: entry, showDivider: entry.id != day.entries.last?.id, onOpen: onOpen)
            }
        }
    }
}

/// One entry: a badge, the title and the line under it, the amount and a small note under that.
struct EntryRowView: View {
    let entry: EntryRowData
    let showDivider: Bool
    var onOpen: ((EntryRowData) -> Void)? = nil

    var body: some View {
        // Expenses, income, transfers and card payments open the entry page (`EntryRowData.editable`) and a payment to or from a person
        // opens its own sheet (`personPayment`). Rows of a loan payment or a buy or sale do nothing when tapped:
        // Android sends each of those to its own sheet, which iOS doesn't have yet. A balance correction opens its sheet.
        if entry.editable || entry.personPayment || entry.kind.name == "Correction", let open = onOpen {
            Button(action: { open(entry) }) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }

    private var content: some View {
        let words = Wording.entry(entry)
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
