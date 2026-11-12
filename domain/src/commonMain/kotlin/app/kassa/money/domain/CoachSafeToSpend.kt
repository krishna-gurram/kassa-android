package app.kassa.money.domain

/**
 * Safe to spend: the money left until the next income day, after what is already known to be coming out and the person's usual daily
 * spending.
 *
 * - Money: checking, savings, cash and prepaid balances of open accounts (fixed deposits, investments and other assets are not liquid).
 * - Until: the next expected payday (the biggest weekly, two-weekly or monthly income that [Repeats] finds, strictly after today and at
 *   most [HORIZON_DAYS] away), otherwise the first day of the next budget month ([UntilKind.MONTH_END]).
 * - Known payments due before that day (earlier ones that are still waiting too): bills ([DueItems] bills), card balances payable, loan
 *   payments, and fixed expenses that recur (monthly, every 3 months, yearly) and are not a bill yet, on the day they are expected.
 * - Usual spending: counted spending of the previous [HISTORY_MONTHS] full budget months (or, for a new ledger, the days of this month
 *   so far) without the known payments above, per day, times the days left (today counts as a whole day: a little conservative).
 *
 * The figure shown is never negative: a plan that does not fit says [Result.shortBy] instead. [Result.usedPercent] is how much of the
 * money is already spoken for, 0 to 100.
 */
object SafeToSpend {
    const val HISTORY_MONTHS = 3

    /** A payday further away than this is not used; the month's end is. */
    const val HORIZON_DAYS = 62

    /** Fewest days of earlier months that make a usual day. */
    const val MIN_HISTORY_DAYS = 14

    /** Fewest elapsed days of this month that make a usual day when earlier months are too short. */
    const val MIN_MONTH_DAYS = 7

    enum class Kind { SPENDABLE, SHORTFALL, NO_ACCOUNTS }

    enum class UntilKind { NEXT_INCOME, MONTH_END }

    /** Where the usual daily spending came from. */
    enum class Basis { HISTORY, THIS_MONTH, NONE }

    enum class PartKind { BILLS, RECURRING, CARDS, LOANS, USUAL_SPENDING }

    /**
     * One thing the money is spoken for by. [refId] is the biggest one's id (a bill, an account, or for [PartKind.USUAL_SPENDING] the
     * top-level category usually spent most in), 0 for none; [name] is its name as the person wrote it ("" for none).
     */
    data class Part(val kind: PartKind, val amount: Long, val count: Int, val refId: Long, val name: String)

    data class Result(
        val kind: Kind,
        /** Never negative. */
        val amount: Long,
        /** How far the plan is from fitting; zero unless [kind] is SHORTFALL. */
        val shortBy: Long,
        /** Epoch day the money has to last until (the day income is expected, or the next budget month starts). */
        val untilDay: Int,
        val untilKind: UntilKind,
        val daysLeft: Int,
        /** Share of the money already spoken for, 0 to 100. */
        val usedPercent: Int,
        val liquid: Long,
        val usualDaily: Long,
        val basis: Basis,
        val parts: List<Part>,
    ) {
        /** What the parts add up to. */
        val committed: Long get() = parts.sumOf { it.amount }

        fun part(kind: PartKind): Part? = parts.firstOrNull { it.kind == kind }

        /** The amount of one part, 0 when it has none. */
        fun amountOf(kind: PartKind): Long = part(kind)?.amount ?: 0L
    }

    fun compute(input: CoachInput): Result = compute(CoachLedger(input))

    internal fun compute(l: CoachLedger): Result {
        val input = l.input
        val until = untilOf(l)
        val untilDay = until.first
        val daysLeft = (untilDay - l.todayDay).coerceAtLeast(1)
        if (!l.hasLiquidAccount) {
            return Result(Kind.NO_ACCOUNTS, 0L, 0L, untilDay, until.second, daysLeft, 0, 0L, 0L, Basis.NONE, emptyList())
        }
        val liquid = l.liquidBalance()
        val parts = ArrayList<Part>()

        val due = DueItems.build(
            l.activeAccounts, input.balances, input.transactions, input.bills, input.loanTerms, input.loanChanges, input.loanPayments,
            input.today, input.zone,
        ).filter { it.dueDay < untilDay && (it.amount ?: 0L) > 0L }
        dueSum(PartKind.BILLS, due.filter { it.kind == DueKind.BILL })?.let { parts.add(it) }
        recurringPart(l, untilDay)?.let { parts.add(it) }
        dueSum(PartKind.CARDS, due.filter { it.kind == DueKind.CARD })?.let { parts.add(it) }
        dueSum(PartKind.LOANS, due.filter { it.kind == DueKind.LOAN })?.let { parts.add(it) }

        val usual = usualOf(l)
        val variable = usual.daily * daysLeft
        if (variable > 0L) parts.add(Part(PartKind.USUAL_SPENDING, variable, daysLeft, usual.topCategory, l.categoryName(usual.topCategory)))

        val committed = parts.sumOf { it.amount }
        val left = liquid - committed
        val used = if (liquid <= 0L) 100 else CoachMath.divHalfUp(committed * 100, liquid).coerceAtMost(100L).toInt()
        return Result(
            kind = if (left < 0L) Kind.SHORTFALL else Kind.SPENDABLE,
            amount = left.coerceAtLeast(0L),
            shortBy = (-left).coerceAtLeast(0L),
            untilDay = untilDay,
            untilKind = until.second,
            daysLeft = daysLeft,
            usedPercent = used,
            liquid = liquid,
            usualDaily = usual.daily,
            basis = usual.basis,
            parts = parts,
        )
    }

    /** The day the money must last until and why: the next payday, else the start of the next budget month. */
    private fun untilOf(l: CoachLedger): Pair<Int, UntilKind> {
        val today = l.input.today
        val income = l.repeats.firstOrNull { it.kind == TxKind.INCOME && it.cadence in PAYDAY_CADENCES }
        if (income != null) {
            var d = income.next(today)
            while (!d.isAfter(today)) d = income.cadence.after(d)
            val day = d.toEpochDay().toInt()
            if (day - l.todayDay <= HORIZON_DAYS) return Pair(day, UntilKind.NEXT_INCOME)
        }
        return Pair(l.current.endInclusive.toEpochDay().toInt() + 1, UntilKind.MONTH_END)
    }

    private fun dueSum(kind: PartKind, list: List<DueItem>): Part? {
        if (list.isEmpty()) return null
        val biggest = list.sortedWith(compareByDescending<DueItem> { it.amount ?: 0L }.thenBy { it.id }).first()
        return Part(kind, list.sumOf { it.amount ?: 0L }, list.size, biggest.id, biggest.name)
    }

    /** Fixed recurring expenses that are not a bill and fall due before [untilDay]: the amount of their last charge. */
    private fun recurringPart(l: CoachLedger, untilDay: Int): Part? {
        val billKeys = l.input.bills.map { Repeats.key(it.name) }.toSet()
        val expected = l.recurringRepeats
            .filter { it.key !in billKeys && it.next(l.input.today).toEpochDay().toInt() < untilDay }
            .sortedWith(compareByDescending<Repeat> { it.typical }.thenBy { it.key })
        if (expected.isEmpty()) return null
        val first = expected.first()
        return Part(PartKind.RECURRING, expected.sumOf { it.typical }, expected.size, first.categoryId ?: 0L, first.name)
    }

    private class Usual(val daily: Long, val basis: Basis, val topCategory: Long)

    /** The usual day to day spending per day, without known payments. */
    private fun usualOf(l: CoachLedger): Usual {
        val first = l.firstEntryDay ?: return Usual(0L, Basis.NONE, 0L)
        val windows = ArrayList<IntRange>()
        for (p in l.fullPriorPeriods(HISTORY_MONTHS)) windows.add(p.start.toEpochDay().toInt()..p.endInclusive.toEpochDay().toInt())
        // A month the ledger began in partway still counts, from its first entry.
        val partial = (1..HISTORY_MONTHS).map { l.periodBack(it) }.firstOrNull { p ->
            val s = p.start.toEpochDay().toInt()
            val e = p.endInclusive.toEpochDay().toInt()
            s < first && first <= e
        }
        if (partial != null) windows.add(first..partial.endInclusive.toEpochDay().toInt())
        var days = windows.sumOf { it.last - it.first + 1 }
        var basis = Basis.HISTORY
        if (days < MIN_HISTORY_DAYS) {
            windows.clear()
            val from = maxOf(l.current.start.toEpochDay().toInt(), first)
            val to = l.todayDay - 1
            days = to - from + 1
            if (days < MIN_MONTH_DAYS) return Usual(0L, Basis.NONE, 0L)
            windows.add(from..to)
            basis = Basis.THIS_MONTH
        }
        var total = 0L
        val byTop = HashMap<Long, Long>()
        for (e in l.expenses) {
            if (e.id in l.recurringIds || windows.none { e.day in it }) continue
            total += e.amount
            if (e.top != 0L) byTop[e.top] = (byTop[e.top] ?: 0L) + e.amount
        }
        val top = byTop.entries.sortedWith(compareByDescending<Map.Entry<Long, Long>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: 0L
        return Usual(CoachMath.divHalfUp(total, days.toLong()), basis, top)
    }

    private val PAYDAY_CADENCES = setOf(Cadence.WEEKLY, Cadence.BIWEEKLY, Cadence.MONTHLY)
}
