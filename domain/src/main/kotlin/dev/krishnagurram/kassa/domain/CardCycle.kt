package dev.krishnagurram.kassa.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Statement math for a credit card.
 *
 * "Outstanding" is what the last statement billed and is still unpaid. "Not yet billed" is everything charged
 * after the last statement closed. A statement or due day past the end of a short month falls on its last day.
 * A due day on or before the statement day means the following month.
 */
data class CardSnapshot(
    val lastClose: LocalDate,
    val previousClose: LocalDate,
    val nextClose: LocalDate,
    val due: LocalDate,
    val statementTotal: Long,
    val paidSinceClose: Long,
    val notYetBilled: Long,
) {
    val outstanding: Long get() = (statementTotal - paidSinceClose).coerceAtLeast(0)

    fun daysUntilDue(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, due)

    val status: Status
        get() = when {
            statementTotal <= 0 -> Status.NOTHING_DUE
            outstanding == 0L -> Status.PAID
            paidSinceClose > 0 -> Status.PARTLY_PAID
            else -> Status.UNPAID
        }

    enum class Status { UNPAID, PARTLY_PAID, PAID, NOTHING_DUE }
}

object CardCycle {

    fun closeIn(month: YearMonth, statementDay: Int): LocalDate =
        month.atDay(statementDay.coerceIn(1, month.lengthOfMonth()))

    /** The most recent statement close on or before [ref]. */
    fun lastClose(statementDay: Int, ref: LocalDate): LocalDate {
        val thisMonth = closeIn(YearMonth.from(ref), statementDay)
        return if (thisMonth.isAfter(ref)) closeIn(YearMonth.from(ref).minusMonths(1), statementDay) else thisMonth
    }

    fun previousClose(statementDay: Int, close: LocalDate): LocalDate =
        closeIn(YearMonth.from(close).minusMonths(1), statementDay)

    fun nextClose(statementDay: Int, close: LocalDate): LocalDate =
        closeIn(YearMonth.from(close).plusMonths(1), statementDay)

    fun dueFor(statementDay: Int, dueDay: Int, close: LocalDate): LocalDate {
        val month = if (dueDay <= statementDay) YearMonth.from(close).plusMonths(1) else YearMonth.from(close)
        return month.atDay(dueDay.coerceIn(1, month.lengthOfMonth()))
    }

    /**
     * Builds the snapshot for [card] as of [today]. Charges are expenses on the card minus income (refunds)
     * to it. Payments are transfers into the card.
     */
    fun snapshot(
        card: Account,
        transactions: Iterable<Transaction>,
        today: LocalDate,
        zone: ZoneId,
    ): CardSnapshot {
        val statementDay = requireNotNull(card.statementDay) { "${card.name} has no statement day" }
        val dueDay = card.dueDay ?: statementDay
        val last = lastClose(statementDay, today)
        val prev = previousClose(statementDay, last)

        var statement = 0L
        var paid = 0L
        var notBilled = 0L
        for (t in transactions) {
            val date = java.time.Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            val charge = when {
                t.kind == TxKind.EXPENSE && t.accountId == card.id -> t.amount
                t.kind == TxKind.INCOME && t.accountId == card.id -> -t.amount
                else -> 0L
            }
            if (charge != 0L) {
                when {
                    date.isAfter(prev) && !date.isAfter(last) -> statement += charge
                    date.isAfter(last) && !date.isAfter(today) -> notBilled += charge
                }
            }
            if (t.kind == TxKind.TRANSFER && t.toAccountId == card.id && date.isAfter(last) && !date.isAfter(today)) {
                paid += t.amount
            }
        }
        return CardSnapshot(
            lastClose = last,
            previousClose = prev,
            nextClose = nextClose(statementDay, last),
            due = dueFor(statementDay, dueDay, last),
            statementTotal = statement,
            paidSinceClose = paid,
            notYetBilled = notBilled,
        )
    }
}
