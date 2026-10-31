package app.kassa.money.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The time windows the app reads entries for, worked out from "today" and the phone's zone. Plain Kotlin, so the day
 * maths has JVM tests; the app recomputes them when the date or time zone changes (see `DayClock`), so a phone left
 * open past midnight, or carried across a time zone, never keeps yesterday's windows.
 */
object QueryWindows {
    /** How far back the ledger reaches: card statements and three months of comparisons. */
    const val RECENT_DAYS = 130L

    /** What repeats is found in this many days: long enough for a yearly charge to show up twice. */
    const val HISTORY_DAYS = 800L

    /** The first moment of [day] in [zone], in epoch milliseconds (a day that starts in a daylight saving gap starts after it). */
    fun startOf(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** From where the recent window reads: the start of the day [RECENT_DAYS] before [today]. */
    fun recentFrom(today: LocalDate, zone: ZoneId): Long = startOf(today.minusDays(RECENT_DAYS), zone)

    /** From where the repeating-charges window reads. */
    fun historyFrom(today: LocalDate, zone: ZoneId): Long = startOf(today.minusDays(HISTORY_DAYS), zone)

    /** The first and last millisecond of [month] in [zone]. */
    fun monthRange(month: YearMonth, zone: ZoneId): LongRange =
        startOf(month.atDay(1), zone)..(startOf(month.plusMonths(1).atDay(1), zone) - 1)

    /**
     * The month a screen that follows the calendar should show after the date moved from [before] to [after]: someone
     * looking at the current month moves on with it; someone who paged back to an older month stays there.
     */
    fun followMonth(shown: YearMonth, before: LocalDate, after: LocalDate): YearMonth =
        if (shown == YearMonth.from(before)) YearMonth.from(after) else shown
}

/** What the Activity search types become in a SQL LIKE pattern: the text itself, never a wildcard. */
object SearchText {
    /** The character the queries name in `ESCAPE`. */
    const val ESCAPE = '\\'

    /** [text] with `%`, `_` and the escape character escaped, so a typed "50%" finds "50%" and not everything starting with 50. */
    fun escape(text: String): String = buildString {
        for (c in text) {
            if (c == '%' || c == '_' || c == ESCAPE) append(ESCAPE)
            append(c)
        }
    }

    /** The pattern that matches [text] anywhere in a column, for `LIKE ... ESCAPE '\'`. */
    fun contains(text: String): String = "%${escape(text.trim())}%"
}
