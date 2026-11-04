package app.kassa.money.domain

/**
 * Which entries belong to a recorded loan payment, and what Activity says about them: the small tag ("principal",
 * "interest", "escrow", "charge") and whether the entry is the principal of a payment that counts as spending (see
 * [CountedSpending]). The same answers as Android's `LoansState.isLoanEntry`, `tagFor` and `countsAsSpending`, over plain
 * lists so another app can ask them.
 */
class LoanEntryIndex(payments: List<LoanPayment>, private val terms: Map<Long, LoanTerms>) {

    private val byEntry: Map<Long, Pair<LoanPayment, LoanPart>> = buildMap {
        for (p in payments) {
            listOf(p.principalEntryId to LoanPart.PRINCIPAL, p.interestEntryId to LoanPart.INTEREST, p.otherEntryId to LoanPart.OTHER)
                .forEach { (id, part) -> if (id != null) put(id, p to part) }
        }
    }

    /** Whether an entry is one of a loan payment's entries, whatever its kind. */
    fun isLoanEntry(entryId: Long): Boolean = entryId in byEntry

    /** Whether an entry is the principal of a payment that counts as spending. */
    fun countsAsSpending(entryId: Long): Boolean =
        byEntry[entryId]?.let { (p, part) -> part == LoanPart.PRINCIPAL && CountedSpending.counts(p, terms[p.accountId]) } == true

    /** The id of the `loan_payments` row an entry belongs to (whatever its kind), or 0 when it belongs to none. */
    fun paymentIdFor(entryId: Long): Long = byEntry[entryId]?.first?.id ?: 0L

    /** The tag Activity shows on a loan payment's entry; null for any other entry. */
    fun tagFor(entryId: Long): String? =
        byEntry[entryId]?.let { (p, part) -> p.tag(part, terms[p.accountId]?.interestWord ?: "Interest") }
}
