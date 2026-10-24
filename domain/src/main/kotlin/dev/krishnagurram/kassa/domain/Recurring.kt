package dev.krishnagurram.kassa.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A bill that comes back every month. It never posts by itself: on its day it becomes a card on Today that asks
 * for approval, prefilled with a fixed amount or an estimate from recent bills.
 */
data class Recurring(
    val id: Long = 0,
    val name: String,
    val categoryId: Long,
    val accountId: Long,
    val dayOfMonth: Int,
    /** Null means estimate from the last three matching entries. */
    val fixedAmount: Long?,
    val active: Boolean = true,
    /** yyyyMM of the last month that was approved, skipped or already logged. */
    val handledMonth: Int = 0,
)

object RecurringRules {
    fun dueDate(r: Recurring, month: YearMonth): LocalDate = month.atDay(r.dayOfMonth.coerceIn(1, month.lengthOfMonth()))

    /**
     * Entries that look like this bill: same category and the same name, ignoring case and the words that change
     * each month ("Mortgage October 2026" matches a bill named "Mortgage"; see [Repeats.key]).
     */
    fun matches(r: Recurring, t: Transaction): Boolean =
        t.kind == TxKind.EXPENSE && t.categoryId == r.categoryId && Repeats.key(t.note) == Repeats.key(r.name)

    /** Average of the last three matching entries before [before], or the fixed amount. Null when there's no history. */
    fun estimate(r: Recurring, history: List<Transaction>, before: LocalDate, zone: ZoneId): Long? {
        r.fixedAmount?.let { return it }
        val last = history.filter { matches(r, it) && Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate().isBefore(before) }
            .sortedByDescending { it.epochMillis }.take(3)
        return if (last.isEmpty()) null else Math.round(last.sumOf { it.amount }.toDouble() / last.size)
    }

    fun recent(r: Recurring, history: List<Transaction>, n: Int = 3): List<Long> =
        history.filter { matches(r, it) }.sortedByDescending { it.epochMillis }.take(n).map { it.amount }

    /**
     * Whether to ask about [r] today: its day has come this month, it hasn't been handled, and nothing matching
     * was already logged this month (so a bill entered by hand never gets asked about twice).
     */
    fun isDue(r: Recurring, history: List<Transaction>, today: LocalDate, zone: ZoneId): Boolean {
        if (!r.active) return false
        val month = YearMonth.from(today)
        if (r.handledMonth >= Budgets.key(month)) return false
        if (today.isBefore(dueDate(r, month))) return false
        return history.none { matches(r, it) && YearMonth.from(Instant.ofEpochMilli(it.epochMillis).atZone(zone)) == month }
    }

    /**
     * The due date to ask about: this month's or next month's, once it's at most [days] away or past, and only while
     * that month isn't handled and nothing matching was logged in it. Null when nothing needs asking.
     */
    fun nextDue(r: Recurring, history: List<Transaction>, today: LocalDate, zone: ZoneId, days: Long = 5): LocalDate? {
        if (!r.active) return null
        val now = YearMonth.from(today)
        return listOf(now, now.plusMonths(1)).map { dueDate(r, it) }.firstOrNull { due ->
            val month = YearMonth.from(due)
            r.handledMonth < Budgets.key(month) && !due.isAfter(today.plusDays(days)) &&
                history.none { matches(r, it) && YearMonth.from(Instant.ofEpochMilli(it.epochMillis).atZone(zone)) == month }
        }
    }

    /** Due within the next [days] days and not yet due, for the "Coming up" list. */
    fun upcoming(r: Recurring, today: LocalDate, days: Long): LocalDate? {
        if (!r.active) return null
        val d = dueDate(r, YearMonth.from(today))
        return d.takeIf { it.isAfter(today) && !it.isAfter(today.plusDays(days)) && r.handledMonth < Budgets.key(YearMonth.from(today)) }
    }
}
