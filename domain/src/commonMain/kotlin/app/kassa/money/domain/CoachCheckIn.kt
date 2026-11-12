package app.kassa.money.domain

/**
 * The weekly check-in: ONE win, ONE watch-out and ONE decision, plus what was spent this week against a usual week. Every part is
 * optional, so a new ledger gets fewer items; with fewer than [MIN_HISTORY_DAYS] days of entries the result is [Kind.NOT_ENOUGH_DATA].
 *
 * The week starts on the phone's first weekday ([CoachInput.firstDay]) and runs to today. "Usual" is the average of up to [USUAL_WEEKS]
 * earlier whole weeks that start on or after the first entry. Week figures are day to day spending: known payments (bills, loan payments,
 * recurring fixed charges; see [CoachLedger.recurringIds]) are left out, since rent in one week would otherwise make every other week
 * look thrifty. Budget figures are the budget month's whole spending, as everywhere else.
 *
 * - Win: the category with the biggest saving, either under the pace of its budget (spent so far against the budget's share for the days
 *   gone) or under its own usual week. Needs spending this week in it. Never a category the month's pace flags (so never the watch-out's).
 * - Watch-out: the on-pace overspend that is furthest past ([SpendingPace]), else the monthly charge that rose most
 *   ([SubscriptionChange]), else nothing.
 * - Decision: for a category watch-out only, a suggested weekly limit: the usual weekly spending in that category (average of the same
 *   weeks) rounded down to a whole unit of money, with at least [MIN_DECISION_WEEKS] weeks behind it. A suggestion only: nothing is written.
 */
object WeeklyCheckIn {
    const val MIN_HISTORY_DAYS = 14
    const val USUAL_WEEKS = 4
    const val MIN_DECISION_WEEKS = 2

    enum class Kind { NOT_ENOUGH_DATA, READY }

    enum class WinKind { UNDER_BUDGET, UNDER_USUAL }

    enum class WatchKind { CATEGORY_PACE, SUBSCRIPTION_RISE }

    /** What the pace is measured against for a [WatchKind.CATEGORY_PACE]. */
    enum class Reference { BUDGET, LAST_MONTH, NONE }

    /**
     * A category that is doing well. [spent] is what it took (this week for UNDER_USUAL, this month for UNDER_BUDGET), [reference] what it
     * is measured against (the usual week, or the budget's share for the days gone) and [savedBy] the difference, above zero.
     */
    data class Win(val kind: WinKind, val categoryId: Long, val spent: Long, val reference: Long, val savedBy: Long)

    /**
     * Something to keep an eye on. For a category pace: [amount] spent so far, [projection] where the month is heading, [reference] the
     * budget or last month's total ([referenceKind]) and [overBy] the gap. For a subscription: [amount] the latest charge, [reference]
     * the one before and [overBy] the rise; [name] is its name as written, "" for a category.
     */
    data class Watch(
        val kind: WatchKind,
        val categoryId: Long,
        val key: String,
        val name: String,
        val amount: Long,
        val projection: Long,
        val reference: Long,
        val referenceKind: Reference,
        val overBy: Long,
        val dayOfPeriod: Int,
        val daysInPeriod: Int,
        /** Share of the budget used, 0 to 100, for a category with a budget; -1 with none. */
        val usedPercent: Int,
    )

    /** A limit to suggest for [categoryId]. [thisWeek] is what the category took this week so far, [usualWeek] its unrounded usual week. */
    data class Decision(val categoryId: Long, val weeklyLimit: Long, val weeksUsed: Int, val usualWeek: Long, val thisWeek: Long)

    data class Result(
        val kind: Kind,
        /** Epoch day the week started on. */
        val weekStartDay: Int,
        /** Day to day spending from the start of the week through today. */
        val spent: Long,
        /** The average whole week; 0 without [hasUsual]. */
        val usualWeek: Long,
        /** The average of the same stretch of earlier weeks (start of the week through the same weekday); 0 without [hasUsual]. */
        val usualByNow: Long,
        /** Earlier whole weeks behind the averages: 1 to [USUAL_WEEKS] when [kind] is READY (14 days of entries always include one), else 0. */
        val weeksCompared: Int,
        val win: Win?,
        val watch: Watch?,
        val decision: Decision?,
    ) {
        val hasUsual: Boolean get() = weeksCompared > 0
    }

    fun compute(input: CoachInput): Result = compute(CoachLedger(input))

    internal fun compute(l: CoachLedger): Result {
        val first = l.firstEntryDay
        val weekStart = WeekPace.startOf(l.input.today, l.input.firstDay).toEpochDay().toInt()
        if (first == null || l.todayDay - first + 1 < MIN_HISTORY_DAYS) {
            return Result(Kind.NOT_ENOUGH_DATA, weekStart, 0L, 0L, 0L, 0, null, null, null)
        }
        val skip = l.recurringIds
        val offset = l.todayDay - weekStart
        val priorStarts = (1..USUAL_WEEKS).map { weekStart - 7 * it }.filter { it >= first }
        val n = priorStarts.size
        val spent = l.sum(l.expenses, weekStart, l.todayDay, null, skip)
        var wholeSum = 0L
        var byNowSum = 0L
        for (ws in priorStarts) {
            wholeSum += l.sum(l.expenses, ws, ws + 6, null, skip)
            byNowSum += l.sum(l.expenses, ws, ws + offset, null, skip)
        }

        // Per top-level category: this week, and the earlier weeks added up.
        val thisWeekBy = HashMap<Long, Long>()
        val priorBy = HashMap<Long, Long>()
        for (e in l.expenses) {
            if (e.top !in l.topExpenseIds || e.id in skip) continue
            if (e.day in weekStart..l.todayDay) thisWeekBy[e.top] = (thisWeekBy[e.top] ?: 0L) + e.amount
            if (priorStarts.any { e.day in it..(it + 6) }) priorBy[e.top] = (priorBy[e.top] ?: 0L) + e.amount
        }

        val pace = SpendingPace.compute(l)
        val watch = watchOf(l, pace)
        val win = winOf(l, pace, thisWeekBy, priorBy, n)
        val decision = decisionOf(watch, thisWeekBy, priorBy, n)
        return Result(
            Kind.READY, weekStart, spent,
            usualWeek = if (n > 0) CoachMath.divHalfUp(wholeSum, n.toLong()) else 0L,
            usualByNow = if (n > 0) CoachMath.divHalfUp(byNowSum, n.toLong()) else 0L,
            weeksCompared = n, win = win, watch = watch, decision = decision,
        )
    }

    private fun watchOf(l: CoachLedger, pace: SpendingPace.Result): Watch? {
        val row = pace.flagged.filter { it.overBy > 0L }
            .sortedWith(compareByDescending<SpendingPace.Row> { it.overBy }.thenByDescending { it.spent }.thenBy { it.categoryId })
            .firstOrNull()
        if (row != null) {
            val budget = row.budget
            val referenceKind = when {
                budget != null && (row.overBudget || row.paceOverBudget) -> Reference.BUDGET
                row.hasLast -> Reference.LAST_MONTH
                else -> Reference.NONE
            }
            val reference = when (referenceKind) {
                Reference.BUDGET -> budget ?: 0L
                Reference.LAST_MONTH -> row.lastTotal
                Reference.NONE -> 0L
            }
            val used = if (budget == null) -1 else CoachMath.divHalfUp(row.spent * 100, budget).coerceAtMost(100L).toInt()
            return Watch(
                WatchKind.CATEGORY_PACE, row.categoryId, "", "", row.spent, row.projection, reference, referenceKind, row.overBy,
                pace.dayOfPeriod, pace.daysInPeriod, used,
            )
        }
        val sub = SubscriptionChange.compute(l).rows.firstOrNull() ?: return null
        return Watch(
            WatchKind.SUBSCRIPTION_RISE, sub.categoryId, sub.key, sub.name, sub.latest, 0L, sub.previous, Reference.NONE, sub.change,
            pace.dayOfPeriod, pace.daysInPeriod, -1,
        )
    }

    private fun winOf(
        l: CoachLedger, pace: SpendingPace.Result, thisWeekBy: Map<Long, Long>, priorBy: Map<Long, Long>, n: Int,
    ): Win? {
        // A category the month's pace flags is not a win, whatever its week looked like.
        val flaggedIds = pace.flagged.map { it.categoryId }.toSet()
        val candidates = ArrayList<Win>()
        // Under the pace of the budget: the budget's share for the days gone, against everything spent this month.
        val curStart = l.current.start.toEpochDay().toInt()
        val elapsed = (l.todayDay - curStart + 1).coerceAtLeast(1)
        for (id in l.topExpenseIds) {
            val budget = Budgets.amountFor(id, l.current.yearMonth, l.input.budgets)?.takeIf { it > 0L } ?: continue
            val spentMonth = l.sum(l.expenses, curStart, l.todayDay, id)
            val expected = CoachMath.divHalfUp(budget * elapsed, pace.daysInPeriod.toLong())
            if (spentMonth > 0L && spentMonth < expected) candidates.add(Win(WinKind.UNDER_BUDGET, id, spentMonth, expected, expected - spentMonth))
        }
        // Under its own usual week.
        if (n > 0) {
            for ((id, week) in thisWeekBy) {
                val total = priorBy[id] ?: 0L
                val usual = CoachMath.divHalfUp(total, n.toLong())
                if (week > 0L && usual > week) candidates.add(Win(WinKind.UNDER_USUAL, id, week, usual, usual - week))
            }
        }
        return candidates.filter { it.categoryId !in flaggedIds }
            .sortedWith(compareByDescending<Win> { it.savedBy }.thenBy { it.kind.ordinal }.thenBy { it.categoryId })
            .firstOrNull()
    }

    private fun decisionOf(watch: Watch?, thisWeekBy: Map<Long, Long>, priorBy: Map<Long, Long>, n: Int): Decision? {
        if (watch == null || watch.kind != WatchKind.CATEGORY_PACE || n < MIN_DECISION_WEEKS) return null
        val usual = CoachMath.divFloor(priorBy[watch.categoryId] ?: 0L, n.toLong())
        val limit = usual / 100L * 100L
        if (limit <= 0L) return null
        return Decision(watch.categoryId, limit, n, usual, thisWeekBy[watch.categoryId] ?: 0L)
    }
}
