package app.kassa.money.domain

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
        return try {
            parts.toBigDecimal().movePointRight(RATE_DECIMALS).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
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
     * java.text.BreakIterator splits joined emoji on older Java versions.
     */
    fun typedEmoji(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        var end = Character.charCount(t.codePointAt(0))
        val flag = isRegionalIndicator(t.codePointAt(0))
        if (flag && end < t.length && isRegionalIndicator(t.codePointAt(end))) end += Character.charCount(t.codePointAt(end))
        while (end < t.length) {
            val cp = t.codePointAt(end)
            end += when {
                // A joiner glues the next character on, as in 👨‍👩‍👧.
                cp == ZWJ && end + 1 < t.length -> 1 + Character.charCount(t.codePointAt(end + 1))
                cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 ||
                    Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || Character.getType(cp) == Character.ENCLOSING_MARK.toInt() -> Character.charCount(cp)
                else -> break
            }
        }
        return t.substring(0, end)
    }

    private const val ZWJ = 0x200D
    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

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
        Math.abs(cents) > AMOUNT_MAX -> "That's more than ${Money.format(AMOUNT_MAX, wholeDollars = true)}."
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
