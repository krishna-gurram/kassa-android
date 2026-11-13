import Foundation
import KassaShared

/// A page opened over the tabs (batch J): "this week" and "this month" open the spending page, a day header opens the day page, a store
/// opens the store page, and a Patterns or week tile opens its detail page ([kind] is an `InsightDetailData.kind`).
enum ReadRoute: Equatable {
    case spending(month: Bool, income: Bool, back: Int)
    case day(Int)
    case store(String)
    case detail(String)
    /// Coach's page and its weekly check-in (`CoachScreens.swift`); their figures are `AppModel.coach`.
    case coach
    case checkIn
    /// The Coach simulator (`CoachSimScreens.swift`): the "Can I afford it?" form, its answer and "Simulate my month". Their figures are `AppModel.sim`.
    case coachAsk
    case coachAnswer
    case coachMonth

    var isCoach: Bool {
        return self == ReadRoute.coach || self == ReadRoute.checkIn || isSim
    }

    var isSim: Bool {
        return self == ReadRoute.coachAsk || self == ReadRoute.coachAnswer || self == ReadRoute.coachMonth
    }
}

/// The figures of the page that is on top of the read stack; only the one the route names is set.
struct ReadPage {
    let route: ReadRoute
    var spending: SpendingPageData?
    var day: DayPageData?
    var store: StorePageData?
    var detail: InsightDetailData?
}

/// What `computeRead` works out for the screens of batch J.
struct ReadFigures {
    let patterns: PatternsData
    let week: WeekData
    let page: ReadPage?
    let budgets: BudgetsData?
    let search: SearchData?
}

/// The read side of batch J. Every figure is `LedgerView`'s (never worked out here); this only keeps which page is open and moves between
/// them. The category page stays Insights' own (`openInsightsCategory`), so every category opens the same page.
extension AppModel {

    /// Works the Patterns, the week, the open page, the budgets and the search out of [made]. Queue only.
    func computeRead(_ made: LedgerView, _ given: Inputs) -> ReadFigures {
        let day = Int32(given.today)
        let zone = given.zone
        let first = Int32(given.firstWeekday)
        let patterns = made.patterns(hidden: given.hidden, todayEpochDay: day, timeZoneId: zone)
        let week = made.weekView(hidden: given.hidden, todayEpochDay: day, timeZoneId: zone, firstWeekday: first)
        var page: ReadPage? = nil
        if let route = given.route {
            page = readPage(made, route, given)
        }
        var budgets: BudgetsData? = nil
        if given.budgetsOpen {
            budgets = made.budgetsPage(hidden: given.hidden, todayEpochDay: day, timeZoneId: zone)
        }
        var search: SearchData? = nil
        if !given.query.isEmpty {
            search = made.activitySearch(query: given.query, hidden: given.hidden, timeZoneId: zone)
        }
        return ReadFigures(patterns: patterns, week: week, page: page, budgets: budgets, search: search)
    }

    private func readPage(_ made: LedgerView, _ route: ReadRoute, _ given: Inputs) -> ReadPage {
        let day = Int32(given.today)
        let zone = given.zone
        let first = Int32(given.firstWeekday)
        var page = ReadPage(route: route)
        switch route {
        case .spending(let month, let income, let back):
            page.spending = made.spendingPage(
                hidden: given.hidden, todayEpochDay: day, timeZoneId: zone, firstWeekday: first,
                month: month, income: income, back: Int32(back)
            )
        case .day(let epochDay):
            page.day = made.dayPage(epochDay: Int32(epochDay), hidden: given.hidden, timeZoneId: zone)
        case .store(let name):
            page.store = made.storePage(name: name, hidden: given.hidden, todayEpochDay: day, timeZoneId: zone)
        case .detail(let kind):
            page.detail = made.insightDetail(
                kind: kind, hidden: given.hidden, todayEpochDay: day, timeZoneId: zone, firstWeekday: first
            )
        case .coach, .checkIn, .coachAsk, .coachAnswer, .coachMonth:
            break
        }
        return page
    }

    // MARK: The read stack

    func openRead(_ route: ReadRoute) {
        if !CoachFeature.allows(route) { return }
        readStack.append(route)
        refreshFigures()
    }

    /// Goes back one page; the page under it (if any) is worked out again.
    func closeRead() {
        if readStack.isEmpty { return }
        readStack.removeLast()
        refreshFigures()
    }

    /// Moves the open spending page to the period [periods] away (-1 is the one before).
    func shiftSpending(_ periods: Int) {
        guard let route = readStack.last else { return }
        guard case .spending(let month, let income, let back) = route else { return }
        let next = back - periods
        if next < 0 { return }
        readStack[readStack.count - 1] = ReadRoute.spending(month: month, income: income, back: next)
        refreshFigures()
    }

    /// A category tapped on any page: Insights' category page for the month it was read in. Closes the pages over the tabs.
    func openCategory(_ id: Int64, monthsBack: Int = 0) {
        readStack = []
        insightsBack = monthsBack
        requestedTab = ShellTab.insights
        openInsightsCategory(id)
    }

    func setInsightsWeek(_ week: Bool) {
        insightsWeek = week
    }

    // MARK: Activity: filter, search and month

    /// Tapping Spending or Income filters to it; tapping the same one again shows everything (the rule is :domain's).
    func tapFilter(_ tapped: String) {
        activityFilter = ActivityBridge.shared.toggledFilter(current: activityFilter, tapped: tapped)
        refreshFigures()
    }

    /// What was typed in the search field, kept as the rules keep it; "" ends the search.
    func setSearch(_ typed: String) {
        let kept = ActivityBridge.shared.typedSearch(raw: typed)
        if kept == searchText { return }
        searchText = kept
        refreshFigures()
    }

    /// Shows [year] and [month] in Activity when the picker rules allow it (never after the current month).
    func pickMonth(year: Int, month: Int) {
        let now = KassaDates.yearMonth(epochDay: KassaDates.todayEpochDay())
        if !ActivityBridge.shared.canPickMonth(
            year: Int32(year), month: Int32(month), latestYear: Int32(now.year), latestMonth: Int32(now.month)
        ) { return }
        setMonth(year: year, month: month)
    }

    /// The twelve months of [year] for the picker sheet (asked on the main thread: the rules touch no Money).
    func monthPick(year: Int) -> MonthPickData {
        let now = KassaDates.yearMonth(epochDay: KassaDates.todayEpochDay())
        return ActivityBridge.shared.monthPick(year: Int32(year), latestYear: Int32(now.year), latestMonth: Int32(now.month))
    }

    // MARK: Budgets

    func openBudgets() {
        budgetsOpen = true
        refreshFigures()
    }

    func closeBudgets() {
        budgetsOpen = false
    }

    /// The editor's starting figures for [categoryId], asked on the queue.
    func loadBudgetEdit(_ categoryId: Int64, done: @escaping (BudgetEditData?) -> Void) {
        let day = Int32(KassaDates.todayEpochDay())
        queue.async {
            var found: BudgetEditData? = nil
            if let made = self.view {
                found = made.budgetEdit(categoryId: categoryId, todayEpochDay: day)
            }
            self.publish { done(found) }
        }
    }

    /// Saves the typed amounts: the domain says which rows change (a blank amount removes its row, a bad one writes nothing), and the store
    /// runs them in one transaction. A problem comes back as `.refused` with the field it is about (EVERY_MONTH or THIS_MONTH).
    func saveBudget(categoryId: Int64, every: String, thisMonth: String, done: @escaping (WriteOutcome) -> Void) {
        let day = Int32(KassaDates.todayEpochDay())
        perform({ opened in
            guard let made = self.view else { throw WriteRefusal(message: String(localized: "write.failed")) }
            let plan: BudgetPlanData = made.budgetPlan(categoryId: categoryId, every: every, thisMonth: thisMonth, todayEpochDay: day)
            if let first = plan.problems.first { throw WriteRefusal(field: first.field, message: first.message) }
            try opened.applyBudgetChanges(plan.changes)
            return categoryId
        }, done: done)
    }

    func removeBudget(categoryId: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.removeBudgets(categoryId: categoryId)
            return categoryId
        }, done: done)
    }
}
