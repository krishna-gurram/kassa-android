package app.kassa.money.domain

import kotlin.math.abs

/**
 * What may be typed into Kassa. Forms use these to stop bad input as it's typed and to explain problems;
 * repositories check them again before saving, so nothing invalid reaches the database by any path.
 *
 * Each `*Problem` function returns null when the value is fine, or a short message to show.
 */
object InputRules {
    /** Accounts, people, bills, account groups. */
    const val NAME_MAX = 40
    /** Categories and subcategories; they sit in tiles and chips. */
    const val CATEGORY_NAME_MAX = 30
    /** An entry's merchant or note. */
    const val NOTE_MAX = 120
    /** A feedback message: several lines allowed. */
    const val FEEDBACK_MAX = 2000
    const val TICKER_MAX = 10
    const val PASSWORD_MIN = 8

    /** Largest amount in minor units: 99,999,999.99. Eight whole digits keep every figure on one line. */
    const val AMOUNT_MAX = 9_999_999_999L
    const val AMOUNT_DIGITS = 8

    val DAYS = 1..31

    /** A yearly rate is stored in 1/10000 of a percent, so 6.125% is 61,250 and needs no floating point. */
    const val RATE_MAX = 1_000_000L
    const val RATE_WHOLE_DIGITS = 3
    const val RATE_DECIMALS = 4

    /** The longest loan term or count of payments left, in months: fifty years. */
    const val MONTHS_MAX = 600

    /** For a text field's onValueChange: no line breaks, never longer than [max]. */
    fun typed(raw: String, max: Int): String = raw.replace(Regex("[\\r\\n\\t]"), " ").take(max)

    /** Like [typed], but keeps line breaks, for longer text such as feedback. */
    fun typedLines(raw: String, max: Int): String = raw.replace('\t', ' ').take(max)

    /**
     * For an amount field's onValueChange: digits and one decimal mark only, at most [AMOUNT_DIGITS] whole digits and
     * two decimals. The mark is the phone's ("12,5" in Germany); a pasted "1,250.50" or "1.250,50" is read right
     * ([NumberText.parts]) and becomes "1250.50" or "1250,50".
     */
    fun typedAmount(raw: String): String = NumberText.fieldText(NumberText.partsForField(raw), AMOUNT_DIGITS, 2)

    /** For a rate field: digits and one decimal mark, at most three whole digits and four decimals ("6.125"). */
    fun typedRate(raw: String): String =
        NumberText.fieldText(NumberText.partsForField(raw, grouping = false), RATE_WHOLE_DIGITS, RATE_DECIMALS)

    /** "6.125" (or "6,125" where the phone says so) as 61,250. Null for anything that is not a number. */
    fun parseRate(text: String): Long? {
        val parts = NumberText.parts(text.trim(), grouping = false) ?: return null
        if (!parts.hasDigits) return null
        return parts.scaled(RATE_DECIMALS)
    }

    /** A yearly rate from 0% to 100%. [what] reads in the message: "Enter the interest rate." */
    fun rateProblem(rate: Long?, what: String = "interest rate"): String? = when {
        rate == null -> "Enter the $what."
        rate < 0 || rate > RATE_MAX -> "The $what has to be between 0% and 100%."
        else -> null
    }

    /** A number of months from 1 to [MONTHS_MAX]. */
    fun monthsProblem(months: Int?, what: String): String? =
        if (months == null || months !in 1..MONTHS_MAX) "Enter $what from 1 to $MONTHS_MAX." else null

    /** Like [typedAmount], but keeps a leading minus: a bank balance can be overdrawn. */
    fun typedBalance(raw: String): String {
        val parts = NumberText.partsForField(raw)
        return (if (parts.negative) "-" else "") + NumberText.fieldText(parts, AMOUNT_DIGITS, 2)
    }

    /** Digits only, at most [max] of them: last 4, days of the month. */
    fun typedDigits(raw: String, max: Int): String = raw.filter(Char::isDigit).take(max)

    /**
     * For an emoji field: only the first character as you see it, even when it's built from several codes
     * (a skin tone, a flag, or a family joined with zero-width joiners). Done by hand because
     * java.text.BreakIterator splits joined emoji on older Java versions, and in plain Kotlin so it is the same everywhere.
     */
    fun typedEmoji(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val firstCode = codePointAt(t, 0)
        var end = unitCount(firstCode)
        if (isRegionalIndicator(firstCode) && end < t.length && isRegionalIndicator(codePointAt(t, end))) end += unitCount(codePointAt(t, end))
        while (end < t.length) {
            val cp = codePointAt(t, end)
            end += when {
                // A joiner glues the next character on, as in 👨‍👩‍👧.
                cp == ZWJ && end + 1 < t.length -> 1 + unitCount(codePointAt(t, end + 1))
                cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || isMark(cp) -> unitCount(cp)
                else -> break
            }
        }
        return t.substring(0, end)
    }

    private const val ZWJ = 0x200D
    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** The code point starting at [i]: a surrogate pair is joined, a lone surrogate stands for itself. */
    private fun codePointAt(s: String, i: Int): Int {
        val high = s[i]
        if (high.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return high.code
    }

    /** How many UTF-16 units [cp] takes. */
    private fun unitCount(cp: Int) = if (cp >= 0x10000) 2 else 1

    /** A non-spacing or enclosing mark (general category Mn or Me), which sticks to the character before it. */
    private fun isMark(cp: Int): Boolean {
        if (cp < 0x10000) {
            val category = cp.toChar().category
            return category == CharCategory.NON_SPACING_MARK || category == CharCategory.ENCLOSING_MARK
        }
        // Beyond the first plane Kotlin has no category lookup: Mn and Me ranges (Unicode 15) as start, end pairs.
        var low = 0
        var high = SUPPLEMENTARY_MARKS.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) / 2
            when {
                cp < SUPPLEMENTARY_MARKS[2 * mid] -> high = mid - 1
                cp > SUPPLEMENTARY_MARKS[2 * mid + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private val SUPPLEMENTARY_MARKS = intArrayOf(
        0x101FD, 0x101FD, 0x102E0, 0x102E0, 0x10376, 0x1037A, 0x10A01, 0x10A03,
        0x10A05, 0x10A06, 0x10A0C, 0x10A0F, 0x10A38, 0x10A3A, 0x10A3F, 0x10A3F,
        0x10AE5, 0x10AE6, 0x10D24, 0x10D27, 0x10EAB, 0x10EAC, 0x10EFD, 0x10EFF,
        0x10F46, 0x10F50, 0x10F82, 0x10F85, 0x11001, 0x11001, 0x11038, 0x11046,
        0x11070, 0x11070, 0x11073, 0x11074, 0x1107F, 0x11081, 0x110B3, 0x110B6,
        0x110B9, 0x110BA, 0x110C2, 0x110C2, 0x11100, 0x11102, 0x11127, 0x1112B,
        0x1112D, 0x11134, 0x11173, 0x11173, 0x11180, 0x11181, 0x111B6, 0x111BE,
        0x111C9, 0x111CC, 0x111CF, 0x111CF, 0x1122F, 0x11231, 0x11234, 0x11234,
        0x11236, 0x11237, 0x1123E, 0x1123E, 0x11241, 0x11241, 0x112DF, 0x112DF,
        0x112E3, 0x112EA, 0x11300, 0x11301, 0x1133B, 0x1133C, 0x11340, 0x11340,
        0x11366, 0x1136C, 0x11370, 0x11374, 0x11438, 0x1143F, 0x11442, 0x11444,
        0x11446, 0x11446, 0x1145E, 0x1145E, 0x114B3, 0x114B8, 0x114BA, 0x114BA,
        0x114BF, 0x114C0, 0x114C2, 0x114C3, 0x115B2, 0x115B5, 0x115BC, 0x115BD,
        0x115BF, 0x115C0, 0x115DC, 0x115DD, 0x11633, 0x1163A, 0x1163D, 0x1163D,
        0x1163F, 0x11640, 0x116AB, 0x116AB, 0x116AD, 0x116AD, 0x116B0, 0x116B5,
        0x116B7, 0x116B7, 0x1171D, 0x1171F, 0x11722, 0x11725, 0x11727, 0x1172B,
        0x1182F, 0x11837, 0x11839, 0x1183A, 0x1193B, 0x1193C, 0x1193E, 0x1193E,
        0x11943, 0x11943, 0x119D4, 0x119D7, 0x119DA, 0x119DB, 0x119E0, 0x119E0,
        0x11A01, 0x11A0A, 0x11A33, 0x11A38, 0x11A3B, 0x11A3E, 0x11A47, 0x11A47,
        0x11A51, 0x11A56, 0x11A59, 0x11A5B, 0x11A8A, 0x11A96, 0x11A98, 0x11A99,
        0x11C30, 0x11C36, 0x11C38, 0x11C3D, 0x11C3F, 0x11C3F, 0x11C92, 0x11CA7,
        0x11CAA, 0x11CB0, 0x11CB2, 0x11CB3, 0x11CB5, 0x11CB6, 0x11D31, 0x11D36,
        0x11D3A, 0x11D3A, 0x11D3C, 0x11D3D, 0x11D3F, 0x11D45, 0x11D47, 0x11D47,
        0x11D90, 0x11D91, 0x11D95, 0x11D95, 0x11D97, 0x11D97, 0x11EF3, 0x11EF4,
        0x11F00, 0x11F01, 0x11F36, 0x11F3A, 0x11F40, 0x11F40, 0x11F42, 0x11F42,
        0x13440, 0x13440, 0x13447, 0x13455, 0x16AF0, 0x16AF4, 0x16B30, 0x16B36,
        0x16F4F, 0x16F4F, 0x16F8F, 0x16F92, 0x16FE4, 0x16FE4, 0x1BC9D, 0x1BC9E,
        0x1CF00, 0x1CF2D, 0x1CF30, 0x1CF46, 0x1D167, 0x1D169, 0x1D17B, 0x1D182,
        0x1D185, 0x1D18B, 0x1D1AA, 0x1D1AD, 0x1D242, 0x1D244, 0x1DA00, 0x1DA36,
        0x1DA3B, 0x1DA6C, 0x1DA75, 0x1DA75, 0x1DA84, 0x1DA84, 0x1DA9B, 0x1DA9F,
        0x1DAA1, 0x1DAAF, 0x1E000, 0x1E006, 0x1E008, 0x1E018, 0x1E01B, 0x1E021,
        0x1E023, 0x1E024, 0x1E026, 0x1E02A, 0x1E08F, 0x1E08F, 0x1E130, 0x1E136,
        0x1E2AE, 0x1E2AE, 0x1E2EC, 0x1E2EF, 0x1E4EC, 0x1E4EF, 0x1E8D0, 0x1E8D6,
        0x1E944, 0x1E94A, 0xE0100, 0xE01EF,
    )

    /** How a name is stored: trimmed, with runs of spaces collapsed and invisible characters removed. */
    fun clean(raw: String): String = raw.filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ")

    /**
     * A name that must be present, fit [max], and not repeat another (case-insensitive) when [taken] says so.
     * [what] reads in the message: "Give the account a name."
     */
    fun nameProblem(raw: String, what: String, max: Int = NAME_MAX, taken: (String) -> Boolean = { false }): String? {
        val name = clean(raw)
        return when {
            name.isEmpty() -> "Give the $what a name."
            name.length > max -> "Keep the name to $max characters."
            taken(name) -> "$name is already used."
            else -> null
        }
    }

    fun noteProblem(raw: String): String? = if (clean(raw).length > NOTE_MAX) "Keep the note to $NOTE_MAX characters." else null

    /** An amount that must be above zero (or zero when [zeroAllowed]) and within [AMOUNT_MAX]. */
    fun amountProblem(cents: Long?, zeroAllowed: Boolean = false): String? = when {
        cents == null -> "Enter an amount."
        cents < 0 -> "Enter an amount without a minus sign."
        cents == 0L && !zeroAllowed -> "Enter an amount."
        cents > AMOUNT_MAX -> "That's more than ${Money.format(AMOUNT_MAX, wholeDollars = true)}."
        else -> null
    }

    /** A balance can be negative (a card you owe on) but not beyond the largest amount either way. */
    fun balanceProblem(cents: Long?): String? = when {
        cents == null -> "Enter a balance, or 0."
        abs(cents) > AMOUNT_MAX -> "That's more than ${Money.format(AMOUNT_MAX, wholeDollars = true)}."
        else -> null
    }

    /** Optional last four digits of a card or account number. */
    fun last4Problem(raw: String): String? {
        val v = raw.trim()
        return if (v.isEmpty() || (v.length == 4 && v.all(Char::isDigit))) null else "Use exactly 4 digits, or leave it empty."
    }

    fun dayProblem(day: Int?, what: String): String? = if (day == null || day !in DAYS) "The $what has to be 1 to 31." else null

    fun tickerProblem(raw: String): String? {
        val t = raw.trim()
        return when {
            t.isEmpty() -> "Enter the ticker."
            t.length > TICKER_MAX || !t.all { it.isLetterOrDigit() || it == '.' || it == '-' } -> "Use the ticker as listed, like VTI or BRK.B."
            else -> null
        }
    }

    fun passwordProblem(password: String, repeat: String): String? = when {
        password.length < PASSWORD_MIN -> "Use at least $PASSWORD_MIN characters."
        password != repeat -> "The two passwords don't match."
        else -> null
    }
}
