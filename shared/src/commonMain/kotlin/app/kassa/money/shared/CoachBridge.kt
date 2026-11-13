package app.kassa.money.shared

import app.kassa.money.domain.Balances
import app.kassa.money.domain.Coach
import app.kassa.money.domain.CoachFacts
import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.CoachReport
import app.kassa.money.domain.EmergencyFund
import app.kassa.money.domain.Money
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.SafeToSpend
import app.kassa.money.domain.SpendingPace
import app.kassa.money.domain.SubscriptionChange
import app.kassa.money.domain.WeekRules
import app.kassa.money.domain.WeeklyCheckIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/*
 * Coach (layer 1, the rules) for the screens: `LedgerView.coach(hidden, today, zone, firstWeekday)` carries what :domain's Coach rules worked
 * out in flat Swift-friendly types (Int, Long, String, Boolean, lists of simple rows, enums as their names). The rules are :domain's
 * (`CoachCore.kt` and the files beside it); nothing is worked out here, and no English sentence is built here: a row says what it is
 * (a kind code) and the screen words it from its own strings.
 *
 * Hide balances: every amount is `Money.format` text, so it is the mask while hidden. Hidden also takes away everything that would give a
 * size or a state away: percents are -1, the safe figure's state is "HIDDEN" (never a shortfall), the pace rows, the subscription rows
 * and the check-in's win, watch-out and decision are empty, tenths of a month are -1. A screen shows a plain "hidden" note instead.
 *
 * Privacy: only totals leave the phone, and only when the person asks a question. [LedgerView.coachAskPayload] is that payload; the
 * screens show [CoachData.facts] (the same keys, values masked while hidden) as "what a question sends". Coach never changes data.
 */

/** One thing the safe figure is spoken for by. [kind] is a `SafeToSpend.PartKind` name; [name] is the biggest one's name as written ("" for none). */
data class CoachPartData(val kind: String, val amountText: String, val count: Int, val refId: Long, val name: String)

/**
 * The safe-to-spend card. [state] is SPENDABLE, SHORTFALL, NO_ACCOUNTS or HIDDEN; [shortByText] is only a figure for SHORTFALL. [untilKind]
 * is NEXT_INCOME or MONTH_END and [untilDay] an epoch day. [usedPercent] is 0 to 100, -1 while hidden or without accounts. [basis] is HISTORY,
 * THIS_MONTH or NONE (where the usual daily spending came from).
 */
data class CoachSafeData(
    val state: String,
    val amountText: String,
    val shortByText: String,
    val untilDay: Int,
    val untilKind: String,
    val daysLeft: Int,
    val usedPercent: Int,
    val basis: String,
    val parts: List<CoachPartData>,
)

/** One top-level category against last month and its budget. [budgetText] is "" with no budget. */
data class CoachPaceRowData(
    val categoryId: Long,
    val categoryName: String,
    val spentText: String,
    val lastSoFarText: String,
    val lastTotalText: String,
    val projectionText: String,
    val budgetText: String,
    val hasLast: Boolean,
    val aboveLast: Boolean,
    val overBudget: Boolean,
    val paceOverBudget: Boolean,
    val overByText: String,
)

/** Spending pace: [rows] are the categories with spending this budget month, flagged ones first (empty while hidden). */
data class CoachPaceData(val dayOfPeriod: Int, val daysInPeriod: Int, val early: Boolean, val rows: List<CoachPaceRowData>)

/** A monthly charge that went up. */
data class CoachSubscriptionRowData(
    val key: String,
    val name: String,
    val categoryId: Long,
    val previousText: String,
    val latestText: String,
    val changeText: String,
    val lastDay: Int,
)

/** Subscription change: the monthly total now, what the rises add up to, how many monthly charges there are and the ones that rose. */
data class CoachSubscriptionData(
    val monthlyTotalText: String,
    val increaseText: String,
    val count: Int,
    val rows: List<CoachSubscriptionRowData>,
)

/** The emergency fund. [state] is READY, NOT_ENOUGH_DATA, NO_SPENDING or HIDDEN; [tenths] and [targetTenths] are tenths of a month (-1 while hidden, [targetTenths] 0 with no suggestion). */
data class CoachFundData(
    val state: String,
    val liquidText: String,
    val monthlySpendingText: String,
    val monthsUsed: Int,
    val tenths: Int,
    val targetTenths: Int,
    val weeklyText: String,
)

/** The check-in's win. [kind] is UNDER_BUDGET or UNDER_USUAL. */
data class CoachWinData(val kind: String, val categoryId: Long, val categoryName: String, val spentText: String, val referenceText: String, val savedByText: String)

/**
 * The check-in's watch-out. [kind] is CATEGORY_PACE or SUBSCRIPTION_RISE; [referenceKind] is BUDGET, LAST_MONTH or NONE; for a subscription
 * [name] is the charge's name and [amountText] its latest amount, [referenceText] the one before and [overByText] the rise.
 * [usedPercent] is the budget used (0 to 100), -1 with no budget.
 */
data class CoachWatchData(
    val kind: String,
    val categoryId: Long,
    val categoryName: String,
    val key: String,
    val name: String,
    val amountText: String,
    val projectionText: String,
    val referenceText: String,
    val referenceKind: String,
    val overByText: String,
    val dayOfPeriod: Int,
    val daysInPeriod: Int,
    val usedPercent: Int,
)

/**
 * A MONTHLY budget the screen may offer for a category (Kassa budgets are monthly). Only a suggestion: nothing is written until the person
 * saves it in the budget editor, which opens with exactly [monthlyBudgetCents].
 */
data class CoachDecisionData(
    val categoryId: Long,
    val categoryName: String,
    val monthlyBudgetText: String,
    /** The suggested monthly budget in cents, for the screen to hand to the budget editor when the person accepts. */
    val monthlyBudgetCents: Long,
    /** The same amount as the budget editor's field takes it (`Money.toInput`: the phone's decimal mark, no grouping). */
    val monthlyBudgetInput: String,
    /** The full budget months the average is over, 1 to 3. */
    val monthsUsed: Int,
    val spentThisMonthText: String,
    /** The category's budget now; "" with none. */
    val currentBudgetText: String,
)

/**
 * The weekly check-in. [state] is READY, NOT_ENOUGH_DATA or HIDDEN. [wins], [watches] and [decisions] hold at most one row each (a list so
 * an absent part is simply empty).
 */
data class CoachCheckInData(
    val state: String,
    val weekStartDay: Int,
    val spentText: String,
    val usualWeekText: String,
    val usualByNowText: String,
    val weeksCompared: Int,
    val wins: List<CoachWinData>,
    val watches: List<CoachWatchData>,
    val decisions: List<CoachDecisionData>,
)

/** One line of "what a question sends": the fact's key and its value as text (the mask while hidden). */
data class CoachFactRowData(val key: String, val valueText: String)

data class CoachData(
    val hidden: Boolean,
    val safe: CoachSafeData,
    val pace: CoachPaceData,
    val subscriptions: CoachSubscriptionData,
    val fund: CoachFundData,
    val checkIn: CoachCheckInData,
    val facts: List<CoachFactRowData>,
)

/** Gathers the Coach rules of :domain for [LedgerView]. Called only inside `LedgerView.shown`, so `Money` shows this ledger's currency and Hide balances. */
internal class CoachViews(private val v: LedgerView) {

    private val hidden: Boolean get() = Money.isHidden

    private fun text(cents: Long): String = Money.format(cents)

    internal fun inputFor(today: LocalDate, zone: TimeZone, firstWeekday: Int): CoachInput {
        val parts = v.parts
        // Repeats and the history look back about two years, like Android's `Ledger.repeats`; balances read every entry.
        val from = QueryWindows.historyFrom(today, zone)
        return CoachInput(
            accounts = parts.accounts,
            balances = Balances.of(parts.accounts, v.transactions),
            transactions = v.transactions.filter { it.epochMillis >= from },
            categories = parts.categories,
            budgets = parts.budgets,
            bills = parts.bills,
            loanTerms = parts.loanTerms,
            loanChanges = parts.loanChanges,
            loanPayments = parts.loanPayments,
            monthStartDay = v.monthStartDay,
            today = today,
            zone = zone,
            firstDay = WeekRules.firstDayOf(firstWeekday),
        )
    }

    fun report(today: LocalDate, zone: TimeZone, firstWeekday: Int): CoachReport = Coach.report(inputFor(today, zone, firstWeekday))

    fun payload(today: LocalDate, zone: TimeZone, firstWeekday: Int): String = CoachFacts.serialize(report(today, zone, firstWeekday).facts)

    fun build(today: LocalDate, zone: TimeZone, firstWeekday: Int): CoachData {
        val r = report(today, zone, firstWeekday)
        return CoachData(
            hidden = hidden,
            safe = safe(r.safe),
            pace = pace(r.pace),
            subscriptions = subscriptions(r.subscriptions),
            fund = fund(r.fund),
            checkIn = checkIn(r.checkIn),
            facts = r.facts.map { CoachFactRowData(it.key, factText(it)) },
        )
    }

    private fun name(categoryId: Long): String = v.categoryById[categoryId]?.name ?: ""

    private fun factText(f: CoachFacts.Fact): String = when {
        f.unit == CoachFacts.FactUnit.DAYS -> f.value.toString()
        hidden -> Money.MASK
        f.unit == CoachFacts.FactUnit.CENTS -> Money.format(f.value)
        else -> CoachFacts.valueText(f)
    }

    private fun safe(s: SafeToSpend.Result): CoachSafeData {
        val state = if (hidden && s.kind != SafeToSpend.Kind.NO_ACCOUNTS) "HIDDEN" else s.kind.name
        return CoachSafeData(
            state = state,
            amountText = text(s.amount),
            shortByText = if (s.kind == SafeToSpend.Kind.SHORTFALL || hidden) text(s.shortBy) else "",
            untilDay = s.untilDay,
            untilKind = s.untilKind.name,
            daysLeft = s.daysLeft,
            usedPercent = if (hidden || s.kind == SafeToSpend.Kind.NO_ACCOUNTS) -1 else s.usedPercent,
            basis = s.basis.name,
            parts = s.parts.map { CoachPartData(it.kind.name, text(it.amount), it.count, it.refId, it.name) },
        )
    }

    private fun pace(p: SpendingPace.Result): CoachPaceData = CoachPaceData(
        dayOfPeriod = p.dayOfPeriod,
        daysInPeriod = p.daysInPeriod,
        early = p.early,
        rows = if (hidden) emptyList() else p.rows.map {
            CoachPaceRowData(
                it.categoryId, name(it.categoryId), text(it.spent), text(it.lastSoFar), text(it.lastTotal), text(it.projection),
                it.budget?.let { b -> text(b) } ?: "", it.hasLast, it.aboveLast, it.overBudget, it.paceOverBudget, text(it.overBy),
            )
        },
    )

    private fun subscriptions(s: SubscriptionChange.Result): CoachSubscriptionData = CoachSubscriptionData(
        monthlyTotalText = text(s.monthlyTotal),
        increaseText = text(s.increase),
        count = s.count,
        rows = if (hidden) emptyList() else s.rows.map {
            CoachSubscriptionRowData(it.key, it.name, it.categoryId, text(it.previous), text(it.latest), text(it.change), it.lastDay)
        },
    )

    private fun fund(f: EmergencyFund.Result): CoachFundData = CoachFundData(
        state = if (hidden) "HIDDEN" else f.kind.name,
        liquidText = text(f.liquid),
        monthlySpendingText = text(f.monthlySpending),
        monthsUsed = f.monthsUsed,
        tenths = if (hidden) -1 else f.tenths,
        targetTenths = if (hidden) -1 else f.targetTenths,
        weeklyText = if (hidden || f.weeklyToTarget > 0L) text(f.weeklyToTarget) else "",
    )

    private fun checkIn(c: WeeklyCheckIn.Result): CoachCheckInData {
        val shown = !hidden
        val win = c.win
        val watch = c.watch
        val decision = c.decision
        return CoachCheckInData(
            state = if (hidden) "HIDDEN" else c.kind.name,
            weekStartDay = c.weekStartDay,
            spentText = text(c.spent),
            usualWeekText = text(c.usualWeek),
            usualByNowText = text(c.usualByNow),
            weeksCompared = c.weeksCompared,
            wins = if (shown && win != null) {
                listOf(CoachWinData(win.kind.name, win.categoryId, name(win.categoryId), text(win.spent), text(win.reference), text(win.savedBy)))
            } else emptyList(),
            watches = if (shown && watch != null) {
                listOf(
                    CoachWatchData(
                        watch.kind.name, watch.categoryId, name(watch.categoryId), watch.key, watch.name, text(watch.amount), text(watch.projection),
                        text(watch.reference), watch.referenceKind.name, text(watch.overBy), watch.dayOfPeriod, watch.daysInPeriod, watch.usedPercent,
                    ),
                )
            } else emptyList(),
            decisions = if (shown && decision != null) {
                listOf(
                    CoachDecisionData(
                        decision.categoryId, name(decision.categoryId), text(decision.monthlyBudget), decision.monthlyBudget, Money.toInput(decision.monthlyBudget), decision.monthsUsed,
                        text(decision.spentThisMonth), decision.currentBudget?.let { text(it) } ?: "",
                    ),
                )
            } else emptyList(),
        )
    }
}
