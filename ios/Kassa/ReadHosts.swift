import SwiftUI
import KassaShared

/// Activity with its batch J parts wired to `AppModel`: the search field, the Spending and Income filters, the month picker sheet and
/// the day pages. The layout is `ActivityContent`.
struct ActivityHost: View {
    @ObservedObject var model: AppModel
    let data: ActivityData
    @State private var picking = false

    var body: some View {
        let typed = Binding<String>(get: { model.searchText }, set: { text in model.setSearch(text) })
        let extra = ActivityRead(
            filter: model.activityFilter, query: typed, search: model.searchResult,
            onTapFilter: { tapped in model.tapFilter(tapped) },
            onOpenPicker: { picking = true },
            onOpenDay: { day in model.openRead(ReadRoute.day(day)) }
        )
        return ActivityScreen(
            data: data, hidden: model.hideAmounts, today: model.today,
            onToggleHidden: { model.toggleHidden() },
            onShift: { months in model.shiftMonth(months) },
            onOpen: { row in model.openEntry(id: row.id) },
            read: extra
        )
        .sheet(isPresented: $picking) {
            MonthPickerSheet(model: model, year: Int(data.year), month: Int(data.month), onClose: { picking = false })
        }
    }
}

/// "Choose month": twelve months of a year with an arrow at each edge. Months after the current one are off (the rules say which).
struct MonthPickerSheet: View {
    @ObservedObject var model: AppModel
    @State var year: Int
    let month: Int
    let onClose: () -> Void

    init(model: AppModel, year: Int, month: Int, onClose: @escaping () -> Void) {
        self.model = model
        self._year = State(initialValue: year)
        self.month = month
        self.onClose = onClose
    }

    var body: some View {
        let pick: MonthPickData = model.monthPick(year: year)
        return VStack(spacing: 16) {
            HStack {
                arrow("chevron.left", enabled: pick.canPreviousYear, by: -1)
                Spacer(minLength: 8)
                Text(String(Int(pick.year))).font(.headline).monospacedDigit()
                Spacer(minLength: 8)
                arrow("chevron.right", enabled: pick.canNextYear, by: 1)
            }
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(pick.cells, id: \.month) { cell in
                    cellButton(cell, year: Int(pick.year))
                }
            }
            Spacer()
        }
        .padding(16)
    }

    private func arrow(_ symbol: String, enabled: Bool, by years: Int) -> some View {
        return Button(action: { year += years }) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(enabled ? Palette.ink : Palette.ink2.opacity(0.4))
                .frame(minWidth: 48, minHeight: 48)
        }
        .disabled(!enabled)
    }

    private func cellButton(_ cell: MonthCellData, year shown: Int) -> some View {
        let symbols = Calendar.current.shortMonthSymbols
        let index = Int(cell.month) - 1
        let name = index >= 0 && index < symbols.count ? symbols[index] : ""
        let current = shown == Int(model.shownYear) && Int(cell.month) == model.shownMonth
        return Button(action: {
            model.pickMonth(year: shown, month: Int(cell.month))
            onClose()
        }) {
            Text(name)
                .font(.body.weight(current ? .bold : .regular))
                .foregroundColor(cell.enabled ? Palette.ink : Palette.ink2.opacity(0.4))
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 12).fill(current ? Palette.chip : Color.clear))
        }
        .disabled(!cell.enabled)
    }
}
