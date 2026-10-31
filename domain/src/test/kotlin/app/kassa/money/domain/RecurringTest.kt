package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurringTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: String) = LocalDate.parse(d).atTime(9, 0).toInstant(zone).toEpochMilli()
    private val elec = Recurring(id = 1, name = "Electric bill", categoryId = 5, accountId = 2, dayOfMonth = 20, fixedAmount = null)
    private fun bill(d: String, c: Long) = Transaction(kind = TxKind.EXPENSE, amount = c, accountId = 2, categoryId = 5, epochMillis = at(d), note = "Electric bill")

    private val history = listOf(bill("2026-07-20", 23_100), bill("2026-08-20", 24_400), bill("2026-09-20", 19_800), bill("2026-06-20", 16_800))

    @Test
    fun estimatesFromLastThree() {
        assertEquals(Math.round((23_100 + 24_400 + 19_800) / 3.0), RecurringRules.estimate(elec, history, LocalDate.parse("2026-10-20"), zone))
        assertEquals(9_900, RecurringRules.estimate(elec.copy(fixedAmount = 9_900), history, LocalDate.parse("2026-10-20"), zone))
        assertNull(RecurringRules.estimate(elec, emptyList(), LocalDate.parse("2026-10-20"), zone))
    }

    @Test
    fun dueOnlyAfterItsDayAndOnlyOnce() {
        assertFalse(RecurringRules.isDue(elec, history, LocalDate.parse("2026-10-19"), zone))
        assertTrue(RecurringRules.isDue(elec, history, LocalDate.parse("2026-10-22"), zone))
        assertFalse(RecurringRules.isDue(elec.copy(handledMonth = 202610), history, LocalDate.parse("2026-10-22"), zone))
        // Logged by hand this month: no need to ask.
        assertFalse(RecurringRules.isDue(elec, history + bill("2026-10-21", 20_000), LocalDate.parse("2026-10-22"), zone))
    }

    @Test
    fun nextDueIsThisMonthsUntilHandledThenNextMonths() {
        val oct = LocalDate.parse("2026-10-20")
        // No window: the due date is there from the start of the month and stays while overdue.
        assertEquals(oct, RecurringRules.nextDue(elec, history, LocalDate.parse("2026-10-01"), zone))
        assertEquals(oct, RecurringRules.nextDue(elec, history, LocalDate.parse("2026-10-30"), zone))
        // October handled: November's is next, however far off.
        val handled = elec.copy(handledMonth = 202610)
        assertEquals(LocalDate.parse("2026-11-20"), RecurringRules.nextDue(handled, history, LocalDate.parse("2026-10-30"), zone))
        assertEquals(LocalDate.parse("2026-11-03"), RecurringRules.nextDue(elec.copy(dayOfMonth = 3, handledMonth = 202610), history, LocalDate.parse("2026-10-30"), zone))
        // Entered by hand in October: counts as done.
        assertEquals(LocalDate.parse("2026-11-20"), RecurringRules.nextDue(elec, history + bill("2026-10-21", 20_000), LocalDate.parse("2026-10-22"), zone))
        assertNull(RecurringRules.nextDue(elec.copy(active = false), history, LocalDate.parse("2026-10-22"), zone))
    }

    @Test
    fun billNamesMatchNotesThatAddTheMonth() {
        assertTrue(RecurringRules.matches(elec, bill("2026-10-20", 1).copy(note = "Electric bill October 2026")))
    }

    @Test
    fun shortMonthsClampTheDay() {
        assertEquals(LocalDate.parse("2026-02-28"), RecurringRules.dueDate(elec.copy(dayOfMonth = 31), java.time.YearMonth.of(2026, 2)))
    }

    @Test
    fun holdingsUseExactShares() {
        val h = Holding(accountId = 1, ticker = "VTI", microShares = 34_200_000, price = 31_240, priceMillis = 0)
        assertEquals(Math.round(34.2 * 31_240), h.value)
    }
}
