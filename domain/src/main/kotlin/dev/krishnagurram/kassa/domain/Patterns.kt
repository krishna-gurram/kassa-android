package dev.krishnagurram.kassa.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What already happened, counted from your entries: no forecasts and no averages that a payday spree would skew.
 * Comparisons are always against the same point in the period before (Sep 1 to 22 against Oct 1 to 22).
 * Split expenses count your share only, like everywhere else.
 */
object Patterns {

    data class SavedMonth(val month: YearMonth, val income: Long, val spent: Long) {
        val saved: Long get() = income - spent
        /** Whole percent of income kept, or null with no income. */
        val rate: Int? get() = if (income > 0) Math.round(saved * 100.0 / income).toInt() else null
    }

    data class Store(val name: String, val category: Category?, val total: Long, val visits: Int)

    data class SmallBuys(val total: Long, val count: Int, val byCategory: List<Triple<Category, Long, Int>>)

    data class Creep(val category: Category, val months: List<Pair<YearMonth, Long>>) {
        val rise: Long get() = months.last().second - months.first().second
    }

    data class CategoryChange(val category: Category, val now: Long, val before: Long) {
        val change: Long get() = now - before
    }

    const val SMALL_LIMIT = 1_500L

    private fun dateOf(t: Transaction, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()

    /** Every expense between two dates, inclusive. */
    fun spent(transactions: Iterable<Transaction>, from: LocalDate, to: LocalDate, zone: ZoneId): Long =
        transactions.filter { it.kind == TxKind.EXPENSE }.filter { dateOf(it, zone).let { d -> !d.isBefore(from) && !d.isAfter(to) } }.sumOf { it.categoryAmount }

    /** The same stretch of the period before: Oct 1 to 22 becomes Sep 1 to 22, Monday to Thursday becomes last Monday to Thursday. */
    fun samePointBefore(start: LocalDate, today: LocalDate, previousStart: LocalDate): Pair<LocalDate, LocalDate> {
        val span = ChronoUnit.DAYS.between(start, today)
        val end = previousStart.plusDays(span).let { if (!it.isBefore(start)) start.minusDays(1) else it }
        return previousStart to end
    }

    /** Monday of the week [date] falls in. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** Expenses per day this week so far, Monday first. Days after today are null. */
    fun daysThisWeek(transactions: Iterable<Transaction>, today: LocalDate, zone: ZoneId): List<Pair<DayOfWeek, Long?>> {
        val monday = weekStart(today)
        val sums = LongArray(7)
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            val d = dateOf(t, zone)
            if (d.isBefore(monday) || d.isAfter(today)) continue
            sums[d.dayOfWeek.value - 1] += t.categoryAmount
        }
        return DayOfWeek.entries.map { day -> day to (if (monday.plusDays((day.value - 1).toLong()).isAfter(today)) null else sums[day.value - 1]) }
    }

    /** Income against spending for the last [count] budget months; the current one runs to today. */
    fun saved(transactions: Iterable<Transaction>, today: LocalDate, monthStartDay: Int, zone: ZoneId, count: Int = 4): List<SavedMonth> {
        val current = Period.containing(today, monthStartDay)
        return (count - 1 downTo 0).map { i ->
            val p = Period.startingIn(current.yearMonth.minusMonths(i.toLong()), monthStartDay)
            val end = if (i == 0) today else p.endInclusive
            var income = 0L; var spent = 0L
            for (t in transactions) {
                val d = dateOf(t, zone)
                if (d.isBefore(p.start) || d.isAfter(end)) continue
                when (t.kind) {
                    TxKind.INCOME -> income += t.amount
                    TxKind.EXPENSE -> spent += t.categoryAmount
                    else -> {}
                }
            }
            SavedMonth(p.yearMonth, income, spent)
        }
    }

    /** Where the money went by store (the entry's note), largest first. */
    fun stores(transactions: Iterable<Transaction>, byId: Map<Long, Category>, from: LocalDate, to: LocalDate, zone: ZoneId): List<Store> =
        transactions.filter { it.kind == TxKind.EXPENSE && it.note.isNotBlank() && dateOf(it, zone).let { d -> !d.isBefore(from) && !d.isAfter(to) } }
            .groupBy { it.note.trim().lowercase() }
            .map { (_, list) ->
                val latest = list.maxBy { it.epochMillis }
                val cat = list.groupingBy { Spending.topOf(it.categoryId, byId)?.id }.eachCount().maxByOrNull { it.value }?.key?.let { byId[it] }
                Store(latest.note.trim(), cat, list.sumOf { it.categoryAmount }, list.size)
            }
            .sortedByDescending { it.total }

    /** Buys under [limit] between two dates: how many, how much, and in which categories. */
    fun smallBuys(transactions: Iterable<Transaction>, byId: Map<Long, Category>, from: LocalDate, to: LocalDate, zone: ZoneId, limit: Long = SMALL_LIMIT): SmallBuys {
        val list = transactions.filter { it.kind == TxKind.EXPENSE && it.categoryAmount in 1 until limit && dateOf(it, zone).let { d -> !d.isBefore(from) && !d.isAfter(to) } }
        val byCat = list.groupBy { Spending.topOf(it.categoryId, byId) }
            .mapNotNull { (c, l) -> c?.let { Triple(it, l.sumOf { t -> t.categoryAmount }, l.size) } }
            .sortedByDescending { it.second }
        return SmallBuys(list.sumOf { it.categoryAmount }, list.size, byCat)
    }

    /** Small-buy totals for the last [count] budget months, the current one to today. */
    fun smallBuysByMonth(transactions: Iterable<Transaction>, byId: Map<Long, Category>, today: LocalDate, monthStartDay: Int, zone: ZoneId, count: Int = 4): List<Pair<YearMonth, Long>> {
        val current = Period.containing(today, monthStartDay)
        return (count - 1 downTo 0).map { i ->
            val p = Period.startingIn(current.yearMonth.minusMonths(i.toLong()), monthStartDay)
            p.yearMonth to smallBuys(transactions, byId, p.start, if (i == 0) today else p.endInclusive, zone).total
        }
    }

    /**
     * Categories that went up every month over the last [months] completed budget months, by at least 20% and $50
     * overall. The current month isn't counted because it isn't over.
     */
    fun creeping(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, monthStartDay: Int, zone: ZoneId, months: Int = 3): List<Creep> {
        val byId = categories.associateBy { it.id }
        val current = Period.containing(today, monthStartDay)
        val periods = (months downTo 1).map { Period.startingIn(current.yearMonth.minusMonths(it.toLong()), monthStartDay) }
        val totals = periods.map { Spending.byTopCategory(transactions, byId, it.start, it.endInclusive, zone) }
        return categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && !it.hidden }
            .mapNotNull { c ->
                val v = totals.map { it[c.id] ?: 0L }
                val rising = v.zipWithNext().all { (a, b) -> b > a }
                if (!rising || v.first() <= 0) return@mapNotNull null
                val rise = v.last() - v.first()
                if (rise < 5_000 || v.last() < v.first() * 1.2) return@mapNotNull null
                Creep(c, periods.map { it.yearMonth }.zip(v))
            }
            .sortedByDescending { it.rise }
    }

    /** This week so far against the same days last week, per top category. */
    fun weekChanges(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, zone: ZoneId): List<CategoryChange> {
        val byId = categories.associateBy { it.id }
        val monday = weekStart(today)
        val (from, to) = samePointBefore(monday, today, monday.minusWeeks(1))
        val now = Spending.byTopCategory(transactions, byId, monday, today, zone)
        val before = Spending.byTopCategory(transactions, byId, from, to, zone)
        return (now.keys + before.keys).mapNotNull { id -> byId[id]?.let { CategoryChange(it, now[id] ?: 0, before[id] ?: 0) } }
            .filter { it.change != 0L }
    }
}
