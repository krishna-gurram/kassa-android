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

    data class RepeatingCharge(val note: String, val category: Category?, val amount: Long, val change: Long, val lastMonth: YearMonth)

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
}
