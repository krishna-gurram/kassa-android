package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber

/**
 * Insights are plain arithmetic on your own entries, so every number can be explained. "Usual" means the
 * average of the previous three budget months. Bills and repeating charges count as already spent rather
 * than being projected forward, so an insurance payment on the 1st doesn't look like a spending spree.
 */
object Insights {

    /** Average spend per weekday (Monday first) over the last [weeks] weeks, for daily categories only. */
    fun weekdayAverages(transactions: List<Transaction>, categories: List<Category>, today: LocalDate, zone: TimeZone, weeks: Int = 13): List<Pair<DayOfWeek, Long>> {
        val byId = categories.associateBy { it.id }
        val from = today.minusWeeks(weeks.toLong())
        val sums = LongArray(7)
        for (t in transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            val top = Spending.topOf(t.categoryId, byId) ?: continue
            if (!top.countsTowardDaily) continue
            val d = dateOfMillis(t.epochMillis, zone)
            if (!d.isBefore(from) && d.isBefore(today)) sums[d.dayOfWeek.isoDayNumber - 1] += t.categoryAmount
        }
        return DayOfWeek.entries.map { it to Math.round(sums[it.isoDayNumber - 1].toDouble() / weeks) }
    }
}
