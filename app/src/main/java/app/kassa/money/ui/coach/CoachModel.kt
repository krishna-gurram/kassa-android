package app.kassa.money.ui.coach

import app.kassa.money.domain.CoachFacts
import app.kassa.money.domain.CoachReport
import app.kassa.money.domain.EmergencyFund
import app.kassa.money.domain.Money
import app.kassa.money.domain.SafeToSpend
import app.kassa.money.domain.SpendingPace
import app.kassa.money.domain.SubscriptionChange
import app.kassa.money.domain.WeeklyCheckIn

/*
 * What the Coach screens show, decided in plain Kotlin (no Android, no strings) so the choices have JVM tests: which cards appear,
 * where a tap goes, what the Overview card says, what Hide balances takes away. The rules themselves are :domain's (`Coach.report`);
 * nothing here works out a figure of money beyond a unit change, and nothing here writes anything. The screens (CoachScreens.kt) word
 * what this says from string resources.
 */

/** What the safe-to-spend hero is in: a figure, a shortfall, no account to read, or hidden (Hide balances is on). */
enum class SafeKind { SPENDABLE, SHORTFALL, NO_ACCOUNTS, HIDDEN }

/** Why a category is flagged, strongest first: over its budget already, on pace to pass it, or on pace to pass last month. */
enum class PaceKind { OVER_BUDGET, PACE_OVER_BUDGET, ABOVE_LAST }

/** The check-in as a whole: ready, too little history, or hidden. */
enum class CheckInState { READY, NOT_ENOUGH_DATA, HIDDEN }

/** How the check-in page opens, from what it found. */
enum class Headline { WIN_AND_WATCH, WIN, WATCH, QUIET }

/** One insight card on the Coach page. */
sealed interface InsightCard {
    /** A category that is flagged this month ([kind]), with the pace row behind it. */
    data class Pace(val kind: PaceKind, val row: SpendingPace.Row, val dayOfPeriod: Int, val daysInPeriod: Int) : InsightCard

    /** The monthly charge that went up most, the total of monthly charges now and what the rises add up to. */
    data class Subscription(val row: SubscriptionChange.Row, val monthlyTotal: Long, val increase: Long) : InsightCard

    /** The emergency fund, only when it could be worked out. */
    data class Fund(val fund: EmergencyFund.Result) : InsightCard
}

/** Where a tap on a card goes: an existing page. */
sealed interface Target {
    data class Category(val id: Long) : Target
    data object Repeating : Target
}

/** The weekly check-in as the screens read it: at most one win, one watch-out and one decision. */
data class CheckInView(
    val state: CheckInState,
    val weekStartDay: Int,
    val spent: Long,
    val usualByNow: Long,
    val hasUsual: Boolean,
    val win: WeeklyCheckIn.Win?,
    val watch: WeeklyCheckIn.Watch?,
    val decision: WeeklyCheckIn.Decision?,
) {
    val headline: Headline
        get() = when {
            win != null && watch != null -> Headline.WIN_AND_WATCH
            win != null -> Headline.WIN
            watch != null -> Headline.WATCH
            else -> Headline.QUIET
        }

    /** Whether there is anything to say: a win or a watch-out (a decision always comes with a watch-out). */
    val hasContent: Boolean get() = win != null || watch != null
}

/** Everything the Coach page and the check-in page read. */
data class CoachView(
    val safeKind: SafeKind,
    val safe: SafeToSpend.Result,
    val cards: List<InsightCard>,
    val checkIn: CheckInView,
)

/** What the Overview card says: the watch-out, else the win, else (balances hidden) only that a check-in is waiting. */
enum class TeaserKind { WATCH, WIN, NEUTRAL }

data class Teaser(val kind: TeaserKind, val watch: WeeklyCheckIn.Watch?, val win: WeeklyCheckIn.Win?)

object CoachModel {

    /** The most cards the Coach page shows: pace, subscriptions and emergency fund, one each. */
    const val MAX_CARDS = 3

    /**
     * The Coach page from [report]. While [hidden] (Hide balances) the safe figure has no state, there are no cards, and the check-in has no win,
     * watch-out or decision: a flag, a "rose" or a decision would give a figure's size away. [showCards] is Settings > Coach > Insight cards.
     */
    fun view(report: CoachReport, hidden: Boolean, showCards: Boolean = true): CoachView {
        val c = report.checkIn
        val ready = c.kind == WeeklyCheckIn.Kind.READY
        val checkIn = CheckInView(
            state = when {
                !ready -> CheckInState.NOT_ENOUGH_DATA
                hidden -> CheckInState.HIDDEN
                else -> CheckInState.READY
            },
            weekStartDay = c.weekStartDay,
            spent = c.spent,
            usualByNow = c.usualByNow,
            hasUsual = c.hasUsual,
            win = if (hidden) null else c.win,
            watch = if (hidden) null else c.watch,
            decision = if (hidden) null else c.decision,
        )
        val safeKind = when {
            report.safe.kind == SafeToSpend.Kind.NO_ACCOUNTS -> SafeKind.NO_ACCOUNTS
            hidden -> SafeKind.HIDDEN
            report.safe.kind == SafeToSpend.Kind.SHORTFALL -> SafeKind.SHORTFALL
            else -> SafeKind.SPENDABLE
        }
        return CoachView(safeKind, report.safe, if (hidden || !showCards) emptyList() else cards(report), checkIn)
    }

    /** The cards for the rows that exist, in a fixed order: the flagged category, the subscription rise, the emergency fund. */
    fun cards(report: CoachReport): List<InsightCard> {
        val out = ArrayList<InsightCard>()
        report.pace.flagged.firstOrNull { it.categoryId != 0L }?.let {
            out.add(InsightCard.Pace(paceKind(it), it, report.pace.dayOfPeriod, report.pace.daysInPeriod))
        }
        report.subscriptions.rows.firstOrNull()?.let {
            out.add(InsightCard.Subscription(it, report.subscriptions.monthlyTotal, report.subscriptions.increase))
        }
        if (report.fund.kind == EmergencyFund.Kind.READY) out.add(InsightCard.Fund(report.fund))
        return out
    }

    fun paceKind(row: SpendingPace.Row): PaceKind = when {
        row.overBudget -> PaceKind.OVER_BUDGET
        row.paceOverBudget -> PaceKind.PACE_OVER_BUDGET
        else -> PaceKind.ABOVE_LAST
    }

    /** The pace kind of the check-in's category watch-out: a budget reference already passed is over budget, otherwise on pace to pass it, else last month. */
    fun paceKind(watch: WeeklyCheckIn.Watch): PaceKind = when (watch.referenceKind) {
        WeeklyCheckIn.Reference.BUDGET -> if (watch.amount > watch.reference) PaceKind.OVER_BUDGET else PaceKind.PACE_OVER_BUDGET
        else -> PaceKind.ABOVE_LAST
    }

    /**
     * Where a tap on [card] goes, or null when it goes nowhere. The emergency fund card goes nowhere on purpose: the Accounts tab lists
     * credit cards first, with their due lines, which read as if the fund were about a card.
     */
    fun target(card: InsightCard): Target? = when (card) {
        is InsightCard.Pace -> Target.Category(card.row.categoryId)
        is InsightCard.Subscription -> Target.Repeating
        is InsightCard.Fund -> null
    }

    /**
     * The Overview card, or null when it should not show: the weekly check-in is off ([enabled] false), the check-in has too little data, the
     * person chose "Not now" for this very week ([dismissedWeek] is the week's start day), or it has nothing to say. While balances are hidden it
     * is only a neutral line, since a win or a watch-out would give a state away.
     */
    fun teaser(view: CoachView, enabled: Boolean, dismissedWeek: Int): Teaser? {
        val c = view.checkIn
        if (!enabled || c.state == CheckInState.NOT_ENOUGH_DATA || dismissedWeek == c.weekStartDay) return null
        if (c.state == CheckInState.HIDDEN) return Teaser(TeaserKind.NEUTRAL, null, null)
        return when {
            c.watch != null -> Teaser(TeaserKind.WATCH, c.watch, c.win)
            c.win != null -> Teaser(TeaserKind.WIN, null, c.win)
            else -> null
        }
    }

    /** [items] as one phrase: "a", "a and b", "a, b and c", with the joining words given by the caller (they come from string resources). */
    fun joinList(items: List<String>, join: (String, String) -> String, last: (String, String) -> String): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> {
            var acc = items[0]
            for (i in 1 until items.size - 1) acc = join(acc, items[i])
            last(acc, items[items.size - 1])
        }
    }

    /** Tenths of a month as text with the phone's decimal mark: 21 is "2.1". */
    fun tenthsText(tenths: Int, mark: String): String = "${tenths / 10}$mark${tenths % 10}"

    /** The used share as a 0 to 1 fraction for the bar; 0 for an unknown share (-1). */
    fun usedFraction(percent: Int): Float = if (percent < 0) 0f else (percent.coerceAtMost(100) / 100f)

    /** How this week compares with a usual week by now: UNDER, OVER or SAME, with the difference in cents (always at least 0). */
    enum class Versus { NONE, UNDER, OVER, SAME }

    fun versus(c: CheckInView): Versus = when {
        !c.hasUsual -> Versus.NONE
        c.spent < c.usualByNow -> Versus.UNDER
        c.spent > c.usualByNow -> Versus.OVER
        else -> Versus.SAME
    }

    fun versusAmount(c: CheckInView): Long = Math.abs(c.spent - c.usualByNow)

    /**
     * The facts a question would send, as `key` and value lines: cents as plain numbers (482.00), tenths of a month as 2.1, counts and days
     * as they are. While [hidden] every value but the days is the mask, as the amounts everywhere (the same rule the iOS bridge follows).
     */
    fun factRows(facts: List<CoachFacts.Fact>, hidden: Boolean): List<Pair<String, String>> = facts.map { f ->
        f.key to when {
            f.unit == CoachFacts.FactUnit.DAYS -> f.value.toString()
            hidden -> Money.MASK
            else -> CoachFacts.valueText(f)
        }
    }
}
