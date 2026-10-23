package dev.krishnagurram.kassa.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Budget lookup: a one-month override wins over the category's default. */
object Budgets {
    fun key(month: YearMonth): Int = month.year * 100 + month.monthValue

    fun amountFor(categoryId: Long, month: YearMonth, budgets: List<Budget>): Long? {
        val k = key(month)
        return budgets.firstOrNull { it.categoryId == categoryId && it.month == k }?.amount
            ?: budgets.firstOrNull { it.categoryId == categoryId && it.month == 0 }?.amount
    }
}

object Spending {
    /** The top-level category an entry rolls up to. */
    fun topOf(categoryId: Long?, byId: Map<Long, Category>): Category? {
        val c = categoryId?.let { byId[it] } ?: return null
        return c.parentId?.let { byId[it] } ?: c
    }

    /** Expense totals per top-level category between two dates, inclusive. Split expenses count your share only. */
    fun byTopCategory(
        transactions: Iterable<Transaction>,
        categories: Map<Long, Category>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            val d = Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            if (d.isBefore(from) || d.isAfter(to)) continue
            val top = topOf(t.categoryId, categories) ?: continue
            out[top.id] = (out[top.id] ?: 0) + t.categoryAmount
        }
        return out
    }
}

/** Equal split. Your share rounds to the cent; any leftover cent goes to the first person so the parts always add up. */
object Split {
    data class Result(val mine: Long, val others: List<Long>)

    fun equal(total: Long, otherPeople: Int): Result {
        require(otherPeople >= 1) { "Split with at least one person" }
        val parts = otherPeople + 1
        val mine = Math.round(total.toDouble() / parts)
        val rest = total - mine
        val each = rest / otherPeople
        val others = List(otherPeople) { i -> if (i == 0) each + (rest - each * otherPeople) else each }
        return Result(mine, others)
    }

    /** What one shared expense means: your share (what counts as spending) and who owes whom. */
    data class Outcome(val myShare: Long, val owed: List<Pair<Long, Long>>)

    /**
     * [shares] is each other person's part of [total]; yours is what's left. When you paid ([paidBy] null), each
     * person owes you their part (positive). When someone else paid, you owe them your part (negative); what the
     * others owe the payer isn't yours to track.
     */
    fun outcome(total: Long, shares: Map<Long, Long>, paidBy: Long?): Outcome {
        val mine = total - shares.values.sum()
        require(mine >= 0) { "Shares add up to more than the total" }
        val owed = if (paidBy == null) shares.filterValues { it != 0L }.map { it.key to it.value }
        else if (mine > 0) listOf(paidBy to -mine) else emptyList()
        return Outcome(mine, owed)
    }

    /** Equal shares for [people], in their order; the odd cent stays with you. */
    fun equalShares(total: Long, people: List<Long>): Map<Long, Long> =
        if (people.isEmpty()) emptyMap() else people.zip(equal(total, people.size).others).toMap()
}

/**
 * Everything the Today screen needs, computed from plain data.
 * "Daily" categories are top-level expense categories that count toward the daily number and have a budget.
 */
object TodayPlan {
    data class Slice(val category: Category, val spent: Long, val budget: Long)

    data class Result(
        val period: Period,
        val plan: DailyPlan.Result,
        val slices: List<Slice>,
    )

    fun compute(
        transactions: List<Transaction>,
        categories: List<Category>,
        budgets: List<Budget>,
        today: LocalDate,
        monthStartDay: Int,
        zone: ZoneId,
    ): Result {
        val period = Period.containing(today, monthStartDay)
        val byId = categories.associateBy { it.id }
        val daily = categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.countsTowardDaily && !it.hidden }
        val spentBefore = if (today.isAfter(period.start)) Spending.byTopCategory(transactions, byId, period.start, today.minusDays(1), zone) else emptyMap()
        val spentToday = Spending.byTopCategory(transactions, byId, today, today, zone)
        val slices = daily.map { c ->
            Slice(c, (spentBefore[c.id] ?: 0) + (spentToday[c.id] ?: 0), Budgets.amountFor(c.id, period.yearMonth, budgets) ?: 0)
        }
        val budget = slices.sumOf { it.budget }
        val plan = DailyPlan.compute(
            budget = budget,
            spentBeforeToday = daily.sumOf { spentBefore[it.id] ?: 0 },
            spentToday = daily.sumOf { spentToday[it.id] ?: 0 },
            period = period,
            today = today,
        )
        return Result(period, plan, slices.filter { it.spent > 0 || it.budget > 0 }.sortedByDescending { it.spent })
    }
}

/**
 * Spending this week so far, next to what a usual week looks like by the same weekday.
 * "Usual" is the average of up to [weeks] previous weeks, counting only weeks that start on or after the first
 * entry, so a new ledger isn't compared against empty weeks. Spending is expenses at your share.
 */
object WeekPace {
    data class Result(
        val start: LocalDate,
        val today: LocalDate,
        val spent: Long,
        /** Average spent from the start of a week through the same weekday. Null with no history. */
        val usualByNow: Long?,
        /** Average spent over a whole week. Null with no history. */
        val usualWeek: Long?,
        val weeksCompared: Int,
    )

    fun startOf(day: LocalDate, firstDay: java.time.DayOfWeek): LocalDate =
        day.minusDays(((day.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

    fun compute(
        transactions: Iterable<Transaction>,
        today: LocalDate,
        firstDay: java.time.DayOfWeek,
        zone: java.time.ZoneId,
        weeks: Int = 8,
    ): Result {
        val byDay = HashMap<LocalDate, Long>()
        var first: LocalDate? = null
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            val d = java.time.Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            byDay[d] = (byDay[d] ?: 0) + t.categoryAmount
            if (first == null || d.isBefore(first)) first = d
        }
        fun sum(from: LocalDate, toInclusive: LocalDate): Long {
            var s = 0L
            var d = from
            while (!d.isAfter(toInclusive)) { s += byDay[d] ?: 0; d = d.plusDays(1) }
            return s
        }
        val start = startOf(today, firstDay)
        val offset = java.time.temporal.ChronoUnit.DAYS.between(start, today)
        val spent = sum(start, today)
        var n = 0
        var byNow = 0L
        var whole = 0L
        for (i in 1..weeks) {
            val ws = start.minusWeeks(i.toLong())
            val f = first ?: break
            if (ws.isBefore(startOf(f, firstDay))) break
            byNow += sum(ws, ws.plusDays(offset))
            whole += sum(ws, ws.plusDays(6))
            n++
        }
        return Result(
            start = start, today = today, spent = spent,
            usualByNow = if (n > 0) byNow / n else null,
            usualWeek = if (n > 0) whole / n else null,
            weeksCompared = n,
        )
    }
}
