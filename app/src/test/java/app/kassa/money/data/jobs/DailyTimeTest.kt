package app.kassa.money.data.jobs

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class DailyTimeTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(zone)
    private val nine = 21 * 60

    @Test
    fun laterTodayIsToday() {
        assertEquals(at("2026-05-04T21:00"), DailyTime.next(at("2026-05-04T08:15"), nine))
    }

    @Test
    fun atOrPastTheTimeIsTomorrow() {
        assertEquals(at("2026-05-05T21:00"), DailyTime.next(at("2026-05-04T21:00"), nine))
        assertEquals(at("2026-05-05T21:00"), DailyTime.next(at("2026-05-04T23:59"), nine))
    }

    @Test
    fun midnightAndTheLastMinute() {
        assertEquals(at("2026-05-05T00:00"), DailyTime.next(at("2026-05-04T12:00"), 0))
        assertEquals(at("2026-05-04T23:59"), DailyTime.next(at("2026-05-04T12:00"), 23 * 60 + 59))
    }

    @Test
    fun keepsTheLocalTimeAcrossDaylightSaving() {
        // Clocks go forward on 8 March 2026 and back on 1 November 2026 in New York.
        val springForward = DailyTime.next(at("2026-03-07T23:00"), nine)
        assertEquals(LocalDateTime.parse("2026-03-08T21:00"), springForward.toLocalDateTime())
        val fallBack = DailyTime.next(at("2026-10-31T21:30"), nine)
        assertEquals(LocalDateTime.parse("2026-11-01T21:00"), fallBack.toLocalDateTime())
    }

    @Test
    fun aTimeInTheSpringGapMovesForward() {
        val next = DailyTime.next(at("2026-03-07T12:00"), 2 * 60 + 30)
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), next.toLocalDateTime())
    }
}
