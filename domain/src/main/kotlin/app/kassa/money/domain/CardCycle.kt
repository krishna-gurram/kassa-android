package app.kassa.money.domain

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
    /**
     * The last statement less what's been paid or refunded since. Negative when you paid more than it: that
     * extra is a credit, shown in green, and it comes off the total. Nothing is clamped to zero.
     */
    val balancePayable: Long get() = statementTotal - paidSinceClose

    /** What still has to be paid by the due date: [balancePayable], or zero once it's covered. */
    val payable: Long get() = balancePayable.coerceAtLeast(0)

    /** Charges since the statement date. */
    val outstanding: Long get() = notYetBilled

    /** Everything on the card now: positive is owed, negative is a credit. */
    val totalBalance: Long get() = balancePayable + outstanding

    /** What the bank owes you when payments and refunds went past everything charged. */
    val credit: Long get() = (-totalBalance).coerceAtLeast(0)

    /** Same as [totalBalance]; adds up with other debts. */
    val owed: Long get() = totalBalance

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

    /**
     * The due date of the statement that closed on [close]: always after it. A due day on or before the statement day
     * means the following month. In a short month the statement day itself moves back (a statement on the 31st closes
     * on February 28), so a due day that is after the statement day on paper can land on or before the close; that too
     * means the following month, never the close date or a day earlier.
     */
    fun dueFor(statementDay: Int, dueDay: Int, close: LocalDate): LocalDate {
        val month = YearMonth.from(close)
        val sameMonth = month.atDay(dueDay.coerceIn(1, month.lengthOfMonth()))
        if (dueDay > statementDay && sameMonth.isAfter(close)) return sameMonth
        val next = month.plusMonths(1)
        return next.atDay(dueDay.coerceIn(1, next.lengthOfMonth()))
    }

    /**
     * Builds the snapshot for [card] as of [today] from what's owed, so an unpaid balance carries forward the way
     * it does on a real statement (0% APR balances, partial payments, anything left over).
     *
     * - The statement balance is what was owed when the cycle closed, the end of the day before the statement
     *   date: everything before the statement date, including any balance
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
            // The statement covers everything up to the day before the statement date; the statement date itself
            // starts the new cycle (statement on the 16th: through the 15th is payable, the 16th on is outstanding).
            if (date.isBefore(last)) continue
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
