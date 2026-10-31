package app.kassa.money.domain

/**
 * Calendar maths for loans, on plain epoch days (days since 1970-01-01, as an [Int]).
 *
 * A date with no time and no zone can't be moved by a clock change, so a daylight-saving weekend is still two days
 * and a leap year still has a 29th of February. There is no java.time in here on purpose: the loan rules are meant to
 * move into the shared Kotlin library for iOS, and the screens convert at the edges with
 * `LocalDate.toEpochDay().toInt()` and `LocalDate.ofEpochDay(day.toLong())`.
 */
object LoanDates {

    data class Ymd(val year: Int, val month: Int, val day: Int)

    fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** The epoch day of a calendar date (proleptic Gregorian, month 1 to 12). */
    fun epochDay(year: Int, month: Int, day: Int): Int {
        require(month in 1..12 && day in 1..daysInMonth(year, month)) { "Not a date: $year-$month-$day" }
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** The calendar date of an epoch day. */
    fun ymd(epochDay: Int): Ymd {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return Ymd(year, month, day)
    }

    fun dayOfMonth(epochDay: Int): Int = ymd(epochDay).day

    /** Whole days from [from] to [to]; negative when [to] is earlier. */
    fun daysBetween(from: Int, to: Int): Int = to - from

    /**
     * [months] calendar months after [epochDay] (before when negative), landing on [anchorDay] of that month, or on
     * its last day when the month is shorter: a due day of 31 falls on 28 February, then on 31 March again because
     * every step counts from the anchor, not from the shortened date before it.
     */
    fun addMonths(epochDay: Int, months: Int, anchorDay: Int = dayOfMonth(epochDay)): Int {
        val d = ymd(epochDay)
        val index = d.year * 12 + (d.month - 1) + months
        val year = index.floorDiv(12)
        val month = index.mod(12) + 1
        return epochDay(year, month, minOf(anchorDay.coerceAtLeast(1), daysInMonth(year, month)))
    }
}
