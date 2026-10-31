package app.kassa.money.data.jobs

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a daily job set for a time of day runs next. Plain Kotlin, so it has a JVM test. */
object DailyTime {
    /**
     * Today at [minuteOfDay] if that's still after [now], otherwise tomorrow at that time, in [now]'s zone. Each day
     * is worked out from its own date, so a daylight saving change keeps the local time (a time that doesn't exist
     * that day moves forward by the gap, as the clock does).
     */
    fun next(now: ZonedDateTime, minuteOfDay: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
        if (today.isAfter(now)) return today
        return now.toLocalDate().plusDays(1).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
    }

    /** [minuteOfDay] on the day [epochDay] in [zone], by the phone's clock (a time in a daylight saving gap moves forward). */
    fun at(epochDay: Long, minuteOfDay: Int, zone: ZoneId): ZonedDateTime =
        LocalDate.ofEpochDay(epochDay).atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone)
}
