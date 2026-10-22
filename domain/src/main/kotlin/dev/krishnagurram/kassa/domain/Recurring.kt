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

    /** Entries that look like this bill: same category and the same name, ignoring case. */
    fun matches(r: Recurring, t: Transaction): Boolean =
        t.kind == TxKind.EXPENSE && t.categoryId == r.categoryId && t.note.trim().equals(r.name.trim(), ignoreCase = true)

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

    /** Due within the next [days] days and not yet due, for the "Coming up" list. */
    fun upcoming(r: Recurring, today: LocalDate, days: Long): LocalDate? {
        if (!r.active) return null
        val d = dueDate(r, YearMonth.from(today))
        return d.takeIf { it.isAfter(today) && !it.isAfter(today.plusDays(days)) && r.handledMonth < Budgets.key(YearMonth.from(today)) }
    }
}

/** A stock or fund in an investment account, valued at the last price the user entered. */
data class Holding(
    val id: Long = 0,
    val accountId: Long,
    val ticker: String,
    /** Shares in millionths, so fractional shares stay exact. */
    val microShares: Long,
    val price: Long,
    val priceMillis: Long,
) {
    val value: Long get() = Math.round(microShares / 1_000_000.0 * price)
    val shares: Double get() = microShares / 1_000_000.0
}
