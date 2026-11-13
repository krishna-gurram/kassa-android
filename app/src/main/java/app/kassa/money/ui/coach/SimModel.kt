package app.kassa.money.ui.coach

import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.CoachSim
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.SimDay
import app.kassa.money.domain.SimEvent
import app.kassa.money.domain.SimEventKind
import app.kassa.money.domain.SimMovable
import app.kassa.money.domain.SimProblem
import app.kassa.money.domain.SimVerdict
import app.kassa.money.domain.WhatIf
import app.kassa.money.domain.WhatIfText

/*
 * What the "Can I afford it?" and "Simulate my month" screens show, decided in plain Kotlin (no Android, no strings) so the choices have JVM
 * tests: how the form's chips become the what-if text, what is a valid amount or day, which verdict gets which tint, what Hide balances takes
 * away, how the days group into the list. The rules themselves are :domain's `CoachSim`; nothing here works out a balance and nothing here
 * writes anything. The screens (SimScreens.kt) word what this says from string resources.
 */

/** What the answer says. HIDDEN is Hide balances: it never says ABOVE_CUSHION, TIGHT or SHORT, which would give the size away. */
enum class SimKind { ABOVE_CUSHION, TIGHT, SHORT, NOT_ENOUGH_DATA, NO_ACCOUNTS, PROBLEM, HIDDEN }

/** The tint of the answer's hero: green above the cushion, orange for tight or short, grey for everything else. */
enum class SimTone { GREEN, ORANGE, GREY }

/** The "Or try" chips of the form. */
enum class SimTry { SKIP_BILL, DELAY_PAYMENT, MOVE_PAYDAY, EXTRA_MONTHLY }

/** Why the form (or a half-made what-if) cannot be used yet. The screen words each one. */
enum class SimFormProblem { AMOUNT_NEEDED, AMOUNT_BAD, AMOUNT_TOO_BIG, DAY_OUTSIDE, PICK_PAYMENT, NO_LATER_DAY, TOO_MANY }

/** One row of the day by day list. [days] is how many days a USUAL_SPENDING row stands for (1 for any other row); [amount] is signed. */
data class SimRow(val kind: SimEventKind, val key: String, val name: String, val amount: Long, val estimated: Boolean, val days: Int = 1)

/** One day of the list with the money at its end. [lowest] marks the lowest day of the month. */
data class SimGroup(val day: Int, val balance: Long, val lowest: Boolean, val rows: List<SimRow>)

/**
 * The simulation as the screens read it. [lowestDay] is -1 and [days] empty while hidden (a dip and its day would give the shape of the balance
 * away); [movable] keeps the payments' names and days (their amounts are masked by `Money.format`).
 */
data class SimView(
    val kind: SimKind,
    val problem: SimProblem?,
    val problemIndex: Int,
    val startDay: Int,
    val cushion: Long,
    val lowestDay: Int,
    val lowest: Long,
    val baselineLowest: Long,
    val shortBy: Long,
    val days: List<SimDay>,
    val baseline: List<Long>,
    val movable: List<SimMovable>,
) {
    val tone: SimTone get() = SimModel.tone(kind)

    /** True when there is a month to draw. */
    val hasChart: Boolean get() = days.isNotEmpty() && baseline.size == days.size
}

/**
 * The form's state: what the person typed and picked. Everything is a String or an Int so it survives the screen being recreated
 * (`SimFormSaver`). [extras] are the what-ifs made with the "Or try" chips (the purchase itself is [amount] and [day]); [trying] is the chip
 * whose little panel is open, with what has been picked in it so far.
 */
data class SimForm(
    val amount: String = "",
    val day: Int,
    val extras: List<WhatIf> = emptyList(),
    val trying: SimTry? = null,
    val tryItem: String = "",
    val tryDay: Int = 0,
    val tryAmount: String = "",
) {
    /** The purchase, when an amount is typed and fine. */
    fun purchase(): WhatIf.Spend? = SimModel.amountCents(amount)?.takeIf { SimModel.amountOk(it) }?.let { WhatIf.Spend(it, day) }

    /** The what-ifs the simulation runs: the purchase first, then the extras in the order they were made. */
    fun whatIfs(): List<WhatIf> = listOfNotNull(purchase()) + extras

    /** [SimModel.whatIfText] of [whatIfs]. */
    fun text(): String = SimModel.whatIfText(whatIfs())
}

object SimModel {
    const val HORIZON_DAYS: Int = CoachSim.HORIZON_DAYS
    const val MAX_WHAT_IFS: Int = CoachSim.MAX_WHAT_IFS

    // ---------- the simulation ----------

    /**
     * Runs the rules on [input] with [whatIfs] and the person's [cushion] and gives the screens what they read. While [hidden] the verdict is
     * HIDDEN (unless there is nothing to read, NO_ACCOUNTS, or the what-ifs were refused, PROBLEM: neither says anything about a size) and
     * there is no chart, no day, no event and no lowest point, as the rules of the iOS bridge do.
     */
    fun run(input: CoachInput, whatIfs: List<WhatIf>, cushion: Long, hidden: Boolean): SimView {
        val r = CoachSim.run(input, whatIfs, cushion)
        val kind = kindOf(r.verdict, hidden)
        val show = !hidden && r.days.isNotEmpty()
        return SimView(
            kind = kind,
            problem = r.problem,
            problemIndex = r.problemIndex,
            startDay = r.startDay,
            cushion = cushion,
            lowestDay = if (show) r.lowest?.day ?: -1 else -1,
            lowest = if (hidden) 0L else r.lowest?.balanceCents ?: 0L,
            baselineLowest = if (hidden) 0L else r.baselineLowest?.balanceCents ?: 0L,
            shortBy = if (hidden) 0L else r.shortByCents,
            days = if (show) r.days else emptyList(),
            baseline = if (show) r.baselineBalances else emptyList(),
            movable = CoachSim.movable(input),
        )
    }

    fun kindOf(verdict: SimVerdict, hidden: Boolean): SimKind = when {
        verdict == SimVerdict.NO_ACCOUNTS -> SimKind.NO_ACCOUNTS
        verdict == SimVerdict.PROBLEM -> SimKind.PROBLEM
        hidden -> SimKind.HIDDEN
        else -> when (verdict) {
            SimVerdict.ABOVE_CUSHION -> SimKind.ABOVE_CUSHION
            SimVerdict.TIGHT -> SimKind.TIGHT
            SimVerdict.SHORT -> SimKind.SHORT
            SimVerdict.NOT_ENOUGH_DATA -> SimKind.NOT_ENOUGH_DATA
            SimVerdict.NO_ACCOUNTS -> SimKind.NO_ACCOUNTS
            SimVerdict.PROBLEM -> SimKind.PROBLEM
        }
    }

    /** Green when the lowest point stays above the cushion, orange when it dips below it or below zero, grey for everything else (and while hidden). */
    fun tone(kind: SimKind): SimTone = when (kind) {
        SimKind.ABOVE_CUSHION -> SimTone.GREEN
        SimKind.TIGHT, SimKind.SHORT -> SimTone.ORANGE
        SimKind.NOT_ENOUGH_DATA, SimKind.NO_ACCOUNTS, SimKind.PROBLEM, SimKind.HIDDEN -> SimTone.GREY
    }

    // ---------- the what-if text ----------

    fun whatIfText(list: List<WhatIf>): String = WhatIfText.encode(list)

    /** The what-ifs of [text]; empty for no text or for text that is not understood. */
    fun whatIfs(text: String): List<WhatIf> = WhatIfText.decode(text) ?: emptyList()

    /** [list] without the what-if at [index]; the same list for an index that is not there. */
    fun remove(list: List<WhatIf>, index: Int): List<WhatIf> = if (index in list.indices) list.filterIndexed { i, _ -> i != index } else list

    /** The text with the what-if at [index] removed (a chip tapped on the Simulate page). */
    fun removeFromText(text: String, index: Int): String = whatIfText(remove(whatIfs(text), index))

    // ---------- the form ----------

    /** A form for today: no amount, the purchase on today, nothing tried. */
    fun newForm(today: Int): SimForm = SimForm(day = today)

    /** The last day the simulation looks at, [HORIZON_DAYS] after [today]. */
    fun lastDay(today: Int): Int = today + HORIZON_DAYS

    /** A day the form may use: today to the last day of the month it replays. */
    fun dayOk(day: Int, today: Int): Boolean = day in today..lastDay(today)

    fun amountCents(text: String): Long? = if (text.isBlank()) null else Money.parseCents(text)

    fun amountOk(cents: Long): Boolean = cents in 1L..InputRules.AMOUNT_MAX

    /** What is wrong with the amount typed, or null when it is fine. A blank amount is only a problem when nothing else is being tried. */
    fun amountProblem(form: SimForm): SimFormProblem? {
        if (form.amount.isBlank()) return if (form.extras.isEmpty()) SimFormProblem.AMOUNT_NEEDED else null
        val cents = amountCents(form.amount) ?: return SimFormProblem.AMOUNT_BAD
        return when {
            cents <= 0L -> SimFormProblem.AMOUNT_BAD
            cents > InputRules.AMOUNT_MAX -> SimFormProblem.AMOUNT_TOO_BIG
            else -> null
        }
    }

    /** The first thing that stops "Check my month", or null when it can run. */
    fun problem(form: SimForm, today: Int): SimFormProblem? {
        amountProblem(form)?.let { return it }
        if (form.amount.isNotBlank() && !dayOk(form.day, today)) return SimFormProblem.DAY_OUTSIDE
        if (form.whatIfs().size > MAX_WHAT_IFS) return SimFormProblem.TOO_MANY
        return null
    }

    // ---------- the chips ----------

    /** The payments a chip may move: bills for [SimTry.SKIP_BILL], card and loan payments for [SimTry.DELAY_PAYMENT]; only ones with a later day left in the month. */
    fun movableFor(trying: SimTry, movable: List<SimMovable>, today: Int): List<SimMovable> {
        val kinds = when (trying) {
            SimTry.SKIP_BILL -> setOf(SimEventKind.BILL)
            SimTry.DELAY_PAYMENT -> setOf(SimEventKind.CARD_PAYMENT, SimEventKind.LOAN_PAYMENT)
            else -> return emptyList()
        }
        return movable.filter { it.kind in kinds && it.day < lastDay(today) }
    }

    /** Opens the little panel of [trying] with its starting picks: the first payment it offers moved to the last day of the month (the closest to skipping it). */
    fun startTry(form: SimForm, trying: SimTry, movable: List<SimMovable>, today: Int): SimForm {
        if (form.trying == trying) return form.copy(trying = null)
        val first = movableFor(trying, movable, today).firstOrNull()
        return form.copy(
            trying = trying,
            tryItem = first?.key.orEmpty(),
            tryDay = when (trying) {
                SimTry.SKIP_BILL, SimTry.DELAY_PAYMENT -> lastDay(today)
                SimTry.MOVE_PAYDAY, SimTry.EXTRA_MONTHLY -> today
            },
            tryAmount = "",
        )
    }

    /** What making the open chip's what-if gives: the form with it added and the panel closed, or the reason it cannot be made yet. */
    sealed interface Made {
        data class Ok(val form: SimForm) : Made
        data class No(val problem: SimFormProblem) : Made
    }

    fun makeTry(form: SimForm, movable: List<SimMovable>, today: Int): Made {
        val trying = form.trying ?: return Made.Ok(form)
        if (form.whatIfs().size >= MAX_WHAT_IFS) return Made.No(SimFormProblem.TOO_MANY)
        if (!dayOk(form.tryDay, today)) return Made.No(SimFormProblem.DAY_OUTSIDE)
        val made: WhatIf = when (trying) {
            SimTry.SKIP_BILL, SimTry.DELAY_PAYMENT -> {
                val item = movableFor(trying, movable, today).firstOrNull { it.key == form.tryItem } ?: return Made.No(SimFormProblem.PICK_PAYMENT)
                if (form.tryDay <= item.day) return Made.No(SimFormProblem.NO_LATER_DAY)
                WhatIf.DelayDue(item.key, form.tryDay)
            }
            SimTry.MOVE_PAYDAY -> WhatIf.MovePayday(form.tryDay)
            SimTry.EXTRA_MONTHLY -> {
                val cents = amountCents(form.tryAmount) ?: return Made.No(if (form.tryAmount.isBlank()) SimFormProblem.AMOUNT_NEEDED else SimFormProblem.AMOUNT_BAD)
                if (cents <= 0L) return Made.No(SimFormProblem.AMOUNT_BAD)
                if (cents > InputRules.AMOUNT_MAX) return Made.No(SimFormProblem.AMOUNT_TOO_BIG)
                WhatIf.ExtraMonthly(cents, form.tryDay)
            }
        }
        return Made.Ok(form.copy(extras = form.extras + made, trying = null, tryItem = "", tryAmount = ""))
    }

    // ---------- the answer ----------

    /** The events the answer's "What moves" lists: everything but a day of usual spending, in day order, at most [limit]. */
    fun whatMoves(days: List<SimDay>, limit: Int = 8): List<Pair<Int, SimEvent>> =
        days.flatMap { d -> d.events.filter { it.kind != SimEventKind.USUAL_SPENDING }.map { d.day to it } }.take(limit)

    /** How many of [whatMoves] there are beyond [limit]. */
    fun moreMoves(days: List<SimDay>, limit: Int = 8): Int =
        (days.sumOf { d -> d.events.count { it.kind != SimEventKind.USUAL_SPENDING } } - limit).coerceAtLeast(0)

    /** Whether an event's figure is orange: only a card payment, which is owed (the Style rule: against you beyond ordinary spending). Money coming in is green. */
    fun figureTone(kind: SimEventKind, inflow: Boolean): SimTone = when {
        inflow -> SimTone.GREEN
        kind == SimEventKind.CARD_PAYMENT -> SimTone.ORANGE
        else -> SimTone.GREY
    }

    // ---------- the day by day list ----------

    /**
     * The days as the list shows them: a group for each day where something other than usual spending happens, and for the lowest day and the last
     * day. The usual spending of the days in between is gathered into one row ("Usual spending (6 days)") at the top of the next group, so a month
     * is a handful of groups, not thirty. A group's balance is the money at the end of its day.
     */
    fun groups(days: List<SimDay>, lowestDay: Int): List<SimGroup> {
        val out = ArrayList<SimGroup>()
        var usual = 0L
        var usualDays = 0
        var usualEstimated = false
        for ((i, d) in days.withIndex()) {
            val special = d.events.filter { it.kind != SimEventKind.USUAL_SPENDING }
            for (u in d.events.filter { it.kind == SimEventKind.USUAL_SPENDING }) {
                usual += u.amountCents
                usualDays++
                usualEstimated = usualEstimated || u.estimated
            }
            val keep = special.isNotEmpty() || d.day == lowestDay || i == days.lastIndex
            if (!keep) continue
            val rows = ArrayList<SimRow>()
            if (usualDays > 0) rows.add(SimRow(SimEventKind.USUAL_SPENDING, "usual", "", usual, usualEstimated, usualDays))
            special.mapTo(rows) { SimRow(it.kind, it.key, it.name, it.amountCents, it.estimated) }
            out.add(SimGroup(d.day, d.endBalanceCents, d.day == lowestDay, rows))
            usual = 0L
            usualDays = 0
            usualEstimated = false
        }
        return out
    }

    // ---------- the cushion setting ----------

    /** The cushion when nothing was set: zero, so the answer is "above zero". */
    const val CUSHION_DEFAULT: Long = 0L

    /** The cushion typed in Settings as cents: blank is zero; null for something that is not an amount or is out of bounds. */
    fun cushionFromText(text: String): Long? {
        if (text.isBlank()) return CUSHION_DEFAULT
        val cents = Money.parseCents(text) ?: return null
        return cents.takeIf { it in 0L..InputRules.AMOUNT_MAX }
    }
}
