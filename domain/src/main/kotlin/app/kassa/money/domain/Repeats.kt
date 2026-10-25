package app.kassa.money.domain

import java.text.DateFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How often a charge comes back. A gap between two charges counts when it falls in [minDays]..[maxDays]. */
enum class Cadence(val label: String, val minDays: Int, val maxDays: Int, val days: Int, val perYear: Double) {
    WEEKLY("Weekly", 6, 8, 7, 365.25 / 7),
    BIWEEKLY("Every 2 weeks", 12, 16, 14, 365.25 / 14),
    MONTHLY("Monthly", 25, 35, 30, 12.0),
    QUARTERLY("Every 3 months", 85, 95, 91, 4.0),
    YEARLY("Yearly", 350, 380, 365, 1.0);

    /** Fewest charges before a pattern counts: two for yearly, three for everything else. */
    val minCount: Int get() = if (this == YEARLY) 2 else 3

    fun after(d: LocalDate): LocalDate = when (this) {
        WEEKLY -> d.plusWeeks(1)
        BIWEEKLY -> d.plusWeeks(2)
        MONTHLY -> d.plusMonths(1)
        QUARTERLY -> d.plusMonths(3)
        YEARLY -> d.plusYears(1)
    }
}

/**
 * One charge (or income) that comes back on a schedule. [amounts] and [dates] are oldest first.
 * [fixed] when at least 75% of the amounts are within 10% of the median; otherwise the amount varies.
 */
data class Repeat(
    val key: String,
    val name: String,
    val kind: TxKind,
    val categoryId: Long?,
    val cadence: Cadence,
    val amounts: List<Long>,
    val dates: List<LocalDate>,
    val fixed: Boolean,
) {
    val last: LocalDate get() = dates.last()
    val low: Long get() = amounts.min()
    val high: Long get() = amounts.max()

    /** The latest amount for a fixed charge, the median for one that varies. */
    val typical: Long get() = if (fixed) amounts.last() else amounts.sorted()[amounts.size / 2]

    /** How much the latest fixed charge went up or down from the one before. Zero when it varies. */
    val change: Long get() = if (fixed && amounts.size >= 2) amounts.last() - amounts[amounts.size - 2] else 0

    val perYear: Long get() = Math.round(typical * cadence.perYear)
    val perMonth: Long get() = Math.round(typical * cadence.perYear / 12)

    /** The next time it should come, today or later. */
    fun next(today: LocalDate): LocalDate {
        var d = cadence.after(last)
        while (d.isBefore(today)) d = cadence.after(d)
        return d
    }

    /** A monthly expense with exactly the same amount the last three times: the only kind Kassa suggests as a bill. */
    val steadyMonthly: Boolean
        get() = kind == TxKind.EXPENSE && cadence == Cadence.MONTHLY && amounts.size >= 3 && amounts.takeLast(3).toSet().size == 1

    /** The usual day of the month, from the last three. */
    val dayOfMonth: Int get() = dates.takeLast(3).map { it.dayOfMonth }.sorted().let { it[it.size / 2] }
}

/**
 * Finds what repeats in your entries. Notes are matched loosely ("Mortgage October 2026" and "mortgage nov" are the
 * same charge) and one merchant's different charges are told apart by amount, so two subscriptions at the same
 * store become two repeats.
 */
object Repeats {

    /**
     * Notes are grouped by this: lowercase, with trailing words that change every time removed (months, years,
     * numbers, #ids). "Mortgage October 2026" and "mortgage - nov" both become "mortgage".
     */
    fun key(note: String): String = name(note).lowercase(Locale.ROOT)

    /** The note as written, without the changing words at the end. */
    fun name(note: String): String {
        val words = note.trim().split(Regex("\\s+")).toMutableList()
        while (words.size > 1 && isNoise(words.last())) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim { !it.isLetterOrDigit() }.ifEmpty { note.trim() }
    }

    /** Charges that repeat and haven't stopped, biggest yearly cost first. */
    fun find(transactions: List<Transaction>, today: LocalDate, zone: ZoneId): List<Repeat> =
        transactions.asSequence()
            .filter { (it.kind == TxKind.EXPENSE || it.kind == TxKind.INCOME) && it.note.isNotBlank() }
            .groupBy { it.kind to key(it.note) }
            .filterKeys { it.second.isNotEmpty() }
            .flatMap { (k, list) -> seriesIn(k.second, k.first, list.sortedBy { it.epochMillis }, zone) }
            .filter { ChronoUnit.DAYS.between(it.last, today) <= 2L * it.cadence.days }
            .sortedByDescending { it.perYear }

    /** The whole group if it's regular; otherwise each run of similar amounts that is. */
    private fun seriesIn(key: String, kind: TxKind, list: List<Transaction>, zone: ZoneId): List<Repeat> {
        regular(key, kind, list, zone)?.let { return listOf(it) }
        return byAmount(list).mapNotNull { regular(key, kind, it, zone) }
    }

    /** Groups amounts within 25% of the smallest in the group, each group back in date order. */
    private fun byAmount(list: List<Transaction>): List<List<Transaction>> {
        val groups = mutableListOf<MutableList<Transaction>>()
        for (t in list.sortedBy { it.amount }) {
            val g = groups.lastOrNull()
            if (g != null && t.amount <= g.first().amount * 1.25) g += t else groups += mutableListOf(t)
        }
        return groups.filter { it.size >= 2 }.map { g -> g.sortedBy { it.epochMillis } }
    }

    /** A repeat when the typical gap fits a cadence and at least 80% of the gaps do. */
    private fun regular(key: String, kind: TxKind, ts: List<Transaction>, zone: ZoneId): Repeat? {
        if (ts.size < 2) return null
        val dates = ts.map { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
        val median = gaps.sorted()[gaps.size / 2]
        val cadence = Cadence.entries.firstOrNull { median in it.minDays..it.maxDays } ?: return null
        if (ts.size < cadence.minCount) return null
        if (gaps.count { it in cadence.minDays..cadence.maxDays } < gaps.size * 0.8) return null
        val amounts = ts.map { it.amount }
        val mid = amounts.sorted()[amounts.size / 2]
        val fixed = amounts.count { Math.abs(it - mid) <= mid / 10 } >= amounts.size * 0.75
        val last = ts.last()
        return Repeat(key, name(last.note), kind, last.categoryId, cadence, amounts, dates, fixed)
    }

    private fun isNoise(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() && it != '#' }
        return w.isEmpty() || w.startsWith("#") || w.all { it.isDigit() || it in "./-'" } || w.trimEnd('.') in months
    }

    /** Month names and short names in English and the phone's language. */
    private val months: Set<String> by lazy {
        listOf(Locale.ENGLISH, Locale.getDefault()).flatMap { l ->
            val s = DateFormatSymbols.getInstance(l)
            (s.months.toList() + s.shortMonths.toList())
        }.map { it.lowercase(Locale.ROOT).trimEnd('.') }.filter { it.isNotEmpty() }.toSet() + "sept"
    }
}
