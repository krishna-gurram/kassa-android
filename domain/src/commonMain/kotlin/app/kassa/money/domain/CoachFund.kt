package app.kassa.money.domain

/**
 * Emergency fund: the liquid money (checking, savings, cash and prepaid of open accounts; not fixed deposits, investments or other
 * assets) divided by the average monthly counted spending of the previous [MONTHS] full budget months, in tenths of a month. A month
 * only counts when the first entry is on or before its first day, so a new ledger is not compared against months it did not have.
 *
 * The suggestion is a weekly amount that, saved for [WEEKS] weeks, reaches the next half month of spending. Once the fund covers
 * [GOAL_TENTHS] tenths (6 months) nothing more is suggested.
 */
object EmergencyFund {
    const val MONTHS = 3
    const val WEEKS = 4

    /** The fund reads at most this many tenths (10 years), so a near-empty ledger cannot overflow or show absurd figures. */
    const val CAP_TENTHS = 1200

    /** No further saving is suggested at this many tenths of a month (6 months). */
    const val GOAL_TENTHS = 60

    enum class Kind { READY, NOT_ENOUGH_DATA, NO_SPENDING }

    data class Result(
        val kind: Kind,
        /** Liquid money, at least 0. */
        val liquid: Long,
        /** Average monthly counted spending of the full months used; 0 unless [kind] is READY. */
        val monthlySpending: Long,
        /** Full months used, 0 to [MONTHS]. */
        val monthsUsed: Int,
        /** Months of spending the liquid money covers, in tenths, rounded down; 0 unless READY. */
        val tenths: Int,
        /** The next half month above [tenths]; 0 when none is suggested. */
        val targetTenths: Int,
        /** What to put aside each week for [WEEKS] weeks to reach [targetTenths]; 0 when none is suggested. */
        val weeklyToTarget: Long,
    )

    fun compute(input: CoachInput): Result = compute(CoachLedger(input))

    internal fun compute(l: CoachLedger): Result {
        val liquid = l.liquidBalance().coerceAtLeast(0L)
        val periods = l.fullPriorPeriods(MONTHS)
        if (periods.isEmpty()) return Result(Kind.NOT_ENOUGH_DATA, liquid, 0L, 0, 0, 0, 0L)
        var total = 0L
        for (p in periods) total += l.sum(l.expenses, p.start.toEpochDay().toInt(), p.endInclusive.toEpochDay().toInt())
        val monthly = CoachMath.divHalfUp(total, periods.size.toLong())
        if (monthly <= 0L) return Result(Kind.NO_SPENDING, liquid, 0L, periods.size, 0, 0, 0L)
        val tenths = CoachMath.divFloor(liquid * 10, monthly).coerceAtMost(CAP_TENTHS.toLong()).toInt()
        if (tenths >= GOAL_TENTHS) return Result(Kind.READY, liquid, monthly, periods.size, tenths, 0, 0L)
        val target = (tenths / 5 + 1) * 5
        val needed = CoachMath.divCeil(target.toLong() * monthly, 10L) - liquid
        val weekly = CoachMath.divCeil(needed, WEEKS.toLong())
        return Result(Kind.READY, liquid, monthly, periods.size, tenths, target, weekly)
    }
}
