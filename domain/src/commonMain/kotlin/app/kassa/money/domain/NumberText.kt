package app.kassa.money.domain

/**
 * How numbers are written: the decimal mark, grouping mark and digits of a language, and whether it groups the Indian way.
 * The app builds one from the phone's locale (`numberStyleOf`); the rules never ask the platform.
 */
data class NumberStyle(
    /** What really separates whole and fraction in formatted text: '.', ',' or the Arabic mark. */
    val decimal: Char,
    val grouping: Char,
    /** The character of digit zero ('0', or an Arabic-Indic or Bengali zero); the other nine follow it. */
    val zeroDigit: Char,
    /** Last three digits, then every two (India, Bangladesh, Nepal). */
    val lakh: Boolean,
    /** What goes before a negative number as formatted text. */
    val minus: String = "-",
) {
    companion object {
        val US = NumberStyle('.', ',', '0', false)

        private val LAKH_REGIONS = setOf("IN", "BD", "NP")

        /** True for the regions that group the Indian way (last three digits, then every two): India, Bangladesh, Nepal. */
        fun usesLakh(region: String): Boolean = region.uppercase() in LAKH_REGIONS

        /**
         * The region in a locale identifier as phones write it: "en_IN", "en-IN", "hi-Latn-IN", "en_IN@calendar=gregorian",
         * "en_IN.UTF-8". Empty when the identifier names a language only.
         */
        fun regionOf(identifier: String): String {
            val parts = identifier.substringBefore('@').substringBefore('.').split('-', '_')
            for (part in parts.drop(1)) {
                if (part.length == 2 && part.all { it in 'A'..'Z' || it in 'a'..'z' }) return part.uppercase()
            }
            return ""
        }

        /**
         * The style of a phone whose locale is [identifier]: the marks its platform reports for that locale, and lakh grouping
         * decided here (from the region) so every platform agrees. An empty mark keeps the US one.
         */
        fun forLocale(identifier: String, decimal: String, grouping: String, zeroDigit: String, minus: String): NumberStyle =
            NumberStyle(
                decimal = decimal.firstOrNull() ?: '.',
                grouping = grouping.firstOrNull() ?: ',',
                zeroDigit = zeroDigit.firstOrNull() ?: '0',
                lakh = usesLakh(regionOf(identifier)),
                minus = minus.ifEmpty { "-" },
            )
    }
}

/**
 * How numbers are written and read on this phone: the decimal mark and grouping of its language (1,234.50 in the US,
 * 1.234,50 in Germany, 1 234,50 in France, 12,34,567.50 in India), whatever the currency. Plain Kotlin with no platform
 * classes, so the same rules serve other platforms. Everything takes the phone's [Money.numberStyle] unless a test names one.
 * Decimals are an unscaled Long and a scale (12.34 is 1234 and 2), never a floating point.
 */
object NumberText {

    /** The mark a field shows between whole and fraction: '.' or ','. Any other mark a language has is typed as '.'. */
    fun decimalMark(style: NumberStyle = Money.numberStyle): Char = style.decimal.takeIf { it == '.' || it == ',' } ?: '.'

    /** The character that really separates whole and fraction in formatted text ('.', ',' or the Arabic mark), to find the cents in "1.234,50". */
    fun decimalSeparator(style: NumberStyle = Money.numberStyle): Char = style.decimal

    /**
     * A plain number for a field: [unscaled] times 10^-[scale], no grouping, no trailing zeros, the phone's decimal
     * mark ("1234,5" in Germany). Digits are always ASCII and a minus is always '-'.
     */
    fun toField(unscaled: Long, scale: Int, style: NumberStyle = Money.numberStyle): String {
        val (whole, fraction) = Decimals.split(Decimals.digitsOf(unscaled), scale)
        val trimmed = fraction.trimEnd('0')
        return (if (unscaled < 0) "-" else "") + whole + if (trimmed.isEmpty()) "" else decimalMark(style).toString() + trimmed
    }

    /**
     * [unscaled] times 10^-[scale] for reading, with exactly [fractionDigits] decimals (fewer when [trim] and they are zero)
     * and the phone's grouping and digits. Rounds half up, so a rate or an amount never shows a value it doesn't have.
     */
    fun decimal(unscaled: Long, scale: Int, fractionDigits: Int, style: NumberStyle = Money.numberStyle, trim: Boolean = false): String =
        decimalOf(unscaled < 0, Decimals.digitsOf(unscaled), scale, fractionDigits, style, trim)

    /** [decimal] for a magnitude given as digits, so the caller never has to negate a [Long.MIN_VALUE]. */
    internal fun decimalOf(negative: Boolean, digits: String, scale: Int, fractionDigits: Int, style: NumberStyle, trim: Boolean): String {
        val (rawWhole, rawFraction) = Decimals.split(digits, scale)
        val (whole, fraction) = Decimals.roundHalfUp(rawWhole, rawFraction, fractionDigits)
        val shown = if (trim) fraction.trimEnd('0') else fraction
        val grouped = groupDigits(localize(whole, style), style)
        return (if (negative) style.minus else "") + grouped + if (shown.isEmpty()) "" else style.decimal.toString() + localize(shown, style)
    }

    /** Whole digits grouped the way the phone groups them: "1234567" is "1,234,567" or, in India, "12,34,567". */
    fun group(digits: String, style: NumberStyle = Money.numberStyle): String {
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return digits
        if (style.lakh) return groupDigits(digits, style)
        return groupDigits(localize(Decimals.stripLeadingZeros(digits), style), style)
    }

    /** ASCII digits written with the style's own zero. */
    private fun localize(digits: String, style: NumberStyle): String =
        if (style.zeroDigit == '0') digits else digits.map { style.zeroDigit + (it - '0') }.joinToString("")

    private fun groupDigits(whole: String, style: NumberStyle): String =
        if (style.lakh) lakh(whole, style.grouping) else thousands(whole, style.grouping)

    private fun thousands(whole: String, separator: Char): String {
        if (whole.length <= 3) return whole
        val out = StringBuilder()
        for ((i, ch) in whole.withIndex()) {
            if (i > 0 && (whole.length - i) % 3 == 0) out.append(separator)
            out.append(ch)
        }
        return out.toString()
    }

    /** India, Bangladesh and Nepal group the last three digits, then every two. */
    private fun lakh(whole: String, separator: Char): String {
        if (whole.length <= 3) return whole
        val head = whole.substring(0, whole.length - 3)
        val groups = head.reversed().chunked(2).map { it.reversed() }.reversed()
        return groups.joinToString(separator.toString()) + separator + whole.substring(whole.length - 3)
    }

    /** A number read from text: [whole] digits, and [fraction] digits when a decimal mark was typed (empty for "12."). */
    class Parts(val negative: Boolean, val whole: String, val fraction: String?) {
        val hasDigits: Boolean get() = whole.isNotEmpty() || !fraction.isNullOrEmpty()

        /**
         * The number times 10^[scale] as a Long, rounded half up on the next digit (1.005 at scale 2 is 101), or null when
         * it does not fit one. Exact, with no floating point.
         */
        fun scaled(scale: Int): Long? = Decimals.scaled(negative, whole, fraction.orEmpty(), scale)
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
    fun parts(raw: String, grouping: Boolean = true, style: NumberStyle = Money.numberStyle): Parts? {
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
        val decimalKind = if (style.decimal == ',') ',' else '.'
        val groupKind = style.grouping.takeIf { it == '.' || it == ',' }
        val after = digits.length - at
        val isGrouping = grouping && kind != decimalKind && kind == groupKind && after == 3 && at in 1..3
        return if (isGrouping) Parts(scan.negative, digits, null) else Parts(scan.negative, digits.substring(0, at), digits.substring(at))
    }

    /** For a field's onValueChange: like [parts], but a muddled paste keeps the first mark as the decimal and drops the rest. */
    fun partsForField(raw: String, grouping: Boolean = true, style: NumberStyle = Money.numberStyle): Parts {
        parts(raw, grouping, style)?.let { return it }
        val scan = scan(raw)
        val first = scan.marks.first().first
        return Parts(scan.negative, scan.digits.substring(0, first), scan.digits.substring(first))
    }

    /** What a field holds after [parts]: the whole digits cut to [wholeMax], the phone's decimal mark, the fraction cut to [fractionMax]. */
    fun fieldText(p: Parts, wholeMax: Int, fractionMax: Int, style: NumberStyle = Money.numberStyle): String =
        p.whole.take(wholeMax) + (p.fraction?.let { decimalMark(style).toString() + it.take(fractionMax) } ?: "")

    private class Scan(val negative: Boolean, val digits: String, val marks: List<Pair<Int, Char>>)

    private fun scan(raw: String): Scan {
        val digits = StringBuilder()
        val marks = ArrayList<Pair<Int, Char>>()
        var negative = false
        for (ch in raw) {
            val d = ch.digitToIntOrNull() ?: -1
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
