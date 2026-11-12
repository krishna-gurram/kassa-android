package app.kassa.money.domain

/**
 * Spending pace: for each top-level expense category, what has been spent so far this budget month against the same day of last month
 * and against its budget, and where it is on pace to finish.
 *
 * Projection = spent so far / days into the month * days in the month, rounded half up, for the day to day part of the spending. Known
 * payments that already went out this month (rent, a bill, a loan payment: [CoachLedger.recurringIds]) are added as they are and never scaled up,
 * because one rent payment on the 1st is not a rate of spending. With none of those the projection is exactly the formula above. Days are the budget month's own, so a budget
 * month that starts on the 15th, or a February of 28 or 29 days, is measured against its real length. "Same day last month" is the
 * same count of days into the previous budget month (all of it when that month was shorter).
 *
 * Before [MIN_DAYS] days into the month a projection is too jumpy to say anything (one big bill on day 1 would look like a month of
 * it), so only the plain fact "already over its budget" is flagged then.
 */
object SpendingPace {
    const val MIN_DAYS = 7

    data class Row(
        val categoryId: Long,
        val spent: Long,
        /** Last month's spending through the same day of the month. */
        val lastSoFar: Long,
        /** Last month's whole spending. */
        val lastTotal: Long,
        val projection: Long,
        /** This month's budget; null with none. */
        val budget: Long?,
        /** Last month had spending in this category, so there is something to compare with. */
        val hasLast: Boolean,
        /** On pace to finish above last month's total. */
        val aboveLast: Boolean,
        /** Already past the budget. */
        val overBudget: Boolean,
        /** Not over yet, but on pace to finish past the budget. */
        val paceOverBudget: Boolean,
        /** How far the projection is past the budget or last month's total, whichever is further; never negative. */
        val overBy: Long,
    ) {
        val flagged: Boolean get() = aboveLast || overBudget || paceOverBudget
    }

    data class Result(
        /** Days into the budget month, counting today; at least 1. */
        val dayOfPeriod: Int,
        val daysInPeriod: Int,
        /** Too early in the month for a projection; see [MIN_DAYS]. */
        val early: Boolean,
        /** Flagged rows first, then by how far past, then by what was spent; only categories with spending this month. */
        val rows: List<Row>,
    ) {
        val flagged: List<Row> get() = rows.filter { it.flagged }
    }

    fun compute(input: CoachInput): Result = compute(CoachLedger(input))

    internal fun compute(l: CoachLedger): Result {
        val cur = l.current
        val prev = l.periodBack(1)
        val curStart = cur.start.toEpochDay().toInt()
        val n = (l.todayDay - curStart + 1).coerceAtLeast(1)
        val days = cur.days.toInt()
        val prevStart = prev.start.toEpochDay().toInt()
        val prevEnd = prev.endInclusive.toEpochDay().toInt()
        val lastEnd = minOf(prevStart + n - 1, prevEnd)
        val early = n < MIN_DAYS

        val spentBy = HashMap<Long, Long>()
        val knownBy = HashMap<Long, Long>()
        val lastSoFarBy = HashMap<Long, Long>()
        val lastTotalBy = HashMap<Long, Long>()
        for (e in l.expenses) {
            if (e.top !in l.topExpenseIds) continue
            if (e.day in curStart..l.todayDay) {
                spentBy[e.top] = (spentBy[e.top] ?: 0L) + e.amount
                if (e.id in l.recurringIds) knownBy[e.top] = (knownBy[e.top] ?: 0L) + e.amount
            }
            if (e.day in prevStart..prevEnd) {
                lastTotalBy[e.top] = (lastTotalBy[e.top] ?: 0L) + e.amount
                if (e.day <= lastEnd) lastSoFarBy[e.top] = (lastSoFarBy[e.top] ?: 0L) + e.amount
            }
        }
        val rows = ArrayList<Row>()
        for ((id, spent) in spentBy) {
            if (spent <= 0L) continue
            val lastTotal = lastTotalBy[id] ?: 0L
            val known = knownBy[id] ?: 0L
            val projection = CoachMath.divHalfUp((spent - known) * days, n.toLong()) + known
            val budget = Budgets.amountFor(id, cur.yearMonth, l.input.budgets)?.takeIf { it > 0L }
            val hasLast = lastTotal > 0L
            val overBudget = budget != null && spent > budget
            val paceOver = !early && !overBudget && budget != null && projection > budget
            val aboveLast = !early && hasLast && projection > lastTotal
            val overBy = listOfNotNull(
                budget?.let { projection - it },
                if (hasLast) projection - lastTotal else null,
            ).maxOrNull()?.coerceAtLeast(0L) ?: 0L
            rows.add(Row(id, spent, lastSoFarBy[id] ?: 0L, lastTotal, projection, budget, hasLast, aboveLast, overBudget, paceOver, overBy))
        }
        rows.sortWith(compareByDescending<Row> { it.flagged }.thenByDescending { it.overBy }.thenByDescending { it.spent }.thenBy { it.categoryId })
        return Result(n, days, early, rows)
    }
}

/**
 * Subscription change: of the fixed charges that come back monthly (what [Repeats] finds: subscriptions, rent, insurance), which ones
 * went up in their latest charge, what the monthly total is now and how much of it is the rise. A loan's own payments are never in it.
 */
object SubscriptionChange {

    data class Row(
        val key: String,
        /** The charge's name as the person wrote it. */
        val name: String,
        val categoryId: Long,
        val previous: Long,
        val latest: Long,
        /** Latest minus previous; above zero. */
        val change: Long,
        /** Epoch day of the latest charge. */
        val lastDay: Int,
    )

    data class Result(
        /** The ones that went up, the biggest rise first. */
        val rows: List<Row>,
        /** What every monthly fixed charge costs now: each one's latest amount. */
        val monthlyTotal: Long,
        /** What the rises add up to. */
        val increase: Long,
        /** How many monthly fixed charges there are. */
        val count: Int,
    )

    fun compute(input: CoachInput): Result = compute(CoachLedger(input))

    internal fun compute(l: CoachLedger): Result {
        val all = l.monthlyRepeats
        val risen = all.filter { it.change > 0L }
            .map { Row(it.key, it.name, it.categoryId ?: 0L, it.amounts[it.amounts.size - 2], it.amounts.last(), it.change, it.last.toEpochDay().toInt()) }
            .sortedWith(compareByDescending<Row> { it.change }.thenBy { it.key })
        return Result(risen, all.sumOf { it.amounts.last() }, risen.sumOf { it.change }, all.size)
    }
}
