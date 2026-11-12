package app.kassa.money.domain

/**
 * Everything Coach worked out in one pass over one [CoachInput]: the five rules and the facts an opt-in "ask" would send.
 * [facts] is empty only when there is nothing to say (no liquid account and no entries).
 */
data class CoachReport(
    val safe: SafeToSpend.Result,
    val pace: SpendingPace.Result,
    val subscriptions: SubscriptionChange.Result,
    val fund: EmergencyFund.Result,
    val checkIn: WeeklyCheckIn.Result,
    val facts: List<CoachFacts.Fact>,
)

object Coach {
    /** Runs every Coach rule on [input]. */
    fun report(input: CoachInput): CoachReport {
        val l = CoachLedger(input)
        val safe = SafeToSpend.compute(l)
        val pace = SpendingPace.compute(l)
        val subs = SubscriptionChange.compute(l)
        val fund = EmergencyFund.compute(l)
        val checkIn = WeeklyCheckIn.compute(l)
        return CoachReport(safe, pace, subs, fund, checkIn, CoachFacts.of(l, safe, pace, subs, fund, checkIn))
    }
}

/**
 * The exact aggregate payload an opt-in "ask" would send. Privacy rule: only totals leave the phone. A fact is a system-stable key
 * (lowercase letters and underscores, listed in [KEYS]) and a number: cents, tenths of a month, a count, days or a percent. Never a
 * merchant, a note, an account, category or person name, an id, a date or any text the person typed. Category figures are not sent at
 * all (their names are the person's own words); only sums across categories are. Coach itself never changes data, and nothing here
 * sends anything: this builds the list, and the screen shows it to the person before anything is sent.
 */
object CoachFacts {

    enum class FactUnit { CENTS, TENTHS_OF_MONTH, COUNT, DAYS, PERCENT }

    data class Fact(val key: String, val value: Long, val unit: FactUnit)

    /** Every key a payload can hold, in the order it is written. */
    val KEYS: List<String> = listOf(
        "safe_to_spend", "safe_to_spend_short_by", "safe_to_spend_days", "safe_to_spend_used_percent",
        "liquid_balance", "upcoming_due_total", "upcoming_bills_total", "upcoming_recurring_total", "upcoming_cards_total",
        "upcoming_loans_total", "usual_daily_spending",
        "spending_month", "spending_prev_month", "spending_prev_month_same_day", "income_month", "income_prev_month",
        "budget_total", "budget_spent_in_budgeted", "budget_month_day", "budget_month_days",
        "categories_over_budget", "categories_above_last_month",
        "week_spending", "week_usual_spending",
        "subscriptions_monthly_total", "subscriptions_increase",
        "liquid_balance_months", "usual_monthly_spending", "emergency_fund_weekly_suggestion",
    )

    internal fun of(
        l: CoachLedger, safe: SafeToSpend.Result, pace: SpendingPace.Result, subs: SubscriptionChange.Result,
        fund: EmergencyFund.Result, checkIn: WeeklyCheckIn.Result,
    ): List<Fact> {
        val out = ArrayList<Fact>()
        fun cents(key: String, v: Long) { out.add(Fact(key, v, FactUnit.CENTS)) }
        fun count(key: String, v: Int) { out.add(Fact(key, v.toLong(), FactUnit.COUNT)) }

        if (safe.kind != SafeToSpend.Kind.NO_ACCOUNTS) {
            cents("safe_to_spend", safe.amount)
            cents("safe_to_spend_short_by", safe.shortBy)
            out.add(Fact("safe_to_spend_days", safe.daysLeft.toLong(), FactUnit.DAYS))
            out.add(Fact("safe_to_spend_used_percent", safe.usedPercent.toLong(), FactUnit.PERCENT))
            cents("liquid_balance", safe.liquid)
            cents("upcoming_due_total", safe.amountOf(SafeToSpend.PartKind.BILLS) + safe.amountOf(SafeToSpend.PartKind.RECURRING) +
                safe.amountOf(SafeToSpend.PartKind.CARDS) + safe.amountOf(SafeToSpend.PartKind.LOANS))
            cents("upcoming_bills_total", safe.amountOf(SafeToSpend.PartKind.BILLS))
            cents("upcoming_recurring_total", safe.amountOf(SafeToSpend.PartKind.RECURRING))
            cents("upcoming_cards_total", safe.amountOf(SafeToSpend.PartKind.CARDS))
            cents("upcoming_loans_total", safe.amountOf(SafeToSpend.PartKind.LOANS))
            cents("usual_daily_spending", safe.usualDaily)
        }

        val cur = l.current
        val prev = l.periodBack(1)
        val curStart = cur.start.toEpochDay().toInt()
        val prevStart = prev.start.toEpochDay().toInt()
        val prevEnd = prev.endInclusive.toEpochDay().toInt()
        val sameDayEnd = minOf(prevStart + pace.dayOfPeriod - 1, prevEnd)
        cents("spending_month", l.sum(l.expenses, curStart, l.todayDay))
        cents("spending_prev_month", l.sum(l.expenses, prevStart, prevEnd))
        cents("spending_prev_month_same_day", l.sum(l.expenses, prevStart, sameDayEnd))
        cents("income_month", l.sum(l.incomes, curStart, l.todayDay))
        cents("income_prev_month", l.sum(l.incomes, prevStart, prevEnd))

        val budgeted = l.topExpenseIds.mapNotNull { id -> Budgets.amountFor(id, cur.yearMonth, l.input.budgets)?.takeIf { it > 0L }?.let { id to it } }
        if (budgeted.isNotEmpty()) {
            cents("budget_total", budgeted.sumOf { it.second })
            cents("budget_spent_in_budgeted", budgeted.sumOf { l.sum(l.expenses, curStart, l.todayDay, it.first) })
        }
        out.add(Fact("budget_month_day", pace.dayOfPeriod.toLong(), FactUnit.DAYS))
        out.add(Fact("budget_month_days", pace.daysInPeriod.toLong(), FactUnit.DAYS))
        count("categories_over_budget", pace.rows.count { it.overBudget })
        count("categories_above_last_month", pace.rows.count { it.aboveLast })

        if (checkIn.kind == WeeklyCheckIn.Kind.READY) {
            cents("week_spending", checkIn.spent)
            if (checkIn.hasUsual) cents("week_usual_spending", checkIn.usualWeek)
        }
        if (subs.count > 0) {
            cents("subscriptions_monthly_total", subs.monthlyTotal)
            cents("subscriptions_increase", subs.increase)
        }
        if (fund.kind == EmergencyFund.Kind.READY) {
            out.add(Fact("liquid_balance_months", fund.tenths.toLong(), FactUnit.TENTHS_OF_MONTH))
            cents("usual_monthly_spending", fund.monthlySpending)
            if (fund.weeklyToTarget > 0L) cents("emergency_fund_weekly_suggestion", fund.weeklyToTarget)
        }
        return out
    }

    /** The facts as the plain text a question would carry: one `key: value` line each, cents as 482.00, tenths of a month as 2.1, the rest whole numbers. No currency, no locale. */
    fun serialize(facts: List<Fact>): String = facts.joinToString("\n") { "${it.key}: ${valueText(it)}" }

    /** One value as plain text, the same for every phone language. */
    fun valueText(f: Fact): String = when (f.unit) {
        FactUnit.CENTS -> plainCents(f.value)
        FactUnit.TENTHS_OF_MONTH -> "${f.value / 10}.${f.value % 10}"
        else -> f.value.toString()
    }

    private fun plainCents(cents: Long): String {
        val abs = if (cents < 0L) -cents else cents
        val minor = (abs % 100).toString().padStart(2, '0')
        return (if (cents < 0L) "-" else "") + (abs / 100).toString() + "." + minor
    }
}
