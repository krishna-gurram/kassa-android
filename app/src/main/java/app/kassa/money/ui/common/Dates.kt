package app.kassa.money.ui.common

import android.text.format.DateFormat
import app.kassa.money.domain.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate

/*
 * The one place a date is written for people, so every screen reads "14 Nov" in the UK, India and the Gulf and "Nov 14"
 * in the US. Each helper asks the phone for the best pattern for a skeleton (which fields to show) in its own language,
 * order and punctuation. Never write DateTimeFormatter.ofPattern("MMM d") or a month name plus a year by hand.
 * Whole-date styles (the medium or full date a calendar announces) use DateTimeFormatter.ofLocalizedDate, which is
 * locale-aware already.
 */

private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

private fun formatter(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return formatters.getOrPut(locale.toLanguageTag() + "|" + skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

/** "Nov 14" or "14 Nov". */
internal fun shortDate(date: LocalDate): String = date.toJavaLocalDate().format(formatter("MMMd"))

/** "Nov 14, 2026" or "14 Nov 2026". */
internal fun shortDateYear(date: LocalDate): String = date.toJavaLocalDate().format(formatter("yMMMd"))

/** "Sat, Nov 14" or "Sat 14 Nov". */
internal fun weekdayDate(date: LocalDate): String = date.toJavaLocalDate().format(formatter("EEEMMMd"))

/** "Saturday, Nov 14" or "Saturday 14 Nov". */
internal fun weekdayDateLong(date: LocalDate): String = date.toJavaLocalDate().format(formatter("EEEEMMMd"))

/** "November 2026". */
internal fun monthYear(month: YearMonth): String = month.atDay(1).toJavaLocalDate().format(formatter("yMMMM"))

/** "Nov 2026". */
internal fun monthYearShort(month: YearMonth): String = month.atDay(1).toJavaLocalDate().format(formatter("yMMM"))

/** "Nov": the month alone, short, for a tile or a tab. */
internal fun monthShort(date: LocalDate): String = date.toJavaLocalDate().format(formatter("MMM"))

/** "2026": the year alone, in the phone's digits. */
internal fun yearOnly(year: Int): String = YearMonth.of(year, 1).atDay(1).toJavaLocalDate().format(formatter("y"))

/** "November" (or "Nov" when [full] is false): the month's own name in the phone's language, for a label that shows a month alone. */
internal fun monthName(month: YearMonth, full: Boolean = true): String =
    java.time.Month.of(month.month).getDisplayName(if (full) TextStyle.FULL else TextStyle.SHORT, Locale.getDefault())
