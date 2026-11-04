package app.kassa.money.importer

/*
 * What a platform hands over after it ran SQL against a backup file from another app. The iOS app opens the picked file
 * (a plain SQLite database) read-only, runs the queries this package lists ([MoneyManagerSql.queries], [CashewSql.queries])
 * and pours every row into a [SqlResults], one cell at a time. Everything after that (which column means what, defaults for
 * a column an older backup lacks, decimal text from a float) is plain Kotlin here, so it is the same rules on every
 * platform and is tested on the JVM without a SQLite driver. Android reads through its own cursor classes today.
 *
 * Swift calls only: begin, addColumn, addNull, addInteger, addReal, addText, endRow, end and skip. Nothing here throws.
 */

/** A query the platform runs. An [optional] one that fails (an older backup lacks the table or column) is reported with [SqlResults.skip]. */
class SqlQuery(val name: String, val sql: String, val optional: Boolean)

/** One cell as SQLite stored it. */
internal sealed interface SqlCell {
    data object Missing : SqlCell
    data class Whole(val value: Long) : SqlCell
    data class Real(val value: Double) : SqlCell
    data class Words(val value: String) : SqlCell
}

internal class SqlTable(val columns: List<String>, val rows: List<List<SqlCell>>)

/** The answers to a list of [SqlQuery], by query name. */
class SqlResults {

    private val finished = HashMap<String, SqlTable>()
    private var name = ""
    private var columns = ArrayList<String>()
    private var rows = ArrayList<List<SqlCell>>()
    private var cells = ArrayList<SqlCell>()

    /** Starts the answer to the query called [name]. */
    fun begin(name: String) {
        this.name = name
        columns = ArrayList()
        rows = ArrayList()
        cells = ArrayList()
    }

    /** The next column's name, in the order of the query's columns (before any row). */
    fun addColumn(name: String) {
        columns.add(name)
    }

    fun addNull() {
        cells.add(SqlCell.Missing)
    }

    fun addInteger(value: Long) {
        cells.add(SqlCell.Whole(value))
    }

    fun addReal(value: Double) {
        cells.add(SqlCell.Real(value))
    }

    /** Text, and a blob read as text. */
    fun addText(value: String) {
        cells.add(SqlCell.Words(value))
    }

    /** The row's cells are all added. */
    fun endRow() {
        rows.add(cells)
        cells = ArrayList()
    }

    /** The answer started by [begin] is complete. */
    fun end() {
        finished[name] = SqlTable(columns.toList(), rows.toList())
        name = ""
    }

    /** The query called [name] failed; an optional one then reads as having no rows. */
    fun skip(name: String) {
        finished.remove(name)
    }

    fun has(name: String): Boolean = finished.containsKey(name)

    internal fun table(name: String): SqlTable? = finished[name]

    /** The rows of [name], or null when it failed or was never run. */
    internal fun rowsOf(name: String): List<SqlRow>? = finished[name]?.let { t -> t.rows.map { SqlRow(t.columns, it) } }

    /** The rows of a query that has to have worked. */
    internal fun need(name: String): List<SqlRow> = rowsOf(name) ?: throw IllegalStateException("No answer for $name")

    /** The rows of a query that may have failed: none then. */
    internal fun maybe(name: String): List<SqlRow> = rowsOf(name) ?: emptyList()
}

/** One row with the reading rules Android's cursor helpers use, so both platforms read a backup the same way. */
internal class SqlRow(private val columns: List<String>, private val cells: List<SqlCell>) {

    private fun at(i: Int): SqlCell = cells.getOrNull(i) ?: SqlCell.Missing

    /** The position of the column called [name] (capitals don't matter), or -1. */
    fun index(name: String): Int = columns.indexOfFirst { it.equals(name, ignoreCase = true) }

    fun isNull(i: Int): Boolean = at(i) == SqlCell.Missing

    /** Like Cursor.getString: a number is its text, null stays null. */
    fun str(i: Int): String? = when (val c = at(i)) {
        SqlCell.Missing -> null
        is SqlCell.Whole -> c.value.toString()
        is SqlCell.Real -> c.value.toString()
        is SqlCell.Words -> c.value
    }

    /** A whole number read from text or a float ("3", "3.0"), or null. */
    fun int(i: Int): Int? = str(i)?.trim()?.toDoubleOrNull()?.toInt()

    /** A number column as plain decimal text, whether it is stored as text or as a float or integer; "0" when null. */
    fun decimal(i: Int): String = when (val c = at(i)) {
        SqlCell.Missing -> "0"
        is SqlCell.Whole -> DecimalText.plain(c.value.toDouble())
        is SqlCell.Real -> DecimalText.plain(c.value)
        is SqlCell.Words -> c.value
    }

    /** The number as a Double (the budget amounts), 0 when it isn't one. */
    fun double(i: Int): Double = when (val c = at(i)) {
        SqlCell.Missing -> 0.0
        is SqlCell.Whole -> c.value.toDouble()
        is SqlCell.Real -> c.value
        is SqlCell.Words -> c.value.trim().toDoubleOrNull() ?: 0.0
    }

    fun isNumber(i: Int): Boolean = at(i).let { it is SqlCell.Whole || it is SqlCell.Real }

    /** The number as a Long, truncating a float; for a column that was checked with [isNumber]. */
    fun long(i: Int): Long = when (val c = at(i)) {
        is SqlCell.Whole -> c.value
        is SqlCell.Real -> c.value.toLong()
        else -> 0L
    }

    // By column name, for Cashew's tables, whose columns differ between versions: a missing column reads as null.

    fun str(name: String): String? = index(name).takeIf { it >= 0 }?.let { str(it) }

    fun int(name: String): Int? = index(name).takeIf { it >= 0 }?.let { int(it) }

    fun bool(name: String): Boolean? = int(name)?.let { it != 0 } ?: str(name)?.let { it.equals("true", ignoreCase = true) }

    /** Like [decimal] but trimmed, and empty for a missing column or null. */
    fun decimalText(name: String): String {
        val i = index(name)
        if (i < 0 || isNull(i)) return ""
        return when (at(i)) {
            is SqlCell.Whole, is SqlCell.Real -> decimal(i)
            else -> str(i).orEmpty().trim()
        }
    }

    /** Cashew stores times as Unix seconds (or milliseconds); a few settings keep ISO text instead, read by [textTime]. */
    fun millis(name: String, textTime: (String) -> Long): Long {
        val i = index(name)
        if (i < 0 || isNull(i)) return 0L
        if (isNumber(i)) {
            val v = long(i)
            // Seconds unless it's clearly already milliseconds (after the year 2286 in seconds).
            return if (v > 10_000_000_000L) v else v * 1000
        }
        return textTime(str(i).orEmpty())
    }
}

/** Decimal text from a float without BigDecimal: what `BigDecimal.valueOf(double).toPlainString()` gives, for reading amounts. */
object DecimalText {

    /** 12.5 is "12.5", 1.0E-5 is "0.000010", 1.2345678E7 is "12345678.0"; a number that isn't finite is "0". */
    fun plain(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val text = value.toString()
        val negative = text.startsWith("-")
        val body = if (negative) text.substring(1) else text
        val e = body.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (e >= 0) body.substring(0, e) else body
        val exponent = if (e >= 0) body.substring(e + 1).removePrefix("+").toIntOrNull() ?: 0 else 0
        val point = mantissa.indexOf('.')
        val whole = if (point >= 0) mantissa.substring(0, point) else mantissa
        val fraction = if (point >= 0) mantissa.substring(point + 1) else ""
        val digits = whole + fraction
        // Where the point sits in [digits] after the exponent is applied.
        val at = whole.length + exponent
        val out = when {
            at <= 0 -> "0." + "0".repeat(-at) + digits
            at >= digits.length -> digits + "0".repeat(at - digits.length) + ".0"
            else -> digits.substring(0, at) + "." + digits.substring(at)
        }
        val trimmed = out.substringBefore('.').trimStart('0').ifEmpty { "0" } + "." + out.substringAfter('.')
        return (if (negative && trimmed.any { it in '1'..'9' }) "-" else "") + trimmed
    }
}
