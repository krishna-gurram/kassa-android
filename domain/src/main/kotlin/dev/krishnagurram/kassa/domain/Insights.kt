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
}
