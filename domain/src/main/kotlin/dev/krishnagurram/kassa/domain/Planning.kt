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
