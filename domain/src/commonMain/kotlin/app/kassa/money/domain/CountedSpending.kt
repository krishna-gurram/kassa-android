package app.kassa.money.domain

/**
 * The one rule for "Count the whole payment as spending" on a loan.
 *
 * A loan payment is stored as ordinary entries: the principal is a TRANSFER to the loan, the interest and escrow are
 * EXPENSEs. That never changes, so balances, net worth, card and loan figures and the loan engine read the real entries.
 * Every figure of spending (Overview, Insights, budgets, week, month, day and category pages, Activity totals) reads the
 * entries through [apply] instead: when a payment counts, its principal entry comes back as an EXPENSE in its principal
 * category, same id, account, date, amount and note, so it is summed like any other expense. Nothing is rewritten in
 * the database, so it also reaches payments recorded before the switch was turned on, and turning it off puts every
 * figure back.
 *
 * Income, balance corrections (ADJUST_IN, ADJUST_OUT) and every other transfer are never touched: only the entry a
 * `loan_payments` row names as its principal can change, and only a TRANSFER does.
 */
object CountedSpending {
    /** Whether [payment]'s principal counts as spending: the payment's own choice when it made one, else the loan's. */
    fun counts(payment: LoanPayment, terms: LoanTerms?): Boolean = payment.countAllAsSpending ?: terms?.countAllAsSpending ?: false

    /**
     * The category [payment]'s principal counts in: the payment's own, else the loan's principal category, else the same
     * as the interest (the category its interest entry went to, then the loan's interest category, then "Loan interest").
     * Only an expense category that still exists counts; null when none does, and the principal then stays out.
     */
    fun principalCategory(payment: LoanPayment, terms: LoanTerms?, interestEntry: Transaction?, categories: List<Category>): Long? {
        val byId = categories.associateBy { it.id }
        return listOf(
            payment.principalCategoryId, terms?.principalCategoryId, interestEntry?.categoryId, terms?.interestCategoryId,
            LoanCategories.interestCategory(categories)?.id,
        ).firstOrNull { id -> id != null && byId[id]?.kind == CategoryKind.EXPENSE }
    }

    /** The ids of the entries [apply] turns into spending: principal entries of the payments that count. */
    fun countedIds(payments: List<LoanPayment>, terms: Map<Long, LoanTerms>): Set<Long> =
        payments.filter { counts(it, terms[it.accountId]) }.mapNotNull { it.principalEntryId }.toSet()

    /**
     * [transactions] as spending sees them: each counted principal entry becomes an EXPENSE in its principal category.
     * Everything else is returned as it is, so with no payment counting the same list comes back.
     */
    fun apply(transactions: List<Transaction>, payments: List<LoanPayment>, terms: Map<Long, LoanTerms>, categories: List<Category>): List<Transaction> {
        val counted = payments.filter { it.principalEntryId != null && counts(it, terms[it.accountId]) }.associateBy { it.principalEntryId!! }
        if (counted.isEmpty()) return transactions
        val byId = transactions.associateBy { it.id }
        return transactions.map { t ->
            val payment = counted[t.id]
            if (payment == null || t.kind != TxKind.TRANSFER) t
            else {
                val interest = payment.interestEntryId?.let { byId[it] }
                val category = principalCategory(payment, terms[payment.accountId], interest, categories)
                if (category == null) t else t.copy(kind = TxKind.EXPENSE, categoryId = category, toAccountId = null)
            }
        }
    }
}
