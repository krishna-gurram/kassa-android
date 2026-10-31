package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun dueAlwaysFallsAfterTheCloseInShortMonths() {
        // Statement on the 30th, due on the 31st: February closes on the 28th, and a due day clamped to the 28th would
        // be the close itself, so it is due in March.
        val feb = CardCycle.lastClose(30, LocalDate.of(2026, 3, 5))
        assertEquals(LocalDate.of(2026, 2, 28), feb)
        assertEquals(LocalDate.of(2026, 3, 31), CardCycle.dueFor(30, 31, feb))
        // The same card in a 31-day month keeps its same-month due date.
        assertEquals(LocalDate.of(2026, 1, 31), CardCycle.dueFor(30, 31, LocalDate.of(2026, 1, 30)))
        // A 30-day month: statement on the 29th, due on the 30th, closes on the 29th, due the next day.
        assertEquals(LocalDate.of(2026, 4, 30), CardCycle.dueFor(29, 30, LocalDate.of(2026, 4, 29)))
        // Statement on the 28th, due on the 30th: February closes on the 28th, due in March on the 30th.
        assertEquals(LocalDate.of(2026, 3, 30), CardCycle.dueFor(28, 30, LocalDate.of(2026, 2, 28)))
    }

    @Test
    fun dueOnOrBeforeTheStatementDayIsNextMonthEvenWhenTheCloseMovedBack() {
        // Statement on the 31st, due on the 31st or the 30th: always the following month.
        assertEquals(LocalDate.of(2026, 3, 31), CardCycle.dueFor(31, 31, LocalDate.of(2026, 2, 28)))
        assertEquals(LocalDate.of(2026, 3, 30), CardCycle.dueFor(31, 30, LocalDate.of(2026, 2, 28)))
        // Following month shorter than the due day: clamped to its last day (January's close, February's due).
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.dueFor(31, 31, LocalDate.of(2026, 1, 31)))
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.dueFor(31, 29, LocalDate.of(2026, 1, 31)))
    }

    @Test
    fun leapYearFebruaryHasA29th() {
        val close = CardCycle.lastClose(30, LocalDate.of(2028, 3, 5))
        assertEquals(LocalDate.of(2028, 2, 29), close)
        assertEquals(LocalDate.of(2028, 3, 31), CardCycle.dueFor(30, 31, close))
        // Statement on the 28th, due on the 29th: a leap February has room, a plain one does not.
        assertEquals(LocalDate.of(2028, 2, 29), CardCycle.dueFor(28, 29, LocalDate.of(2028, 2, 28)))
        assertEquals(LocalDate.of(2026, 3, 29), CardCycle.dueFor(28, 29, LocalDate.of(2026, 2, 28)))
    }

    @Test
    fun theDueDateIsAfterTheCloseForEveryDayPairInEveryMonthOfTwoYears() {
        for (year in listOf(2026, 2028)) for (m in 1..12) for (statement in 1..31) for (due in 1..31) {
            val close = CardCycle.closeIn(java.time.YearMonth.of(year, m), statement)
            val d = CardCycle.dueFor(statement, due, close)
            assertTrue(d.isAfter(close), "statement $statement due $due close $close gave $d")
            assertTrue(d.isBefore(close.plusDays(63)), "statement $statement due $due close $close gave $d")
        }
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
            charge("2026-10-08", 5_000),    // the day before the statement date is the last day on it
            charge("2026-10-15", 7_500),    // not billed yet
            payment("2026-10-12", 5_000),
        )
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        // The September 5 charge was never paid, so it carries into this statement.
        assertEquals(35_000, s.statementTotal)
        assertEquals(5_000, s.paidSinceClose)
        assertEquals(30_000, s.payable)
        assertEquals(7_500, s.outstanding)
        assertEquals(37_500, s.totalBalance)
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
        assertEquals(0, s.payable)
        assertEquals(CardSnapshot.Status.PAID, s.status)
    }

    @Test
    fun overpayingTheStatementShowsACreditOnPayable() {
        // 100 payable, 159 paid: payable shows 59 in credit and the total drops by it.
        val txs = listOf(
            charge("2026-09-20", 10_000),
            charge("2026-10-15", 8_000),
            payment("2026-10-16", 15_900),
        )
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        assertEquals(-5_900, s.balancePayable)
        assertEquals(0, s.payable)
        assertEquals(8_000, s.outstanding)
        assertEquals(2_100, s.totalBalance)
        assertEquals(CardSnapshot.Status.PAID, s.status)
    }

    @Test
    fun unpaidBalanceCarriesForwardMonthAfterMonth() {
        // A 0% APR card: 14,000 owed from before (opening balance), nothing paid for months, new purchases each month.
        val c = card(9, 4).copy(openingBalance = -1_400_000)
        val txs = listOf(
            charge("2026-07-15", 20_000),
            charge("2026-08-15", 30_000),
            payment("2026-08-30", 10_000),
            charge("2026-09-15", 40_000),
            charge("2026-10-15", 5_000),
        )
        val s = CardCycle.snapshot(c, txs, today, zone)
        assertEquals(1_480_000, s.statementTotal)
        assertEquals(1_480_000, s.payable)
        assertEquals(5_000, s.outstanding)
        assertEquals(1_485_000, s.totalBalance)
        assertEquals(CardSnapshot.Status.UNPAID, s.status)
    }

    @Test
    fun givenBalanceOnlyNeedsEntriesSinceTheStatement() {
        // The app passes the card's real balance and only the months it has loaded.
        val recent = listOf(charge("2026-10-15", 5_000), payment("2026-10-16", 1_000_000))
        val s = CardCycle.snapshot(card(9, 4), recent, today, zone, balance = -1_400_000)
        assertEquals(2_395_000, s.statementTotal)
        assertEquals(1_395_000, s.payable)
        assertEquals(5_000, s.outstanding)
        assertEquals(1_400_000, s.totalBalance)
    }

    @Test
    fun cashWithdrawalsAndFeesCountAsCharges() {
        val txs = listOf(
            Transaction(kind = TxKind.TRANSFER, amount = 30_000, accountId = 10, toAccountId = 1, epochMillis = millis("2026-10-12")),
            charge("2026-10-13", 1_500),
        )
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        assertEquals(0, s.payable)
        assertEquals(31_500, s.outstanding)
        assertEquals(31_500, s.totalBalance)
    }

    @Test
    fun overpaidCardShowsACredit() {
        val txs = listOf(charge("2026-09-20", 10_000), payment("2026-10-12", 10_600))
        val s = CardCycle.snapshot(card(9, 4), txs, today, zone)
        assertEquals(-600, s.totalBalance)
        assertEquals(600, s.credit)
        assertEquals(-600, s.owed)
    }

    @Test
    fun theStatementDateStartsTheNextCycle() {
        // Statement on the 16th: the 15th is payable, the 16th is outstanding.
        val today16 = LocalDate.of(2026, 10, 20)
        val txs = listOf(charge("2026-10-15", 10_000), charge("2026-10-16", 2_500))
        val s = CardCycle.snapshot(card(16, 10), txs, today16, zone)
        assertEquals(10_000, s.payable)
        assertEquals(2_500, s.outstanding)
    }

    @Test
    fun onTheStatementDateItsChargesAreOutstanding() {
        val day = LocalDate.of(2026, 10, 16)
        val txs = listOf(charge("2026-10-15", 10_000), charge("2026-10-16", 2_500))
        val s = CardCycle.snapshot(card(16, 10), txs, day, zone)
        assertEquals(LocalDate.of(2026, 10, 16), s.lastClose)
        assertEquals(10_000, s.payable)
        assertEquals(2_500, s.outstanding)
    }
}
