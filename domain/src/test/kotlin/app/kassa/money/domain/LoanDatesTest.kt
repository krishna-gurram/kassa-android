package app.kassa.money.domain

import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoanDatesTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    @Test
    fun knownEpochDays() {
        assertEquals(0, day(1970, 1, 1))
        assertEquals(20_758, day(2026, 11, 1))
        assertEquals(-1, day(1969, 12, 31))
    }

    @Test
    fun matchesTheCalendarForEveryDayForTwoHundredYears() {
        // java.time is only the oracle here; the loan rules don't use it.
        for (d in -36_500..36_500) {
            val ld = LocalDate.ofEpochDay(d.toLong())
            val ymd = LoanDates.ymd(d)
            assertEquals(Triple(ld.year, ld.monthValue, ld.dayOfMonth), Triple(ymd.year, ymd.month, ymd.day), "day $d")
            assertEquals(d, LoanDates.epochDay(ymd.year, ymd.month, ymd.day))
        }
    }

    @Test
    fun aClockChangeWeekendIsStillTwoDays() {
        // The US clocks went forward on 8 March 2026 and the EU's on 29 March; epoch days have no clock to move.
        assertEquals(2, LoanDates.daysBetween(day(2026, 3, 7), day(2026, 3, 9)))
        assertEquals(2, LoanDates.daysBetween(day(2026, 3, 28), day(2026, 3, 30)))
        assertEquals(2, LoanDates.daysBetween(day(2026, 10, 31), day(2026, 11, 2)))
    }

    @Test
    fun leapYearsHaveA29thOfFebruary() {
        assertEquals(2, LoanDates.daysBetween(day(2024, 2, 28), day(2024, 3, 1)))
        assertEquals(1, LoanDates.daysBetween(day(2025, 2, 28), day(2025, 3, 1)))
        assertEquals(366, LoanDates.daysBetween(day(2024, 1, 1), day(2025, 1, 1)))
        assertTrue(LoanDates.isLeap(2000))
        assertFalse(LoanDates.isLeap(1900))
        assertFalse(LoanDates.isLeap(2026))
        assertFailsWith<IllegalArgumentException> { day(2025, 2, 29) }
    }

    @Test
    fun addingMonthsKeepsTheAnchorDayAndClampsShortMonths() {
        assertEquals(day(2026, 2, 28), LoanDates.addMonths(day(2026, 1, 31), 1))
        assertEquals(day(2028, 2, 29), LoanDates.addMonths(day(2028, 1, 31), 1))
        // A due day of 31 recovers after a short month because every step counts from the anchor.
        assertEquals(day(2026, 3, 31), LoanDates.addMonths(day(2026, 2, 28), 1, anchorDay = 31))
        assertEquals(day(2027, 1, 15), LoanDates.addMonths(day(2026, 12, 15), 1))
        assertEquals(day(2025, 12, 1), LoanDates.addMonths(day(2026, 1, 1), -1))
        assertEquals(day(2027, 11, 1), LoanDates.addMonths(day(2026, 11, 1), 12))
        assertEquals(day(2026, 1, 31), LoanDates.addMonths(day(2026, 3, 31), -2))
    }
}
