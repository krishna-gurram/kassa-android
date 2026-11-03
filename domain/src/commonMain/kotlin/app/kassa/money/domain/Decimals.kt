package app.kassa.money.domain

/**
 * Exact decimal arithmetic on digit strings and Longs, so number reading and writing needs no BigDecimal (plain Kotlin,
 * the same on every platform). A decimal here is a sign, a string of whole digits and a string of fraction digits.
 */
internal object Decimals {

    /** The digits of [value] without its sign (also right for [Long.MIN_VALUE], which has no positive twin). */
    fun digitsOf(value: Long): String = value.toString().removePrefix("-")

    /**
     * [digits] read as an integer times 10^-[scale], split into whole digits (no leading zeros, "0" when empty) and
     * exactly [scale] fraction digits. A negative [scale] adds zeros to the whole part.
     */
    fun split(digits: String, scale: Int): Pair<String, String> {
        if (scale <= 0) return stripLeadingZeros(digits + "0".repeat(-scale)) to ""
        val padded = digits.padStart(scale + 1, '0')
        return stripLeadingZeros(padded.substring(0, padded.length - scale)) to padded.substring(padded.length - scale)
    }

    fun stripLeadingZeros(whole: String): String = whole.trimStart('0').ifEmpty { "0" }

    /** [whole] and [fraction] cut to [fractionDigits] decimals, rounding half up (away from zero on the magnitude), carrying into the whole part. */
    fun roundHalfUp(whole: String, fraction: String, fractionDigits: Int): Pair<String, String> {
        if (fraction.length <= fractionDigits) return whole to fraction.padEnd(fractionDigits, '0')
        val kept = fraction.substring(0, fractionDigits)
        if (fraction[fractionDigits] < '5') return whole to kept
        val bumped = increment(whole + kept)
        val cut = bumped.length - fractionDigits
        return stripLeadingZeros(bumped.substring(0, cut)) to bumped.substring(cut)
    }

    /** [digits] plus one, as digits. */
    private fun increment(digits: String): String {
        val out = digits.toCharArray()
        var i = out.size - 1
        while (i >= 0) {
            if (out[i] == '9') { out[i] = '0'; i-- } else { out[i] = out[i] + 1; return out.concatToString() }
        }
        return "1" + out.concatToString()
    }

    /**
     * The decimal as a Long in units of 10^-[scale], rounded half up on the next digit, exactly like
     * BigDecimal.movePointRight(scale).setScale(0, HALF_UP).longValueExact(). Null when it does not fit a Long.
     * [whole] and [fraction] must be ASCII digits.
     */
    fun scaled(negative: Boolean, whole: String, fraction: String, scale: Int): Long? {
        // Built downwards (as Long.parseLong does) so Long.MIN_VALUE is reachable.
        var acc = 0L
        val limit = Long.MIN_VALUE / 10
        fun push(d: Int): Boolean {
            if (acc < limit) return false
            acc *= 10
            if (acc < Long.MIN_VALUE + d) return false
            acc -= d
            return true
        }
        for (ch in whole) if (!push(ch - '0')) return null
        for (i in 0 until scale) if (!push(if (i < fraction.length) fraction[i] - '0' else 0)) return null
        if (fraction.length > scale && fraction[scale] >= '5') {
            if (acc == Long.MIN_VALUE) return null
            acc -= 1
        }
        if (negative) return acc
        return if (acc == Long.MIN_VALUE) null else -acc
    }
}
