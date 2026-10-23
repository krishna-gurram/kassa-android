package dev.krishnagurram.kassa.domain

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
    const val TICKER_MAX = 10
    const val PASSWORD_MIN = 8

    /** Largest amount in minor units: 99,999,999.99. Eight whole digits keep every figure on one line. */
    const val AMOUNT_MAX = 9_999_999_999L
    const val AMOUNT_DIGITS = 8

    val DAYS = 1..31

    /** For a text field's onValueChange: no line breaks, never longer than [max]. */
    fun typed(raw: String, max: Int): String = raw.replace(Regex("[\\r\\n\\t]"), " ").take(max)

    /**
     * For an amount field's onValueChange: digits and one decimal point only, at most [AMOUNT_DIGITS] whole digits
     * and two decimals. Commas are dropped, so pasting "1,250.50" works.
     */
    fun typedAmount(raw: String): String {
        val kept = raw.filter { it.isDigit() || it == '.' }
        val dot = kept.indexOf('.')
        val whole = (if (dot < 0) kept else kept.substring(0, dot)).take(AMOUNT_DIGITS)
        return if (dot < 0) whole else whole + "." + kept.substring(dot + 1).filter(Char::isDigit).take(2)
    }

    /** Like [typedAmount], but keeps a leading minus: a bank balance can be overdrawn. */
    fun typedBalance(raw: String): String {
        val minus = raw.trimStart().startsWith("-") || raw.trimStart().startsWith("−")
        return (if (minus) "-" else "") + typedAmount(raw)
    }

    /** Digits only, at most [max] of them: last 4, days of the month. */
    fun typedDigits(raw: String, max: Int): String = raw.filter(Char::isDigit).take(max)

    /** For an emoji field: only the first character as you see it (one emoji, even one built from several codes). */
    fun typedEmoji(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val it = java.text.BreakIterator.getCharacterInstance().apply { setText(t) }
        return t.substring(0, it.next().coerceAtLeast(1))
    }

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
