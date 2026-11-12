import SwiftUI

/// The entry page's month calendar: the month's days under the weekday letters, starting on the phone's first weekday. The day picked
/// is an ink disc, today is outlined, and days after today are dimmed and can't be picked (as on Android). The arrows change the
/// month (the next one only up to this month). It only lays out days; the day picked goes back as an epoch day.
struct EntryCalendar: View {
    let selected: Int
    let today: Int
    let onPick: (Int) -> Void

    @State private var year: Int
    @State private var month: Int

    init(selected: Int, today: Int, onPick: @escaping (Int) -> Void) {
        self.selected = selected
        self.today = today
        self.onPick = onPick
        let shown = KassaDates.yearMonth(epochDay: selected)
        _year = State(initialValue: shown.year)
        _month = State(initialValue: shown.month)
    }

    private var current: (year: Int, month: Int) {
        return KassaDates.yearMonth(epochDay: today)
    }

    private var canGoNext: Bool {
        let now = current
        return year * 12 + month < now.year * 12 + now.month
    }

    /// The epoch day of the 1st of the shown month.
    private var firstEpoch: Int {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = 1
        guard let first = KassaDates.gregorian.date(from: parts) else { return today }
        return KassaDates.epochDay(of: first)
    }

    /// The days of the month as week rows of seven: the day of the month, or 0 for a blank cell.
    private var weeks: [[Int]] {
        let first: Date = KassaDates.date(epochDay: firstEpoch)
        let count: Int = KassaDates.gregorian.range(of: .day, in: .month, for: first)?.count ?? 30
        let weekday: Int = KassaDates.gregorian.component(.weekday, from: first)
        let lead: Int = (weekday - Calendar.current.firstWeekday + 7) % 7
        var cells: [Int] = Array(repeating: 0, count: lead)
        for day in 1...count {
            cells.append(day)
        }
        while cells.count % 7 != 0 {
            cells.append(0)
        }
        var rows: [[Int]] = []
        var start = 0
        while start < cells.count {
            rows.append(Array(cells[start..<(start + 7)]))
            start += 7
        }
        return rows
    }

    private var weekdayLetters: [String] {
        let symbols: [String] = Calendar.current.veryShortStandaloneWeekdaySymbols
        let first: Int = Calendar.current.firstWeekday - 1
        var letters: [String] = []
        for offset in 0..<7 {
            letters.append(symbols[(first + offset) % 7])
        }
        return letters
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            HStack(spacing: 0) {
                ForEach(0..<7, id: \.self) { index in
                    Text(weekdayLetters[index])
                        .font(Font.kassa(12, .semibold, relativeTo: .caption))
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, minHeight: 32)
                        .accessibilityHidden(true)
                }
            }
            ForEach(0..<weeks.count, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<7, id: \.self) { column in
                        cell(weeks[row][column])
                    }
                }
            }
        }
        .onChange(of: selected) { day in
            let shown = KassaDates.yearMonth(epochDay: day)
            year = shown.year
            month = shown.month
        }
    }

    private var header: some View {
        HStack(spacing: 0) {
            Button(action: { shift(-1) }) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("a11y.previousMonth"))
            Text(KassaDates.monthYear(year: year, month: month))
                .font(Font.kassa(17, .semibold, relativeTo: .body))
                .foregroundColor(Palette.ink)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)
            Button(action: { shift(1) }) {
                Image(systemName: "chevron.right")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(canGoNext ? Palette.ink : Palette.ink3.opacity(0.5))
                    .frame(minWidth: 48, minHeight: 48)
            }
            .disabled(!canGoNext)
            .accessibilityLabel(Text("a11y.nextMonth"))
        }
    }

    private func shift(_ months: Int) {
        let next = KassaDates.shifted(year: year, month: month, by: months)
        year = next.year
        month = next.month
    }

    @ViewBuilder
    private func cell(_ day: Int) -> some View {
        if day == 0 {
            Color.clear.frame(maxWidth: .infinity).frame(height: 48)
        } else {
            dayButton(day)
        }
    }

    private func dayButton(_ day: Int) -> some View {
        let epoch: Int = firstEpoch + day - 1
        let on: Bool = epoch == selected
        let future: Bool = epoch > today
        let isToday: Bool = epoch == today
        return Button(action: { onPick(epoch) }) {
            Text(String(day))
                .font(Font.kassa(17, isToday ? .bold : .medium, relativeTo: .body).monospacedDigit())
                .foregroundColor(on ? Palette.onInk : (future ? Palette.ink3.opacity(0.6) : Palette.ink))
                .frame(width: 40, height: 40)
                .background(Circle().fill(on ? Palette.ink : Color.clear))
                .overlay(Circle().stroke(isToday && !on ? Palette.ink : Color.clear, lineWidth: 1.5))
                .frame(maxWidth: .infinity, minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(future)
        .accessibilityLabel(Text(KassaDates.shortDateYear(epochDay: epoch)))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
