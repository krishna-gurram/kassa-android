package dev.krishnagurram.kassa.domain

/**
 * Reads a bank or card notification and pulls out what a pending entry needs. Runs on the phone only.
 *
 * It is deliberately forgiving: when a bank changes its wording, the worst case is a pending card with just the
 * amount, which the user finishes by hand. It never guesses an amount from a balance line.
 */
data class ParsedAlert(
    val amount: Long,
    val merchant: String?,
    val last4: String?,
    /** A refund or credit to the account rather than a charge. */
    val isCredit: Boolean,
)

object AlertParser {
    // Any common currency mark or code before the number, so alerts read the same whichever currency the phone uses.
    // Grouping can be 1,234 or the Indian 1,23,456.
    private val amountRe = Regex("""(?:USD|GBP|EUR|AED|INR|CAD|AUD|SGD|SAR|QAR|Rs\.?|C\$|A\$|S\$|\$|£|€|₹)\s?\(?\s?([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{2})|[0-9]+\.[0-9]{2}|[0-9]+)\)?""")
    private val last4Re = Regex("""(?:ending(?: in)?|ending with|x|\*{2,}|\.\.\.)\s?#?\s?([0-9]{4})\b""", RegexOption.IGNORE_CASE)
    private val merchantRe = Regex(
        """\b(?:at|with|to|from)\s+([A-Za-z0-9][A-Za-z0-9 &'.#*/-]{1,40}?)(?=\s+(?:on|was|has|for|using|with|ending)\b|[.,;!]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val ignoreWords = listOf("available balance", "current balance", "balance is", "statement balance", "minimum payment", "payment due", "credit limit")
    private val creditWords = listOf("refund", "credit of", "credited", "deposit", "returned", "reversal")
    private val notMerchants = setOf("your", "card", "account", "the", "a", "an", "chase", "amex", "citi")

    fun parse(title: String?, text: String?): ParsedAlert? {
        val full = listOfNotNull(title, text).joinToString(". ").replace('\n', ' ').trim()
        if (full.isEmpty()) return null
        val lower = full.lowercase()
        // Statement reminders and balance updates are not purchases.
        if (ignoreWords.any { lower.contains(it) } && !lower.contains("transaction") && !lower.contains("purchase") && !lower.contains("charge")) return null

        val amountMatch = amountRe.find(full) ?: return null
        val amount = Money.parseCents(amountMatch.groupValues[1]) ?: return null
        if (amount <= 0) return null

        val merchant = merchantRe.findAll(full)
            .map { it.groupValues[1].trim().trimEnd('.', '-', '#', '*').trim() }
            .firstOrNull { m -> m.isNotEmpty() && m.lowercase() !in notMerchants && !m.lowercase().startsWith("your ") && !m.contains('$') }
            ?.let(::tidy)

        return ParsedAlert(
            amount = amount,
            merchant = merchant,
            last4 = last4Re.find(full)?.groupValues?.get(1),
            isCredit = creditWords.any { lower.contains(it) },
        )
    }

    /** "COSTCO WHSE #0123" becomes "Costco Whse". Store numbers and trailing codes are dropped. */
    fun tidy(raw: String): String {
        val words = raw.replace(Regex("""#\s?\d+|\*\S+|\b\d{3,}\b"""), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        // All-caps merchant names ("BEST BUY") read better in title case; mixed case was written on purpose.
        if (raw.any { it.isLowerCase() }) return words.joinToString(" ")
        return words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }.trim()
    }
}
