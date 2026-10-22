package dev.krishnagurram.kassa.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CardCycleTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 22)

    private fun millis(date: String) = LocalDate.parse(date).atTime(12, 0).toInstant(zone).toEpochMilli()

    private fun card(statement: Int, due: Int) =
        Account(id = 10, name = "Card", type = AccountType.CREDIT_CARD, statementDay = statement, dueDay = due)

    private fun charge(date: String, cents: Long) =
        Transaction(kind = TxKind.EXPENSE, amount = cents, accountId = 10, epochMillis = millis(date))

    private fun payment(date: String, cents: Long) =
        Transaction(kind = TxKind.TRANSFER, amount = cents, accountId = 1, toAccountId = 10, epochMillis = millis(date))

    @Test
    fun dueSameMonthWhenDueDayIsAfterStatementDay() {
        // Statement closes on the 2nd, due on the 27th of the same month.
        val close = CardCycle.lastClose(2, today)
        assertEquals(LocalDate.of(2026, 10, 2), close)
        assertEquals(LocalDate.of(2026, 10, 27), CardCycle.dueFor(2, 27, close))
    }

    @Test
    fun dueNextMonthWhenDueDayIsBeforeStatementDay() {
        // Statement closes on the 9th, due on the 4th of the next month.
        val close = CardCycle.lastClose(9, today)
        assertEquals(LocalDate.of(2026, 10, 9), close)
        assertEquals(LocalDate.of(2026, 11, 4), CardCycle.dueFor(9, 4, close))
    }

    @Test
    fun lastCloseRollsBackWhenThisMonthsCloseIsAhead() {
        assertEquals(LocalDate.of(2026, 9, 29), CardCycle.lastClose(29, today))
    }

    @Test
    fun shortMonthsUseTheirLastDay() {
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.closeIn(java.time.YearMonth.of(2026, 2), 31))
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.lastClose(30, LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun outstandingIsLastStatementMinusPaymentsSinceItClosed() {
        val txs = listOf(
            charge("2026-09-05", 10_000),   // previous statement (closed Sep 9)
            charge("2026-09-20", 20_000),   // this statement (Sep 10 to Oct 9)
            charge("2026-10-09", 5_000),    // closing day counts in this statement
            charge("2026-10-15", 7_500),    // not billed yet
            payment("2026-10-12", 5_000),
        )
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        assertEquals(25_000, s.statementTotal)
        assertEquals(5_000, s.paidSinceClose)
        assertEquals(20_000, s.outstanding)
        assertEquals(7_500, s.notYetBilled)
        assertEquals(CardSnapshot.Status.PARTLY_PAID, s.status)
        assertEquals(LocalDate.of(2026, 11, 4), s.due)
        assertEquals(13, s.daysUntilDue(today))
    }

    @Test
    fun refundsReduceTheStatement() {
        val txs = listOf(
            charge("2026-09-20", 20_000),
            Transaction(kind = TxKind.INCOME, amount = 3_000, accountId = 10, epochMillis = millis("2026-09-25")),
            payment("2026-10-20", 17_000),
        )
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        assertEquals(17_000, s.statementTotal)
        assertEquals(0, s.outstanding)
        assertEquals(CardSnapshot.Status.PAID, s.status)
    }
}
