package app.kassa.money.domain

/*
 * Coach simulator ("Can I afford it?" / "Simulate my month"), rules only. It replays the person's liquid money (open checking, savings,
 * cash and prepaid, the same money as [SafeToSpend]) day by day from today through today + [CoachSim.HORIZON_DAYS], from the person's own
 * data only, then applies a few what-ifs and says whether the lowest point stays above a cushion the caller passes in.
 *
 * Plain Kotlin, no Android, deterministic and bounded (at most [CoachSim.MAX_HORIZON_DAYS] + 1 days, [CoachSim.MAX_WHAT_IFS] what-ifs).
 * Money is Long cents. Nothing here writes anything and no English sentence is built here: every row is a kind code and a key and the
 * screens word it. A what-if that cannot be applied is a [SimProblem] code in the result; nothing throws to the UI.
 *
 * Day 0 is today and the money at the start is the balance now. Today counts as a whole day of usual spending, as in [SafeToSpend]
 * (a little conservative).
 */

/** What an event of the replay is. */
enum class SimEventKind { PAYCHECK, BILL, CARD_PAYMENT, LOAN_PAYMENT, RECURRING, USUAL_SPENDING, SPEND, EXTRA_MONTHLY }

/** Why a what-if or a setting could not be used. [SimResult.problemIndex] says which what-if (-1 for the cushion or the list as a whole). */
enum class SimProblem { TOO_MANY_WHAT_IFS, BAD_AMOUNT, DAY_OUT_OF_RANGE, UNKNOWN_ITEM, NOT_LATER, NO_PAYDAY, BAD_CUSHION, BAD_ENCODING }

enum class SimVerdict {
    /** The lowest end-of-day balance is at or above the cushion. */
    ABOVE_CUSHION,

    /** The lowest balance is not below zero but is below the cushion. */
    TIGHT,

    /** The lowest balance is below zero; [SimResult.shortByCents] says by how much. */
    SHORT,

    /** Fewer than [CoachSim.MIN_HISTORY_DAYS] days of entries (the rule of [WeeklyCheckIn]). */
    NOT_ENOUGH_DATA,

    /** No open checking, savings, cash or prepaid account. */
    NO_ACCOUNTS,

    /** A what-if or the cushion could not be used; see [SimResult.problem]. */
    PROBLEM,
}

/** Something to try. Plain data: kind codes and numbers, days are epoch days. Amounts are positive cents. */
sealed class WhatIf {
    /** One purchase of [amountCents] on [day]. */
    data class Spend(val amountCents: Long, val day: Int) : WhatIf()

    /** Move one card payment, loan payment or bill (its [itemKey], from [CoachSim.movable]) to the later day [toDay]. */
    data class DelayDue(val itemKey: String, val toDay: Int) : WhatIf()

    /** A new monthly cost of [amountCents] that starts on [fromDay] and comes back on the same day of each month inside the horizon. */
    data class ExtraMonthly(val amountCents: Long, val fromDay: Int) : WhatIf()

    /** The next paycheck arrives on [toDay] instead (the later ones keep their days). */
    data class MovePayday(val toDay: Int) : WhatIf()
}

/** One thing that happened on a day: [amountCents] is signed (negative leaves the money). [name] is the person's own word for it ("" for none). */
data class SimEvent(val kind: SimEventKind, val key: String, val amountCents: Long, val estimated: Boolean, val name: String = "")

/** The money at the end of [day] and what moved it, ordered by key. */
data class SimDay(val day: Int, val endBalanceCents: Long, val events: List<SimEvent>)

data class SimPoint(val day: Int, val balanceCents: Long)

/** A payment that a [WhatIf.DelayDue] can move. [day] is the day it is expected (today for one that is overdue), [amountCents] positive. */
data class SimMovable(val kind: SimEventKind, val key: String, val name: String, val day: Int, val amountCents: Long)

/**
 * The replay. [days] and [baselineBalances] have one entry per day from today (empty unless the verdict is ABOVE_CUSHION, TIGHT or SHORT).
 * [baselineBalances] is the end balance per day with no what-ifs. [lowest] and [baselineLowest] are the lowest end-of-day balance (earliest day
 * on a tie). [shortByCents] is only above zero for SHORT. [startBalanceCents] is the money now: the sum of all events equals the last end
 * balance minus it.
 */
data class SimResult(
    val verdict: SimVerdict,
    val problem: SimProblem?,
    val problemIndex: Int,
    val startDay: Int,
    val startBalanceCents: Long,
    val cushionCents: Long,
    val days: List<SimDay>,
    val baselineBalances: List<Long>,
    val lowest: SimPoint?,
    val baselineLowest: SimPoint?,
    val shortByCents: Long,
)

object CoachSim {
    const val HORIZON_DAYS = 30

    /** The longest horizon the rules would accept. */
    const val MAX_HORIZON_DAYS = 62

    const val MAX_WHAT_IFS = 8

    /** Fewest days of entries (first entry to today) that make a simulation, the same rule as [WeeklyCheckIn.MIN_HISTORY_DAYS]. */
    const val MIN_HISTORY_DAYS = 14

    private val MOVABLE = setOf(SimEventKind.CARD_PAYMENT, SimEventKind.LOAN_PAYMENT, SimEventKind.BILL)

    /** One event on a day while working; [day] is where it lands now. */
    private data class Ev(val day: Int, val event: SimEvent)

    fun run(input: CoachInput, whatIfs: List<WhatIf>, cushionCents: Long): SimResult = run(CoachLedger(input), whatIfs, cushionCents)

    /** The payments a [WhatIf.DelayDue] can move, soonest first (ties by key); empty without a liquid account. */
    fun movable(input: CoachInput): List<SimMovable> {
        val l = CoachLedger(input)
        if (!l.hasLiquidAccount) return emptyList()
        return baseline(l).filter { it.event.kind in MOVABLE }
            .sortedWith(compareBy({ it.day }, { it.event.key }))
            .map { SimMovable(it.event.kind, it.event.key, it.event.name, it.day, -it.event.amountCents) }
    }

    private fun result(
        l: CoachLedger, verdict: SimVerdict, problem: SimProblem? = null, index: Int = -1, cushion: Long, start: Long = 0L,
        days: List<SimDay> = emptyList(), baseline: List<Long> = emptyList(), lowest: SimPoint? = null, baseLow: SimPoint? = null, shortBy: Long = 0L,
    ) = SimResult(verdict, problem, index, l.todayDay, start, cushion, days, baseline, lowest, baseLow, shortBy)

    internal fun run(l: CoachLedger, whatIfs: List<WhatIf>, cushionCents: Long): SimResult {
        if (!l.hasLiquidAccount) return result(l, SimVerdict.NO_ACCOUNTS, cushion = cushionCents)
        val first = l.firstEntryDay
        if (first == null || l.todayDay - first + 1 < MIN_HISTORY_DAYS) return result(l, SimVerdict.NOT_ENOUGH_DATA, cushion = cushionCents)
        if (cushionCents < 0L || cushionCents > InputRules.AMOUNT_MAX) {
            return result(l, SimVerdict.PROBLEM, SimProblem.BAD_CUSHION, cushion = cushionCents)
        }
        if (whatIfs.size > MAX_WHAT_IFS) return result(l, SimVerdict.PROBLEM, SimProblem.TOO_MANY_WHAT_IFS, cushion = cushionCents)

        val start = l.todayDay
        val end = start + HORIZON_DAYS
        val base = baseline(l)
        var events = base
        for ((i, w) in whatIfs.withIndex()) {
            val applied = apply(events, w, i, start, end)
            if (applied.problem != null) return result(l, SimVerdict.PROBLEM, applied.problem, i, cushionCents)
            events = applied.events
        }

        val liquid = l.liquidBalance()
        val days = replay(liquid, events, start, end)
        val baseDays = replay(liquid, base, start, end)
        val lowest = lowestOf(days)
        val low = lowest.balanceCents
        val verdict = when {
            low < 0L -> SimVerdict.SHORT
            low >= cushionCents -> SimVerdict.ABOVE_CUSHION
            else -> SimVerdict.TIGHT
        }
        return result(
            l, verdict, cushion = cushionCents, start = liquid, days = days, baseline = baseDays.map { it.endBalanceCents },
            lowest = lowest, baseLow = lowestOf(baseDays), shortBy = if (low < 0L) -low else 0L,
        )
    }

    private fun lowestOf(days: List<SimDay>): SimPoint {
        var best = days.first()
        for (d in days) if (d.endBalanceCents < best.endBalanceCents) best = d
        return SimPoint(best.day, best.endBalanceCents)
    }

    private fun replay(liquid: Long, events: List<Ev>, start: Int, end: Int): List<SimDay> {
        val byDay = events.groupBy { it.day }
        var balance = liquid
        val out = ArrayList<SimDay>()
        for (day in start..end) {
            val today = (byDay[day] ?: emptyList()).map { it.event }.sortedWith(compareBy({ it.key }, { it.amountCents }))
            balance += today.sumOf { it.amountCents }
            out.add(SimDay(day, balance, today))
        }
        return out
    }

    // ---------- what-ifs ----------

    private class Applied(val events: List<Ev>, val problem: SimProblem? = null)

    private fun amountOk(cents: Long): Boolean = cents in 1L..InputRules.AMOUNT_MAX

    private fun apply(events: List<Ev>, w: WhatIf, index: Int, start: Int, end: Int): Applied {
        when (w) {
            is WhatIf.Spend -> {
                if (!amountOk(w.amountCents)) return Applied(events, SimProblem.BAD_AMOUNT)
                if (w.day !in start..end) return Applied(events, SimProblem.DAY_OUT_OF_RANGE)
                return Applied(events + Ev(w.day, SimEvent(SimEventKind.SPEND, "spend:$index", -w.amountCents, false)))
            }
            is WhatIf.ExtraMonthly -> {
                if (!amountOk(w.amountCents)) return Applied(events, SimProblem.BAD_AMOUNT)
                if (w.fromDay !in start..end) return Applied(events, SimProblem.DAY_OUT_OF_RANGE)
                val added = ArrayList<Ev>()
                val anchor = LoanDates.dayOfMonth(w.fromDay)
                var k = 0
                while (k < 3) {
                    val day = LoanDates.addMonths(w.fromDay, k, anchor)
                    if (day > end) break
                    added.add(Ev(day, SimEvent(SimEventKind.EXTRA_MONTHLY, "extra:$index:$day", -w.amountCents, false)))
                    k++
                }
                return Applied(events + added)
            }
            is WhatIf.DelayDue -> {
                if (w.toDay !in start..end) return Applied(events, SimProblem.DAY_OUT_OF_RANGE)
                val target = events.filter { it.event.key == w.itemKey && it.event.kind in MOVABLE }.minByOrNull { it.day }
                    ?: return Applied(events, SimProblem.UNKNOWN_ITEM)
                if (w.toDay <= target.day) return Applied(events, SimProblem.NOT_LATER)
                return Applied(events.map { if (it === target) it.copy(day = w.toDay) else it })
            }
            is WhatIf.MovePayday -> {
                if (w.toDay !in start..end) return Applied(events, SimProblem.DAY_OUT_OF_RANGE)
                val target = events.filter { it.event.kind == SimEventKind.PAYCHECK }.minWithOrNull(compareBy({ it.day }, { it.event.key }))
                    ?: return Applied(events, SimProblem.NO_PAYDAY)
                return Applied(events.map { if (it === target) it.copy(day = w.toDay) else it })
            }
        }
    }

    // ---------- the baseline ----------

    /** Every event of the next [HORIZON_DAYS] days from the person's own data, with no what-ifs. */
    private fun baseline(l: CoachLedger): List<Ev> {
        val input = l.input
        val start = l.todayDay
        val end = start + HORIZON_DAYS
        val out = ArrayList<Ev>()
        val seen = HashSet<String>()

        // Payments that are not worked out on a day come in at today.
        fun add(day: Int, kind: SimEventKind, key: String, amount: Long, estimated: Boolean, name: String) {
            val d = maxOf(day, start)
            if (d > end || amount == 0L || !seen.add(key)) return
            out.add(Ev(d, SimEvent(kind, key, amount, estimated, name)))
        }

        val due = DueItems.build(
            l.activeAccounts, input.balances, input.transactions, input.bills, input.loanTerms, input.loanChanges, input.loanPayments,
            input.today, input.zone,
        )
        val activeBills = input.bills.filter { it.active }.associateBy { it.id }

        // Cards: the balance payable on its due day (DueItems), and the next statement's charges so far on its own due day.
        for (item in due.filter { it.kind == DueKind.CARD && (it.amount ?: 0L) > 0L }) {
            add(item.dueDay, SimEventKind.CARD_PAYMENT, item.key, -(item.amount ?: 0L), false, item.name)
        }
        for (a in l.activeAccounts) {
            val statementDay = a.statementDay ?: continue
            if (!a.type.hasStatement) continue
            val s = CardCycle.snapshot(a, input.transactions, input.today, input.zone, balance = input.balances[a.id] ?: 0L)
            // A balance payable still unpaid after the next close would be on that statement too; only a payable that is settled first is left out.
            if (s.payable > 0L && !s.due.isBefore(s.nextClose)) continue
            val carried = if (s.payable > 0L) 0L else s.balancePayable
            val amount = (s.outstanding + carried).coerceAtLeast(0L)
            if (amount <= 0L) continue
            val dueDay = CardCycle.dueFor(statementDay, a.dueDay ?: statementDay, s.nextClose).toEpochDay().toInt()
            add(dueDay, SimEventKind.CARD_PAYMENT, "card:${a.id}:$dueDay", -amount, true, a.name)
        }

        // Loans: the scheduled payment on nextPaymentDay (unless one on or after it is recorded) and each month after it.
        for (a in l.activeAccounts) {
            if (a.type != AccountType.LOAN) continue
            val terms = input.loanTerms[a.id] ?: continue
            var owed = -(input.balances[a.id] ?: 0L)
            if (owed <= 0L) continue
            var day = terms.nextPaymentDay
            var lastDay = LoanEngine.lastDay(terms, input.loanPayments)
            if (input.loanPayments.any { it.accountId == a.id && it.day >= day }) day = LoanDates.addMonths(day, 1, terms.dueDay)
            var firstOne = true
            var guard = 0
            while (day <= end && guard++ < 64) {
                if (day >= start || firstOne) {
                    val amount = LoanEngine.amountDue(terms, input.loanChanges, owed, lastDay, day)
                    if (amount <= 0L) break
                    add(day, SimEventKind.LOAN_PAYMENT, "loan:${a.id}:$day", -amount, !firstOne, a.name)
                    owed -= LoanEngine.suggest(amount, day, owed, lastDay, terms, input.loanChanges).principal
                    lastDay = day
                    firstOne = false
                }
                day = LoanDates.addMonths(day, 1, terms.dueDay)
            }
        }

        // Bills: the next due date waiting on an answer and each month after it. A bill with no amount known is left out, never guessed.
        for (item in due.filter { it.kind == DueKind.BILL }) {
            val r = activeBills[item.id] ?: continue
            val amount = item.amount ?: continue
            val estimated = r.fixedAmount == null
            var month = YearMonth.from(localDateOfEpochDay(item.dueDay))
            var day = item.dueDay
            var guard = 0
            while (day <= end && guard++ < 4) {
                add(day, SimEventKind.BILL, "bill:${r.id}:$day", -amount, estimated, r.name)
                month = month.plusMonths(1)
                day = RecurringRules.dueDate(r, month).toEpochDay().toInt()
            }
        }

        // Fixed charges that recur and are not a bill yet (the same ones SafeToSpend subtracts), on the days they are expected.
        val billKeys = input.bills.map { Repeats.key(it.name) }.toSet()
        for (r in l.recurringRepeats.filter { it.key !in billKeys }.sortedBy { it.key }) {
            var d = r.next(input.today)
            var guard = 0
            while (d.toEpochDay().toInt() <= end && guard++ < 64) {
                val day = d.toEpochDay().toInt()
                add(day, SimEventKind.RECURRING, "recurring:${r.key}:$day", -r.typical, !r.fixed, r.name)
                d = r.cadence.after(d)
            }
        }

        // Paychecks: the repeat SafeToSpend takes as payday, at its own cadence.
        val payday = SafeToSpend.paydayRepeat(l)
        if (payday != null) {
            var d = payday.next(input.today)
            var guard = 0
            while (d.toEpochDay().toInt() <= end && guard++ < 64) {
                val day = d.toEpochDay().toInt()
                add(day, SimEventKind.PAYCHECK, "paycheck:$day", payday.typical, !payday.fixed, payday.name)
                d = payday.cadence.after(d)
            }
        }

        // Usual day to day spending, without the known payments above (SafeToSpend's rule), the same each day.
        val usual = SafeToSpend.usualOf(l)
        if (usual.daily > 0L) {
            for (day in start..end) add(day, SimEventKind.USUAL_SPENDING, "usual:$day", -usual.daily, true, "")
        }
        return out
    }
}

/**
 * What-ifs as text, so Swift and Kotlin pass them as one string: one what-if per line (`\n`), fields split by `|`:
 * `SPEND|<cents>|<epochDay>`, `DELAY|<itemKey>|<epochDay>`, `EXTRA|<cents>|<epochDay>`, `PAYDAY|<epochDay>`. Blank lines are skipped. An item
 * key never holds `|` or a line break (they are made from ids and days). [decode] gives null for anything it does not understand.
 */
object WhatIfText {
    fun encode(list: List<WhatIf>): String = list.joinToString("\n") { line(it) }

    fun line(w: WhatIf): String = when (w) {
        is WhatIf.Spend -> "SPEND|${w.amountCents}|${w.day}"
        is WhatIf.DelayDue -> "DELAY|${w.itemKey}|${w.toDay}"
        is WhatIf.ExtraMonthly -> "EXTRA|${w.amountCents}|${w.fromDay}"
        is WhatIf.MovePayday -> "PAYDAY|${w.toDay}"
    }

    fun decode(text: String): List<WhatIf>? {
        val out = ArrayList<WhatIf>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val p = line.split('|')
            val w: WhatIf = when {
                p[0] == "SPEND" && p.size == 3 -> WhatIf.Spend(p[1].toLongOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
                p[0] == "DELAY" && p.size == 3 && p[1].isNotEmpty() -> WhatIf.DelayDue(p[1], p[2].toIntOrNull() ?: return null)
                p[0] == "EXTRA" && p.size == 3 -> WhatIf.ExtraMonthly(p[1].toLongOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
                p[0] == "PAYDAY" && p.size == 2 -> WhatIf.MovePayday(p[1].toIntOrNull() ?: return null)
                else -> return null
            }
            out.add(w)
            if (out.size > 1000) return null
        }
        return out
    }
}
