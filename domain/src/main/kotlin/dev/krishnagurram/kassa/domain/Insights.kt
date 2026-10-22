package dev.krishnagurram.kassa.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Insights are plain arithmetic on your own entries, so every number can be explained. "Usual" means the
 * average of the previous three budget months. Bills and repeating charges count as already spent rather
 * than being projected forward, so an insurance payment on the 1st doesn't look like a spending spree.
 */
object Insights {

    data class Flag(
        val category: Category,
        val soFar: Long,
        val projected: Long,
        val usual: Long,
        val budget: Long?,
    ) {
        /** What holding to the usual month would save. */
        val extra: Long get() = projected - usual
        /** A round cap to suggest: the usual month, rounded up to the next $50. */
        val suggestedCap: Long get() = maxOf(5_000, ((usual + 4_999) / 5_000) * 5_000)
    }

    data class Win(val category: Category, val underPace: Long)

    data class RepeatingCharge(val note: String, val category: Category?, val amount: Long, val change: Long, val lastMonth: YearMonth)

    data class MonthTotal(val month: YearMonth, val spent: Long, val projected: Long?)

    data class WeekCompare(val soFar: Long, val typical: Long, val byCategory: List<Triple<Category, Long, Long>>)

    /** Thresholds the user can choose: how far over the usual pace before a category is flagged. */
    enum class Sensitivity(val margin: Double, val label: String) {
        GENTLE(0.30, "Gentle"), BALANCED(0.15, "Balanced"), STRICT(0.05, "Strict")
    }

    fun usual(categoryId: Long, transactions: List<Transaction>, categories: Map<Long, Category>, current: Period, monthStartDay: Int, zone: ZoneId): Long {
        var total = 0L
        for (i in 1..3) {
            val p = Period.startingIn(current.yearMonth.minusMonths(i.toLong()), monthStartDay)
            total += Spending.byTopCategory(transactions, categories, p.start, p.endInclusive, zone)[categoryId] ?: 0
        }
        return Math.round(total / 3.0)
    }

    fun flags(
        transactions: List<Transaction>,
        categories: List<Category>,
        budgets: List<Budget>,
        fixedNotes: Set<String>,
        today: LocalDate,
        monthStartDay: Int,
        zone: ZoneId,
        sensitivity: Sensitivity,
        dismissed: Set<Long> = emptySet(),
    ): List<Flag> {
        val period = Period.containing(today, monthStartDay)
        val byId = categories.associateBy { it.id }
        val progress = period.progress(today)
        val mtdAll = Spending.byTopCategory(transactions, byId, period.start, today, zone)
        val fixedByTop = HashMap<Long, Long>()
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE || t.note.lowercase() !in fixedNotes) continue
            val d = Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            if (d.isBefore(period.start) || d.isAfter(today)) continue
            val top = Spending.topOf(t.categoryId, byId) ?: continue
            fixedByTop[top.id] = (fixedByTop[top.id] ?: 0) + t.categoryAmount
        }
        return categories
            .filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.countsTowardDaily && !it.hidden && it.id !in dismissed }
            .mapNotNull { c ->
                val mtd = mtdAll[c.id] ?: 0
                if (mtd < 5_000) return@mapNotNull null
                val fixed = fixedByTop[c.id] ?: 0
                val projected = fixed + Math.round((mtd - fixed) / progress)
                val usual = usual(c.id, transactions, byId, period, monthStartDay, zone)
                val budget = Budgets.amountFor(c.id, period.yearMonth, budgets)
                val overUsual = usual > 0 && projected > usual * (1 + sensitivity.margin) && projected - usual > 4_000
                val overBudget = budget != null && budget > 0 && mtd > budget
                if (overUsual || overBudget) Flag(c, mtd, projected, usual, budget) else null
            }
            .sortedByDescending { it.extra }
    }

    /** Categories furthest under their budget's even pace, for "one win" in the recap. */
    fun wins(transactions: List<Transaction>, categories: List<Category>, budgets: List<Budget>, today: LocalDate, monthStartDay: Int, zone: ZoneId): List<Win> {
        val period = Period.containing(today, monthStartDay)
        val byId = categories.associateBy { it.id }
        val mtd = Spending.byTopCategory(transactions, byId, period.start, today, zone)
        return categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.countsTowardDaily && !it.hidden && (mtd[it.id] ?: 0) > 0 }
            .mapNotNull { c ->
                val b = Budgets.amountFor(c.id, period.yearMonth, budgets) ?: return@mapNotNull null
                val under = Math.round(b * period.progress(today)) - (mtd[c.id] ?: 0)
                if (under > 0) Win(c, under) else null
            }
            .sortedByDescending { it.underPace }
    }

    /** Average spend per weekday (Monday first) over the last [weeks] weeks, for daily categories only. */
    fun weekdayAverages(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, zone: ZoneId, weeks: Int = 13): List<Pair<DayOfWeek, Long>> {
        val byId = categories.associateBy { it.id }
        val from = today.minusWeeks(weeks.toLong())
        val sums = LongArray(7)
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            val top = Spending.topOf(t.categoryId, byId) ?: continue
            if (!top.countsTowardDaily) continue
            val d = Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            if (!d.isBefore(from) && d.isBefore(today)) sums[d.dayOfWeek.value - 1] += t.categoryAmount
        }
        return DayOfWeek.entries.map { it to Math.round(sums[it.value - 1].toDouble() / weeks) }
    }

    /** Daily-category spending for the last [count] budget months, with a projection for the current one. */
    fun monthTotals(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, monthStartDay: Int, zone: ZoneId, count: Int = 5): List<MonthTotal> {
        val byId = categories.associateBy { it.id }
        val daily = categories.filter { it.parentId == null && it.countsTowardDaily }.map { it.id }.toSet()
        val current = Period.containing(today, monthStartDay)
        return (count - 1 downTo 0).map { i ->
            val p = Period.startingIn(current.yearMonth.minusMonths(i.toLong()), monthStartDay)
            val end = if (i == 0) today else p.endInclusive
            val spent = Spending.byTopCategory(transactions, byId, p.start, end, zone).filterKeys { it in daily }.values.sum()
            MonthTotal(p.yearMonth, spent, if (i == 0) Math.round(spent / p.progress(today)) else null)
        }
    }

    /**
     * Charges that come back about monthly at a steady amount: seen in at least three months, once a month,
     * on similar days. [change] is how much the latest one differs from the one before (a price increase).
     */
    fun repeating(transactions: List<Transaction>, categories: Map<Long, Category>, zone: ZoneId): List<RepeatingCharge> {
        return transactions.filter { it.kind == TxKind.EXPENSE && it.note.isNotBlank() }
            .groupBy { it.note.trim().lowercase() }
            .mapNotNull { (_, list) ->
                val ts = list.sortedBy { it.epochMillis }
                val dates = ts.map { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }
                val months = dates.map { YearMonth.from(it) }
                if (months.toSet().size < 3 || months.toSet().size != ts.size) return@mapNotNull null
                val earlier = ts.dropLast(1).map { it.amount }
                if (earlier.max() > earlier.min() * 1.12) return@mapNotNull null
                val days = dates.map { it.dayOfMonth }
                if (days.max() - days.min() > 5) return@mapNotNull null
                val last = ts.last()
                RepeatingCharge(last.note.trim(), Spending.topOf(last.categoryId, categories), last.amount, last.amount - ts[ts.size - 2].amount, months.last())
            }
            .sortedByDescending { it.amount }
    }

    /** This week so far (Monday on) against the average of the same days over the previous 12 weeks. */
    fun week(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, zone: ZoneId): WeekCompare {
        val byId = categories.associateBy { it.id }
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val span = ChronoUnit.DAYS.between(monday, today)
        val daily = categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.countsTowardDaily && !it.hidden }
        val now = Spending.byTopCategory(transactions, byId, monday, today, zone)
        val typical = HashMap<Long, Double>()
        for (w in 1..12) {
            val s = monday.minusWeeks(w.toLong())
            Spending.byTopCategory(transactions, byId, s, s.plusDays(span), zone).forEach { (k, v) -> typical[k] = (typical[k] ?: 0.0) + v / 12.0 }
        }
        val rows = daily.map { Triple(it, now[it.id] ?: 0L, Math.round(typical[it.id] ?: 0.0)) }.filter { it.second > 0 || it.third > 0 }.sortedByDescending { it.second }
        return WeekCompare(rows.sumOf { it.second }, rows.sumOf { it.third }, rows)
    }
}
