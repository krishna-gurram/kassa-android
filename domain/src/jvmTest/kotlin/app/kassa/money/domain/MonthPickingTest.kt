package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonthPickingTest {
    private val latest = YearMonth.of(2026, 11)

    @Test
    fun monthsAfterTheLatestAreDisabled() {
        val now = MonthPicking.cells(2026, latest)
        assertEquals(12, now.size)
        assertTrue(now[10].enabled)
        assertFalse(now[11].enabled)
        assertTrue(MonthPicking.cells(2025, latest).all { it.enabled })
        assertTrue(MonthPicking.cells(2027, latest).none { it.enabled })
    }

    @Test
    fun theYearArrowsStopAtTheEnds() {
        assertTrue(MonthPicking.canPreviousYear(1991))
        assertFalse(MonthPicking.canPreviousYear(MonthPicking.EARLIEST_YEAR))
        assertTrue(MonthPicking.canNextYear(2025, latest))
        assertFalse(MonthPicking.canNextYear(2026, latest))
    }

    @Test
    fun onlyARealMonthNoLaterThanTheLatestCanBePicked() {
        assertTrue(MonthPicking.canPick(2026, 11, latest))
        assertFalse(MonthPicking.canPick(2026, 12, latest))
        assertFalse(MonthPicking.canPick(2026, 0, latest))
        assertFalse(MonthPicking.canPick(1989, 5, latest))
    }

    @Test
    fun tappingTheSameTotalAgainShowsEverything() {
        assertEquals(EntryFilter.EXPENSE, EntryFilter.ALL.toggled(EntryFilter.EXPENSE))
        assertEquals(EntryFilter.ALL, EntryFilter.EXPENSE.toggled(EntryFilter.EXPENSE))
        assertEquals(EntryFilter.INCOME, EntryFilter.EXPENSE.toggled(EntryFilter.INCOME))
        assertEquals(EntryFilter.ALL, EntryFilter.named("nonsense"))
        assertTrue(EntryFilter.EXPENSE.keeps(TxKind.EXPENSE))
        assertFalse(EntryFilter.EXPENSE.keeps(TxKind.INCOME))
        assertFalse(EntryFilter.INCOME.keeps(TxKind.TRANSFER))
        assertTrue(EntryFilter.ALL.keeps(TxKind.TRANSFER))
    }

    @Test
    fun theWeekStartsOnTheFirstWeekdayThePhoneNames() {
        assertEquals(DayOfWeek.SUNDAY, WeekRules.firstDayOf(1))
        assertEquals(DayOfWeek.MONDAY, WeekRules.firstDayOf(2))
        assertEquals(DayOfWeek.SATURDAY, WeekRules.firstDayOf(7))
        assertEquals(DayOfWeek.MONDAY, WeekRules.firstDayOf(0))
        // Thursday 22 October 2026: a Sunday week starts on the 18th, a Monday week on the 19th, a Saturday week on the 17th.
        val thursday = LocalDate(2026, 10, 22)
        assertEquals(LocalDate(2026, 10, 18), WeekPace.startOf(thursday, WeekRules.firstDayOf(1)))
        assertEquals(LocalDate(2026, 10, 19), WeekPace.startOf(thursday, WeekRules.firstDayOf(2)))
        assertEquals(LocalDate(2026, 10, 17), WeekPace.startOf(thursday, WeekRules.firstDayOf(7)))
        // The first day itself, and the day before it, which belongs to the week before.
        val sunday = LocalDate(2026, 10, 18)
        assertEquals(sunday, WeekPace.startOf(sunday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate(2026, 10, 12), WeekPace.startOf(sunday, DayOfWeek.MONDAY))
        assertEquals(LocalDate(2026, 10, 11), WeekPace.startOf(LocalDate(2026, 10, 17), DayOfWeek.SUNDAY))
    }
}
