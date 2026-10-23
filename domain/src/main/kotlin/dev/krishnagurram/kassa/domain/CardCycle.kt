package dev.krishnagurram.kassa.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Statement math for a credit card.
 *
 * Wording follows Money Manager and card issuers:
 * - [payable] ("Balance payable") is the last statement's balance (including anything carried from before) still
 *   unpaid. The due date applies to it.
 * - [outstanding] ("Outstanding") is what was charged after the statement date, less any payment beyond the
 *   statement balance. It goes on the next statement.
 * - [totalBalance] is the two together: what paying the card off today would take.
 * A statement or due day past the end of a short month falls on its last day.
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
    val payable: Long get() = (statementTotal - paidSinceClose).coerceAtLeast(0)

    /** Charges since the statement date. A payment beyond the statement balance comes off this first. */
    val outstanding: Long get() = (notYetBilled - (paidSinceClose - statementTotal).coerceAtLeast(0)).coerceAtLeast(0)

    val totalBalance: Long get() = payable + outstanding

    /** What the bank owes you when payments and refunds went past everything charged (an overpaid card). */
    val credit: Long get() = (paidSinceClose - statementTotal - notYetBilled).coerceAtLeast(0)

    /** The card's balance as a signed figure: positive is owed, negative is a credit. Adds up with other debts. */
    val owed: Long get() = totalBalance - credit

    fun daysUntilDue(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, due)

    val status: Status
        get() = when {
            statementTotal <= 0 -> Status.NOTHING_DUE
            payable == 0L -> Status.PAID
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
     * Builds the snapshot for [card] as of [today] from what's owed, so an unpaid balance carries forward the way
     * it does on a real statement (0% APR balances, partial payments, anything left over).
     *
     * - The statement balance is what was owed on the closing day: everything before it, including any balance
     *   carried from earlier months and the card's opening balance.
     * - Payments and credits since then (payments in, refunds) come off it first; what's left is balance payable.
     * - New charges since the closing day (purchases, cash withdrawals, fees, money sent from the card) are outstanding.
     *
     * [balance] is the card's current balance (negative while you owe). When it isn't given it's worked out from
     * [transactions], which then must be the card's whole history. Otherwise they only need to cover the time since
     * the last statement closed.
     */
    fun snapshot(
        card: Account,
        transactions: Iterable<Transaction>,
        today: LocalDate,
        zone: ZoneId,
        balance: Long? = null,
    ): CardSnapshot {
        val statementDay = requireNotNull(card.statementDay) { "${card.name} has no statement day" }
        val dueDay = card.dueDay ?: statementDay
        val last = lastClose(statementDay, today)
        val prev = previousClose(statementDay, last)
        val now = balance ?: balanceOf(card, transactions)

        var charges = 0L
        var credits = 0L
        var afterClose = 0L
        for (t in transactions) {
            val effect = t.effectOn(card.id)
            if (effect == 0L) continue
            val date = java.time.Instant.ofEpochMilli(t.epochMillis).atZone(zone).toLocalDate()
            if (!date.isAfter(last)) continue
            afterClose += effect
            if (date.isAfter(today)) continue
            if (effect < 0) charges += -effect else credits += effect
        }
        // Owed on the closing day: today's balance with everything since taken back out.
        val owedAtClose = -(now - afterClose)
        return CardSnapshot(
            lastClose = last,
            previousClose = prev,
            nextClose = nextClose(statementDay, last),
            due = dueFor(statementDay, dueDay, last),
            statementTotal = owedAtClose,
            paidSinceClose = credits,
            notYetBilled = charges,
        )
    }
}
