package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek

/**
 * The rules of the "Choose month" sheet: a year with arrows and its twelve months, none after the latest month (Activity stops at the
 * current one). The month's name is the screen's to write, with the phone's calendar.
 */
object MonthPicking {

    /** The earliest year the arrows go back to. */
    const val EARLIEST_YEAR = 1990

    data class Cell(val month: Int, val enabled: Boolean)

    fun cells(year: Int, latest: YearMonth): List<Cell> = (1..12).map { m -> Cell(m, !YearMonth(year, m).isAfter(latest)) }

    fun canPreviousYear(year: Int): Boolean = year > EARLIEST_YEAR

    fun canNextYear(year: Int, latest: YearMonth): Boolean = year < latest.year

    /** True when [year] and [month] can be picked: a real month, no later than [latest]. */
    fun canPick(year: Int, month: Int, latest: YearMonth): Boolean =
        month in 1..12 && year >= EARLIEST_YEAR && !YearMonth(year, month).isAfter(latest)
}

/** Which entries Activity lists: all of the month, only spending or only income. Tapping the picked total again shows all. */
enum class EntryFilter {
    ALL, EXPENSE, INCOME;

    fun keeps(kind: TxKind): Boolean = when (this) {
        ALL -> true
        EXPENSE -> kind == TxKind.EXPENSE
        INCOME -> kind == TxKind.INCOME
    }

    /** What tapping [tapped] does from here: the same one again goes back to ALL. */
    fun toggled(tapped: EntryFilter): EntryFilter = if (this == tapped) ALL else tapped

    companion object {
        /** The filter named [name] ("ALL", "EXPENSE", "INCOME"); anything else is ALL. */
        fun named(name: String): EntryFilter = entries.firstOrNull { it.name == name } ?: ALL
    }
}

/** First day of the week from the phone's own number for it. */
object WeekRules {

    /** Apple's `Calendar.firstWeekday` (1 is Sunday, 2 Monday ... 7 Saturday) as a day of the week; anything else reads as Monday. */
    fun firstDayOf(calendarFirstWeekday: Int): DayOfWeek = when (calendarFirstWeekday) {
        1 -> DayOfWeek.SUNDAY
        3 -> DayOfWeek.TUESDAY
        4 -> DayOfWeek.WEDNESDAY
        5 -> DayOfWeek.THURSDAY
        6 -> DayOfWeek.FRIDAY
        7 -> DayOfWeek.SATURDAY
        else -> DayOfWeek.MONDAY
    }
}
