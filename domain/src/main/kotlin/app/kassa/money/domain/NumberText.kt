package app.kassa.money.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * How numbers are written and read on this phone: the decimal mark and grouping of its language (1,234.50 in the US,
 * 1.234,50 in Germany, 1 234,50 in France, 12,34,567.50 in India), whatever the currency. Plain Kotlin on java.text,
 * so the same rules can serve other platforms later. Everything takes the phone's [Money.locale] unless a test names one.
 */
object NumberText {

    /** The mark a field shows between whole and fraction: '.' or ','. Any other mark a language has is typed as '.'. */
    fun decimalMark(locale: Locale = Money.locale): Char =
        DecimalFormatSymbols.getInstance(locale).decimalSeparator.takeIf { it == '.' || it == ',' } ?: '.'

    /** The character that really separates whole and fraction in formatted text ('.', ',' or the Arabic mark), to find the cents in "1.234,50". */
    fun decimalSeparator(locale: Locale = Money.locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator

    /** A plain number for a field: no grouping, no trailing zeros, the phone's decimal mark ("1234,5" in Germany). */
    fun toField(value: BigDecimal, locale: Locale = Money.locale): String {
        val plain = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        return plain.replace('.', decimalMark(locale))
    }

    /**
     * [value] for reading, with exactly [fractionDigits] decimals (fewer when [trim] and they are zero) and the phone's
     * grouping and digits. Rounds half up, so a rate or an amount never shows a value it doesn't have.
     */
    fun decimal(value: BigDecimal, fractionDigits: Int, locale: Locale = Money.locale, trim: Boolean = false): String {
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = if (trim) 0 else fractionDigits
            maximumFractionDigits = fractionDigits
            roundingMode = java.math.RoundingMode.HALF_UP
        }
        if (!lakhGrouping(locale)) return nf.format(value)
        nf.isGroupingUsed = false
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val text = nf.format(value)
        val at = text.indexOf(symbols.decimalSeparator)
        val whole = if (at < 0) text else text.substring(0, at)
        return lakh(whole, symbols.groupingSeparator) + if (at < 0) "" else text.substring(at)
    }

    /** Whole digits grouped the way the phone groups them: "1234567" is "1,234,567" or, in India, "12,34,567". */
    fun group(digits: String, locale: Locale = Money.locale): String {
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return digits
        if (lakhGrouping(locale)) return lakh(digits, DecimalFormatSymbols.getInstance(locale).groupingSeparator)
        return NumberFormat.getIntegerInstance(locale).format(BigInteger(digits))
    }

    /** India, Bangladesh and Nepal group the last three digits, then every two. java.text can't, so it is done here. */
    private fun lakhGrouping(locale: Locale): Boolean = locale.country in setOf("IN", "BD", "NP")

    private fun lakh(whole: String, separator: Char): String {
        if (whole.length <= 3) return whole
        val head = whole.substring(0, whole.length - 3)
        val groups = head.reversed().chunked(2).map { it.reversed() }.reversed()
        return groups.joinToString(separator.toString()) + separator + whole.substring(whole.length - 3)
    }

    /** A number read from text: [whole] digits, and [fraction] digits when a decimal mark was typed (empty for "12."). */
    class Parts(val negative: Boolean, val whole: String, val fraction: String?) {
        val hasDigits: Boolean get() = whole.isNotEmpty() || !fraction.isNullOrEmpty()

        fun toBigDecimal(): BigDecimal {
            val value = BigDecimal(whole.ifEmpty { "0" } + if (fraction.isNullOrEmpty()) "" else ".$fraction")
            return if (negative) value.negate() else value
        }
    }

    /**
     * Reads typed or pasted text in any language's digits: "1,234.50", "1.234,50", "1 234,5", "₹1,00,000", "−12". Only
     * digits, the marks '.' and ',' (and the Arabic decimal mark) and a minus before the first digit count; anything else,
     * such as a symbol, a code or spaces, is ignored. Which mark is the decimal one:
     * - two kinds present: the last one ("1,234.50" and "1.234,50");
     * - one kind several times: grouping, so "1,00,000" is a hundred thousand (needs [grouping]);
     * - one mark once: the phone's decimal mark is a decimal; the phone's grouping mark followed by exactly three digits,
     *   after one to three, is grouping ("1.234" in Germany is 1234, "1,234" in the US too); any other mark is a decimal.
     * With [grouping] false (a rate, a share count) a lone mark is always a decimal. Returns null when the marks make no
     * sense, such as "1.2.3".
     */
    fun parts(raw: String, grouping: Boolean = true, locale: Locale = Money.locale): Parts? {
        val scan = scan(raw)
        val marks = scan.marks
        val digits = scan.digits
        if (marks.isEmpty()) return Parts(scan.negative, digits, null)
        val kinds = marks.map { it.second }.toSet()
        if (kinds.size == 2) {
            val last = marks.last()
            if (marks.dropLast(1).any { it.second == last.second }) return null
            return Parts(scan.negative, digits.substring(0, last.first), digits.substring(last.first))
        }
        if (marks.size > 1) {
            if (!grouping) return null
            val edges = marks.map { it.first } + digits.length
            val sizes = edges.zipWithNext { a, b -> b - a }
            val first = edges.first()
            if (first !in 1..3 || sizes.any { it !in 2..3 }) return null
            return Parts(scan.negative, digits, null)
        }
        val (at, kind) = marks.single()
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimalKind = if (symbols.decimalSeparator == ',') ',' else '.'
        val groupKind = symbols.groupingSeparator.takeIf { it == '.' || it == ',' }
        val after = digits.length - at
        val isGrouping = grouping && kind != decimalKind && kind == groupKind && after == 3 && at in 1..3
        return if (isGrouping) Parts(scan.negative, digits, null) else Parts(scan.negative, digits.substring(0, at), digits.substring(at))
    }

    /** For a field's onValueChange: like [parts], but a muddled paste keeps the first mark as the decimal and drops the rest. */
    fun partsForField(raw: String, grouping: Boolean = true, locale: Locale = Money.locale): Parts {
        parts(raw, grouping, locale)?.let { return it }
        val scan = scan(raw)
        val first = scan.marks.first().first
        return Parts(scan.negative, scan.digits.substring(0, first), scan.digits.substring(first))
    }

    /** What a field holds after [parts]: the whole digits cut to [wholeMax], the phone's decimal mark, the fraction cut to [fractionMax]. */
    fun fieldText(p: Parts, wholeMax: Int, fractionMax: Int, locale: Locale = Money.locale): String =
        p.whole.take(wholeMax) + (p.fraction?.let { decimalMark(locale).toString() + it.take(fractionMax) } ?: "")

    private class Scan(val negative: Boolean, val digits: String, val marks: List<Pair<Int, Char>>)

    private fun scan(raw: String): Scan {
        val digits = StringBuilder()
        val marks = ArrayList<Pair<Int, Char>>()
        var negative = false
        for (ch in raw) {
            val d = Character.digit(ch, 10)
            when {
                d >= 0 -> digits.append('0' + d)
                (ch == '-' || ch == '−') && digits.isEmpty() && marks.isEmpty() -> negative = true
                ch == '.' || ch == '٫' -> marks += digits.length to '.'
                ch == ',' -> marks += digits.length to ','
            }
        }
        return Scan(negative, digits.toString(), marks)
    }
}
