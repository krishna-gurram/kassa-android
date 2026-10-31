package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryWindowsTest {
    private val utc = ZoneOffset.UTC
    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun theRecentWindowStartsAtMidnightThe130thDayBack() {
        val today = LocalDate.of(2026, 10, 31)
        assertEquals(LocalDate.of(2026, 6, 23).atStartOfDay(utc).toInstant().toEpochMilli(), QueryWindows.recentFrom(today, utc))
        assertEquals(LocalDate.of(2024, 8, 22).atStartOfDay(utc).toInstant().toEpochMilli(), QueryWindows.historyFrom(today, utc))
    }

    @Test
    fun theWindowMovesByExactlyOneDayWhenTheDateRollsOver() {
        val day = LocalDate.of(2026, 12, 31)
        val before = QueryWindows.recentFrom(day, utc)
        val after = QueryWindows.recentFrom(day.plusDays(1), utc)
        assertEquals(24 * 60 * 60 * 1000L, after - before)
        assertEquals(24 * 60 * 60 * 1000L, QueryWindows.historyFrom(day.plusDays(1), utc) - QueryWindows.historyFrom(day, utc))
    }

    @Test
    fun theWindowFollowsTheTimeZone() {
        val today = LocalDate.of(2026, 10, 31)
        // The same calendar day starts 5h30 earlier in Kolkata than in UTC.
        assertEquals(QueryWindows.recentFrom(today, utc) - (5 * 60 + 30) * 60_000L, QueryWindows.recentFrom(today, kolkata))
        assertTrue(QueryWindows.recentFrom(today, newYork) > QueryWindows.recentFrom(today, utc))
    }

    @Test
    fun aDaylightSavingChangeInsideTheWindowDoesNotShiftItsMidnight() {
        // 2026-03-08 is the US spring-forward day: its midnight is still local midnight (05:00 UTC), not an hour off.
        val springForward = LocalDate.of(2026, 3, 8)
        assertEquals(LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant().toEpochMilli(), QueryWindows.startOf(springForward, newYork))
        // 130 days after it, the window starts at local midnight even though the clocks moved in between.
        val today = springForward.plusDays(QueryWindows.RECENT_DAYS)
        val from = QueryWindows.recentFrom(today, newYork)
        assertEquals(springForward.atStartOfDay(newYork).toInstant().toEpochMilli(), from)
        assertEquals(0, java.time.Instant.ofEpochMilli(from).atZone(newYork).toLocalTime().toSecondOfDay())
    }

    @Test
    fun aMonthRangeCoversTheMonthAndNothingElse() {
        val month = YearMonth.of(2026, 2)
        val r = QueryWindows.monthRange(month, utc)
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(utc).toInstant().toEpochMilli(), r.first)
        assertEquals(LocalDate.of(2026, 3, 1).atStartOfDay(utc).toInstant().toEpochMilli() - 1, r.last)
        // The month that holds a daylight saving change is a day shorter or longer than 28 x 24 hours.
        val march = QueryWindows.monthRange(YearMonth.of(2026, 3), newYork)
        assertEquals(31 * 24 * 3_600_000L - 3_600_000L, march.last - march.first + 1)
    }

    @Test
    fun theCurrentMonthMovesOnWithTheCalendarAndAnOlderOneStays() {
        val lastNight = LocalDate.of(2026, 10, 31)
        val thisMorning = LocalDate.of(2026, 11, 1)
        assertEquals(YearMonth.of(2026, 11), QueryWindows.followMonth(YearMonth.of(2026, 10), lastNight, thisMorning))
        assertEquals(YearMonth.of(2026, 9), QueryWindows.followMonth(YearMonth.of(2026, 9), lastNight, thisMorning))
        // Within a month nothing moves.
        assertEquals(YearMonth.of(2026, 10), QueryWindows.followMonth(YearMonth.of(2026, 10), lastNight, lastNight.minusDays(1)))
    }

    @Test
    fun searchTextNeverActsAsAWildcard() {
        assertEquals("50\\%", SearchText.escape("50%"))
        assertEquals("a\\_b", SearchText.escape("a_b"))
        assertEquals("c:\\\\x", SearchText.escape("c:\\x"))
        assertEquals("%50\\% off%", SearchText.contains("  50% off "))
        assertEquals("%coffee%", SearchText.contains("coffee"))
    }
}
