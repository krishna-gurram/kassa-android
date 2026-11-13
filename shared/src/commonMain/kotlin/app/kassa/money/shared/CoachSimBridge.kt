package app.kassa.money.shared

import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.CoachSim
import app.kassa.money.domain.Money
import app.kassa.money.domain.SimEventKind
import app.kassa.money.domain.SimProblem
import app.kassa.money.domain.SimVerdict
import app.kassa.money.domain.WhatIf
import app.kassa.money.domain.WhatIfText
import app.kassa.money.domain.localDateOfEpochDay
import kotlinx.datetime.TimeZone

/*
 * Coach simulator ("Can I afford it?" / "Simulate my month") for the screens: `LedgerView.coachSim(hidden, today, zone, firstWeekday, whatIfs,
 * cushionCents)` carries what :domain's `CoachSim` worked out in flat Swift-friendly types (Int, Long, String, Boolean, lists of simple rows,
 * enums as their names). The rules are `CoachSim.kt`; nothing is worked out here and no English sentence is built here: a row says what it is
 * (a kind code) and the screen words it from its own strings. Nothing is written: the simulator only reads.
 *
 * What-ifs travel as one text (`WhatIfText`: one per line, `SPEND|cents|day`, `DELAY|key|day`, `EXTRA|cents|day`, `PAYDAY|day`); [CoachSimBridge]
 * builds the lines so Swift never formats them by hand.
 *
 * Hide balances: the verdict is "HIDDEN" (never ABOVE_CUSHION, TIGHT or SHORT: that would give the size away), every amount is `Money.MASK`,
 * there is no chart, no event rows and no lowest day (a day and a dip would give the shape of the balance away). NO_ACCOUNTS and PROBLEM
 * say nothing about a size and stay. The movable items keep their name, kind, key and due day (their amount is the mask).
 */

/** One thing that happens on a day. [kind] is a `SimEventKind` name; [name] is the person's own word for it ("" for none); [inflow] true for money in. */
data class CoachSimEventData(
    val day: Int,
    val kind: String,
    val key: String,
    val name: String,
    val amountText: String,
    val inflow: Boolean,
    val estimated: Boolean,
)

/** A card payment, loan payment or bill a `DELAY` what-if can move; [key] goes into the what-if, [day] is the day it is expected. */
data class CoachSimMovableData(val kind: String, val key: String, val name: String, val day: Int, val amountText: String)

/**
 * The simulation. [verdict] is ABOVE_CUSHION, TIGHT, SHORT, NOT_ENOUGH_DATA, NO_ACCOUNTS, PROBLEM or HIDDEN. [problem] is a `SimProblem` name
 * ("" with none) and [problemIndex] the what-if it is about (-1 for the cushion or the list). The lowest days are epoch days (-1 for none).
 * [shortByText] is only a figure for SHORT (or the mask while hidden). The chart is [chartDays] (epoch days, today first) with the end balance of
 * each day in cents for the scenario ([chartBalances]) and with no what-ifs ([baselineBalances]); all three are empty unless the verdict is
 * ABOVE_CUSHION, TIGHT or SHORT. [events] are every event in day order, then key (a usual day of spending is one row a day, kind USUAL_SPENDING).
 */
data class CoachSimData(
    val verdict: String,
    val problem: String,
    val problemIndex: Int,
    val startDay: Int,
    val startText: String,
    val cushionText: String,
    val lowestDay: Int,
    val lowestText: String,
    val baselineLowestDay: Int,
    val baselineLowestText: String,
    val shortByText: String,
    val chartDays: List<Int>,
    val chartBalances: List<Long>,
    val baselineBalances: List<Long>,
    val events: List<CoachSimEventData>,
    val movable: List<CoachSimMovableData>,
)

/** Builds the what-if text for Swift. */
object CoachSimBridge {
    const val HORIZON_DAYS: Int = CoachSim.HORIZON_DAYS
    const val MAX_WHAT_IFS: Int = CoachSim.MAX_WHAT_IFS

    /** One purchase of [amountCents] on epoch day [day]. */
    fun spendLine(amountCents: Long, day: Int): String = WhatIfText.line(WhatIf.Spend(amountCents, day))

    /** Move the item [itemKey] (a `CoachSimMovableData.key`) to the later epoch day [toDay]. */
    fun delayLine(itemKey: String, toDay: Int): String = WhatIfText.line(WhatIf.DelayDue(itemKey, toDay))

    /** A new monthly cost of [amountCents] from epoch day [fromDay]. */
    fun extraMonthlyLine(amountCents: Long, fromDay: Int): String = WhatIfText.line(WhatIf.ExtraMonthly(amountCents, fromDay))

    /** The next paycheck arrives on epoch day [toDay]. */
    fun paydayLine(toDay: Int): String = WhatIfText.line(WhatIf.MovePayday(toDay))

    /** The lines joined into the one text `coachSim` takes. */
    fun join(lines: List<String>): String = lines.joinToString("\n")

    /** How many what-ifs [text] holds, or -1 when it is not understood. */
    fun countWhatIfs(text: String): Int = WhatIfText.decode(text)?.size ?: -1
}

/** Gathers the simulator of :domain for [LedgerView]. Called only inside `LedgerView.shown`, so `Money` shows this ledger's currency and Hide balances. */
internal class CoachSimViews(private val coach: CoachViews) {

    private val hidden: Boolean get() = Money.isHidden

    private fun text(cents: Long): String = Money.format(cents)

    fun build(today: kotlinx.datetime.LocalDate, zone: TimeZone, firstWeekday: Int, whatIfs: String, cushionCents: Long): CoachSimData {
        val input: CoachInput = coach.inputFor(today, zone, firstWeekday)
        val parsed = WhatIfText.decode(whatIfs)
        val r = CoachSim.run(input, parsed ?: emptyList(), cushionCents)
        val badText = parsed == null
        val verdictName = when {
            badText -> SimVerdict.PROBLEM.name
            hidden && r.verdict != SimVerdict.NO_ACCOUNTS && r.verdict != SimVerdict.PROBLEM -> "HIDDEN"
            else -> r.verdict.name
        }
        val shown = !hidden && !badText
        val low = r.lowest
        val baseLow = r.baselineLowest
        val mask = Money.MASK
        val movable = CoachSim.movable(input).map {
            CoachSimMovableData(it.kind.name, it.key, it.name, it.day, text(it.amountCents))
        }
        return CoachSimData(
            verdict = verdictName,
            problem = if (badText) SimProblem.BAD_ENCODING.name else r.problem?.name ?: "",
            problemIndex = if (badText) -1 else r.problemIndex,
            startDay = r.startDay,
            startText = text(r.startBalanceCents),
            cushionText = text(cushionCents),
            lowestDay = if (shown && low != null) low.day else -1,
            lowestText = if (low != null) text(low.balanceCents) else if (hidden) mask else "",
            baselineLowestDay = if (shown && baseLow != null) baseLow.day else -1,
            baselineLowestText = if (baseLow != null) text(baseLow.balanceCents) else if (hidden) mask else "",
            shortByText = if (r.verdict == SimVerdict.SHORT || hidden) text(r.shortByCents) else "",
            chartDays = if (shown) r.days.map { it.day } else emptyList(),
            chartBalances = if (shown) r.days.map { it.endBalanceCents } else emptyList(),
            baselineBalances = if (shown) r.baselineBalances else emptyList(),
            events = if (shown) {
                r.days.flatMap { d ->
                    d.events.map {
                        CoachSimEventData(d.day, it.kind.name, it.key, it.name, text(kotlin.math.abs(it.amountCents)), it.amountCents > 0L, it.estimated)
                    }
                }
            } else emptyList(),
            movable = movable,
        )
    }
}
