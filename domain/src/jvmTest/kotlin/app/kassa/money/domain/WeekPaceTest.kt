package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeekPaceTest {
    private val zone = TimeZone.UTC
    private fun spend(d: String, cents: Long, share: Long? = null) = Transaction(
        kind = TxKind.EXPENSE, amount = cents, accountId = 1, myShare = share,
        epochMillis = millisAt(LocalDate.parse(d), 12, 0, zone),
    )
    private val thursday = LocalDate(2026, 10, 22)

    @Test
    fun weekStartsOnTheChosenDay() {
        assertEquals(LocalDate(2026, 10, 19), WeekPace.startOf(thursday, DayOfWeek.MONDAY))
        assertEquals(LocalDate(2026, 10, 18), WeekPace.startOf(thursday, DayOfWeek.SUNDAY))
        assertEquals(thursday, WeekPace.startOf(thursday, DayOfWeek.THURSDAY))
    }

    @Test
    fun comparesWithTheSameWeekdayInPastWeeks() {
        val txs = listOf(
            spend("2026-10-19", 10_000), spend("2026-10-22", 5_000),
            // Last week: 20_000 by Thursday, 30_000 for the whole week.
            spend("2026-10-12", 20_000), spend("2026-10-17", 10_000),
            // Two weeks ago: 40_000 by Thursday, 50_000 whole week.
            spend("2026-10-08", 40_000), spend("2026-10-11", 10_000),
            // Income doesn't count.
            Transaction(kind = TxKind.INCOME, amount = 99_000, accountId = 1, epochMillis = startOfDayMillis(LocalDate.parse("2026-10-20"), zone)),
        )
        val r = WeekPace.compute(txs, thursday, DayOfWeek.MONDAY, zone, weeks = 2)
        assertEquals(15_000, r.spent)
        assertEquals(30_000, r.usualByNow)
        assertEquals(40_000, r.usualWeek)
        assertEquals(2, r.weeksCompared)
    }

    @Test
    fun ignoresWeeksBeforeTheFirstEntryAndUsesMyShare() {
        val txs = listOf(spend("2026-10-20", 10_000, share = 5_000), spend("2026-10-13", 8_000))
        val r = WeekPace.compute(txs, thursday, DayOfWeek.MONDAY, zone)
        assertEquals(5_000, r.spent)
        assertEquals(1, r.weeksCompared)
        assertEquals(8_000, r.usualByNow)
    }

    @Test
    fun noHistoryMeansNoComparison() {
        val r = WeekPace.compute(listOf(spend("2026-10-21", 1_000)), thursday, DayOfWeek.MONDAY, zone)
        assertEquals(1_000, r.spent)
        assertNull(r.usualByNow)
        assertEquals(0, r.weeksCompared)
    }
}
