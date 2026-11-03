package app.kassa.money.domain

/**
 * The number pad of the entry page. What is typed always keeps a point for its decimal mark, so it reads the same on every phone;
 * [display] shows it the way the phone writes numbers. [Money.parseCents] reads it back (it takes either mark).
 */
object AmountPad {

    /** One pad key applied to [current]: "del" removes the last character, "." adds the point, a digit is added. At most [InputRules.AMOUNT_DIGITS] whole digits and two decimals. */
    fun press(current: String, key: String): String = when (key) {
        "del" -> current.dropLast(1)
        "." -> if (current.contains('.')) current else current.ifEmpty { "0" } + "."
        else -> when {
            key.length != 1 || key[0] !in '0'..'9' -> current
            current.contains('.') && current.substringAfter('.').length >= 2 -> current
            !current.contains('.') && current.length >= InputRules.AMOUNT_DIGITS -> current
            current == "0" -> key
            else -> current + key
        }
    }

    /**
     * What the pad has typed, shown the way the phone writes numbers: "12345.6" reads "12,345.6" (12,345.6 in the US, 12.345,6 in
     * Germany, 12,345.6 with lakh grouping in India). Nothing typed shows "0".
     */
    fun display(typed: String): String {
        if (typed.isEmpty()) return "0"
        val whole = typed.substringBefore('.').ifEmpty { "0" }
        val grouped = NumberText.group(whole)
        return if (typed.contains('.')) grouped + NumberText.decimalMark() + typed.substringAfter('.') else grouped
    }

    /** Cents as pad input, which always keeps a point whatever the phone writes: 1250 is "12.50", 1200 is "12". */
    fun typedOf(cents: Long): String {
        val whole = cents / 100
        val fraction = cents % 100
        return if (fraction == 0L) whole.toString() else whole.toString() + "." + fraction.toString().padStart(2, '0')
    }

    /** What the pad has typed as cents; 0 for nothing typed. */
    fun cents(typed: String): Long = Money.parseCents(typed) ?: 0L
}
