package app.kassa.money.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

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
    fun startOf(day: LocalDate, zone: TimeZone): Long = startOfDayMillis(day, zone)

    /** From where the recent window reads: the start of the day [RECENT_DAYS] before [today]. */
    fun recentFrom(today: LocalDate, zone: TimeZone): Long = startOf(today.minusDays(RECENT_DAYS), zone)

    /** From where the repeating-charges window reads. */
    fun historyFrom(today: LocalDate, zone: TimeZone): Long = startOf(today.minusDays(HISTORY_DAYS), zone)

    /** The first and last millisecond of [month] in [zone]. */
    fun monthRange(month: YearMonth, zone: TimeZone): LongRange =
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

    /**
     * Whether [value] matches the LIKE [pattern] that [contains] made, the way SQLite's `LIKE ... ESCAPE '\'` reads it: `%` is any run of
     * characters, `_` any one, the escape character makes the next one plain, and letters match without regard to case (A to Z only, as
     * SQLite does by default). A search done in memory on iOS runs through this, so a typed "50%" finds "50%" and not "500".
     */
    fun like(value: String, pattern: String): Boolean {
        // The pattern as tokens: a plain character, or one of the two wildcards.
        val plain = ArrayList<Char>()
        val kinds = ArrayList<Int>() // 0 plain, 1 any one, 2 any run
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == ESCAPE && i + 1 < pattern.length) {
                plain.add(pattern[i + 1]); kinds.add(0); i += 2
                continue
            }
            when (c) {
                '%' -> { plain.add(c); kinds.add(2) }
                '_' -> { plain.add(c); kinds.add(1) }
                else -> { plain.add(c); kinds.add(0) }
            }
            i++
        }
        // ok[j] after step k: the first k tokens can match the first j characters.
        var ok = BooleanArray(value.length + 1)
        ok[0] = true
        for (k in kinds.indices) {
            val next = BooleanArray(value.length + 1)
            when (kinds[k]) {
                2 -> {
                    var seen = false
                    for (j in 0..value.length) {
                        if (ok[j]) seen = true
                        next[j] = seen
                    }
                }
                1 -> for (j in 1..value.length) next[j] = ok[j - 1]
                else -> for (j in 1..value.length) next[j] = ok[j - 1] && sameLetter(value[j - 1], plain[k])
            }
            ok = next
        }
        return ok[value.length]
    }

    private fun sameLetter(a: Char, b: Char): Boolean =
        a == b || (a in 'A'..'Z' && a + 32 == b) || (a in 'a'..'z' && a - 32 == b)

    /** Whether any of [fields] contains the typed [text] (blank text matches nothing), or [amount] is the exact amount typed ([cents] above zero). */
    fun matches(text: String, fields: List<String>, amount: Long, cents: Long): Boolean {
        if (text.isBlank()) return false
        if (cents > 0L && amount == cents) return true
        val pattern = contains(text)
        return fields.any { it.isNotEmpty() && like(it, pattern) }
    }
}
