package app.kassa.money.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/*
 * The small date API the rules use, so no rule needs the JDK's date classes (Kotlin/Native has none). The names follow the JDK's
 * (plusDays, isBefore, toEpochDay, YearMonth.atDay, ...) so a call site reads the same as before. Plain Kotlin on top of
 * kotlinx-datetime: nothing here is Android or JVM only.
 *
 * Month arithmetic clamps the day like the JDK does: 31 January plus one month is 28 (or 29) February.
 */

/** A calendar month of a year, `month` 1 to 12. Compares by year, then month; prints as "2026-11", the same as the JDK's. */
data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {

    init {
        require(month in 1..12) { "Month must be 1 to 12: $month" }
    }

    /** Same as [month]; the JDK called it `monthValue`. */
    val monthValue: Int get() = month

    fun plusMonths(months: Long): YearMonth {
        val index = year.toLong() * 12 + (month - 1) + months
        return YearMonth(index.floorDiv(12L).toInt(), index.mod(12L).toInt() + 1)
    }

    fun plusMonths(months: Int): YearMonth = plusMonths(months.toLong())

    fun minusMonths(months: Long): YearMonth = plusMonths(-months)

    fun minusMonths(months: Int): YearMonth = plusMonths(-months.toLong())

    fun lengthOfMonth(): Int = LoanDates.daysInMonth(year, month)

    /** The [day] of this month; throws when the month has no such day. */
    fun atDay(day: Int): LocalDate = LocalDate(year, month, day)

    fun atStartOfMonth(): LocalDate = atDay(1)

    fun atEndOfMonth(): LocalDate = atDay(lengthOfMonth())

    fun isBefore(other: YearMonth): Boolean = this < other

    fun isAfter(other: YearMonth): Boolean = this > other

    override fun compareTo(other: YearMonth): Int =
        if (year != other.year) year.compareTo(other.year) else month.compareTo(other.month)

    override fun toString(): String {
        val abs = if (year < 0) -year.toLong() else year.toLong()
        val digits = when {
            year > 9999 -> "+$year"
            year < 0 -> "-" + abs.toString().padStart(4, '0')
            else -> abs.toString().padStart(4, '0')
        }
        return digits + "-" + month.toString().padStart(2, '0')
    }

    companion object {
        fun of(year: Int, month: Int): YearMonth = YearMonth(year, month)

        fun from(date: LocalDate): YearMonth = YearMonth(date.year, date.monthNumber)
    }
}

// ---------- LocalDate ----------

fun LocalDate.plusDays(days: Long): LocalDate = localDateOfEpochDay(toEpochDay() + days)

fun LocalDate.plusDays(days: Int): LocalDate = plusDays(days.toLong())

fun LocalDate.minusDays(days: Long): LocalDate = plusDays(-days)

fun LocalDate.minusDays(days: Int): LocalDate = plusDays(-days.toLong())

fun LocalDate.plusWeeks(weeks: Long): LocalDate = plusDays(weeks * 7)

fun LocalDate.plusWeeks(weeks: Int): LocalDate = plusDays(weeks.toLong() * 7)

fun LocalDate.minusWeeks(weeks: Long): LocalDate = plusDays(-weeks * 7)

fun LocalDate.minusWeeks(weeks: Int): LocalDate = plusDays(-weeks.toLong() * 7)

/** [months] later (earlier when negative), keeping the day of the month or the last day of a shorter month. */
fun LocalDate.plusMonths(months: Long): LocalDate {
    val target = YearMonth.from(this).plusMonths(months)
    return target.atDay(minOf(dayOfMonth, target.lengthOfMonth()))
}

fun LocalDate.plusMonths(months: Int): LocalDate = plusMonths(months.toLong())

fun LocalDate.minusMonths(months: Long): LocalDate = plusMonths(-months)

fun LocalDate.minusMonths(months: Int): LocalDate = plusMonths(-months.toLong())

fun LocalDate.plusYears(years: Long): LocalDate = plusMonths(years * 12)

fun LocalDate.plusYears(years: Int): LocalDate = plusMonths(years.toLong() * 12)

fun LocalDate.minusYears(years: Long): LocalDate = plusMonths(-years * 12)

fun LocalDate.minusYears(years: Int): LocalDate = plusMonths(-years.toLong() * 12)

/** Days since 1970-01-01. */
fun LocalDate.toEpochDay(): Long = toEpochDays().toLong()

fun localDateOfEpochDay(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())

fun localDateOfEpochDay(epochDay: Int): LocalDate = LocalDate.fromEpochDays(epochDay)

fun LocalDate.isBefore(other: LocalDate): Boolean = this < other

fun LocalDate.isAfter(other: LocalDate): Boolean = this > other

fun LocalDate.lengthOfMonth(): Int = LoanDates.daysInMonth(year, monthNumber)

fun LocalDate.lengthOfYear(): Int = if (LoanDates.isLeap(year)) 366 else 365

/** The same month with another day of the month; throws when the month has no such day. */
fun LocalDate.withDayOfMonth(day: Int): LocalDate = LocalDate(year, monthNumber, day)

/** Whole days from [from] to [to]; negative when [to] is earlier. Replaces `ChronoUnit.DAYS.between`. */
fun daysBetween(from: LocalDate, to: LocalDate): Long = to.toEpochDay() - from.toEpochDay()

/** Whole months from [from] to [to]; negative when [to] is earlier. Replaces `ChronoUnit.MONTHS.between`. */
fun monthsBetween(from: YearMonth, to: YearMonth): Long = (to.year - from.year) * 12L + (to.month - from.month)

// ---------- instants and zones ----------

/** The calendar day of [epochMillis] on the clock of [zone]. */
fun dateOfMillis(epochMillis: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

/** The first moment of [day] in [zone], in epoch milliseconds (a day that starts in a daylight saving gap starts after it). */
fun startOfDayMillis(day: LocalDate, zone: TimeZone): Long = day.atStartOfDayIn(zone).toEpochMilliseconds()

/** The moment [hour]:[minute] of [day] on the clock of [zone], in epoch milliseconds. */
fun millisAt(day: LocalDate, hour: Int, minute: Int, zone: TimeZone): Long =
    LocalDateTime(day.year, day.monthNumber, day.dayOfMonth, hour, minute).toInstant(zone).toEpochMilliseconds()
