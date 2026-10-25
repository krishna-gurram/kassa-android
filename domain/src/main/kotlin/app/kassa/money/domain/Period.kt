package app.kassa.money.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * A budget month. Most people use the 1st, but the month can start on any day from 1 to 28,
 * for example on payday. The period is named after the calendar month it starts in.
 */
data class Period(val start: LocalDate, val endInclusive: LocalDate) {
    val days: Long get() = ChronoUnit.DAYS.between(start, endInclusive) + 1
    val yearMonth: YearMonth get() = YearMonth.from(start)
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    /** Share of the period that has passed, counting [today] as passed. */
    fun progress(today: LocalDate): Double =
        ((ChronoUnit.DAYS.between(start, today) + 1).coerceIn(0, days)).toDouble() / days

    companion object {
        fun startingIn(month: YearMonth, startDay: Int): Period {
            require(startDay in 1..28) { "Month start day must be 1 to 28" }
            val start = month.atDay(startDay)
            val end = month.plusMonths(1).atDay(startDay).minusDays(1)
            return Period(start, end)
        }

        fun containing(date: LocalDate, startDay: Int): Period {
            val month = if (date.dayOfMonth < startDay) YearMonth.from(date).minusMonths(1) else YearMonth.from(date)
            return startingIn(month, startDay)
        }
    }
}

