package app.kassa.money.domain

/**
 * What saving a loan payment, deleting one and saving a loan's details have to check and write, in plain Kotlin, so Android's
 * `LoanRepository` and the iOS store follow ONE set of rules. Each function mirrors the repository method of the same name step
 * for step (the same checks in the same order, the same sentences): it is handed what the store holds ([Facts]) and says either
 * what is wrong or exactly what to write. The caller runs the write itself, in one transaction.
 */
object LoanPaymentPlanner {

    /** A loan payment as the payment sheet builds it, before it is saved. The parts have to add up to [amount]. */
    data class Draft(
        /** The id of the payment being edited; null for a new one. */
        val id: Long?,
        val accountId: Long,
        val fromId: Long?,
        /** The payment's date as an epoch day (for the schedule) and as a moment (for the entries). */
        val day: Int,
        val epochMillis: Long,
        val amount: Long,
        val principal: Long,
        val interest: Long,
        val escrow: Long,
        val charge: Long,
        /** The category for the interest part; null means the loan's own, then "Loan interest". */
        val categoryId: Long?,
        /** The category for the escrow and charge entry; null means the shared "Escrow" category. */
        val escrowCategoryId: Long?,
        val edited: Boolean,
        val estimate: Long,
        /** This payment only: null follows the loan's switch, true counts the principal as spending, false doesn't. */
        val countAllAsSpending: Boolean?,
        val principalCategoryId: Long?,
    )

    /**
     * What the store holds. [balance] is the loan account's balance as stored now (negative while owed), with the edited
     * payment's own entries still in it; [oldEntries] are those entries, so the plan can take them out first.
     */
    class Facts(
        val loan: Account?,
        val terms: LoanTerms?,
        val changes: List<LoanTermChange>,
        val accounts: List<Account>,
        val categories: List<Category>,
        val old: LoanPayment?,
        val oldEntries: List<Transaction>,
        val balance: Long,
    )

    /**
     * The answer: [problem] is the sentence to show (null when the payment can be saved), else what to write. A null
     * [interestCategoryId] means the "Loan interest" category has to be made or brought back first; a null [costCategoryId] with
     * [other] above zero means the same for "Escrow". [linkId] is the `loan_payments` row to update, 0 for a new one.
     */
    class Plan(
        val problem: String?,
        val principal: Long = 0,
        val interest: Long = 0,
        val other: Long = 0,
        val principalNote: String = "",
        val interestNote: String = "",
        val otherNote: String = "",
        val interestCategoryId: Long? = null,
        val costCategoryId: Long? = null,
        val scheduled: Boolean = false,
        val nextPaymentDay: Int = 0,
        val principalCategoryId: Long? = null,
        val linkId: Long = 0,
    )

    private fun refuse(text: String): Plan = Plan(problem = text)

    private fun note(text: String): String = InputRules.clean(text).take(InputRules.NOTE_MAX)

    /** `LoanRepository.recordPayment`: the checks, then the rows. */
    fun plan(d: Draft, f: Facts): Plan {
        val terms = f.terms ?: return refuse("Add this loan's terms first.")
        val loan = f.loan ?: return refuse("This loan isn't there any more.")
        InputRules.amountProblem(d.amount)?.let { return refuse(it) }
        if (d.principal < 0 || d.interest < 0 || d.escrow < 0 || d.charge < 0) return refuse("Parts of a payment can't be negative.")
        val parts = d.principal + d.interest + d.escrow + d.charge
        if (parts != d.amount) return refuse("The parts add up to ${Money.format(parts)}, not ${Money.format(d.amount)}.")
        val from = d.fromId ?: return refuse("Pick the account it's paid from.")
        if (from == d.accountId || f.accounts.none { it.id == from }) return refuse("Pick the account it's paid from.")
        val category = d.categoryId ?: terms.interestCategoryId ?: LoanCategories.interestCategory(f.categories)?.id
        if (category != null && f.categories.none { it.id == category }) {
            return refuse("Pick a category for the ${terms.interestWord.lowercase()}.")
        }
        // The principal's category only matters when this payment counts: its own choice, else the loan's.
        val counts = d.countAllAsSpending ?: terms.countAllAsSpending
        val principalCategory = d.principalCategoryId?.takeIf { counts }
        if (principalCategory != null && f.categories.none { it.id == principalCategory && it.kind == CategoryKind.EXPENSE }) {
            return refuse("Pick an expense category for the principal.")
        }
        val costs = d.escrow + d.charge
        val costCategory = if (costs > 0) (d.escrowCategoryId ?: LoanCategories.escrowCategory(f.categories)?.id) else null
        if (costs > 0 && costCategory != null && f.categories.none { it.id == costCategory }) {
            return refuse("Pick a category for the ${if (d.escrow > 0) "escrow" else "charge"}.")
        }

        // A new payment can't land on a closed loan or be paid from a closed account. Editing one keeps the accounts it already has.
        val old = f.old
        val before: Set<Long> = if (old == null) emptySet() else setOf(old.accountId) + f.oldEntries.mapNotNull { it.accountId }
        for (id in listOf(d.accountId, from).distinct()) {
            if (id in before) continue
            val account = f.accounts.firstOrNull { it.id == id }
            if (account != null && account.isClosed) return refuse(EntryRules.closedMessage(account.name))
        }

        // Take the payment being edited out first, so the balance and the next date are as they were before it.
        var next = terms.nextPaymentDay
        if (old != null && old.scheduled) next = LoanDates.addMonths(next, -1, terms.dueDay)
        val taken = f.oldEntries.sumOf { it.effectOn(d.accountId) }
        val owed = (-(f.balance - taken)).coerceAtLeast(0)
        if (d.principal > owed) return refuse("That's more than the ${Money.format(owed)} still owed.")

        val scheduled = d.amount >= terms.effective(f.changes, d.day).payment + terms.escrow
        if (scheduled) next = LoanDates.addMonths(next, 1, terms.dueDay)
        return Plan(
            problem = null,
            principal = d.principal,
            interest = d.interest,
            other = costs,
            principalNote = note(LoanNotes.principal(loan.name)),
            interestNote = note(LoanNotes.interest(loan.name, terms.interestWord)),
            otherNote = note(LoanNotes.other(loan.name, d.escrow, d.charge)),
            interestCategoryId = category,
            costCategoryId = costCategory,
            scheduled = scheduled,
            nextPaymentDay = next,
            principalCategoryId = principalCategory,
            linkId = old?.id ?: 0,
        )
    }

    /**
     * `LoanRepository.deletePayment`: the next payment date after [payment] is deleted, which moves back a month when the
     * payment had moved it on. Null when the date stays (the loan has no terms, or the payment never moved it).
     */
    fun nextDayAfterDelete(payment: LoanPayment, terms: LoanTerms?): Int? {
        if (terms == null || !payment.scheduled) return null
        return LoanDates.addMonths(terms.nextPaymentDay, -1, terms.dueDay)
    }
}

/** What saving a loan's details checks and writes (`LoanRepository.updateDetails`, with `AccountRepository.update`'s checks). */
object LoanDetailsPlanner {

    /** [problem] is the sentence to show, null when the details can be saved; else the cleaned [name] and the [terms] to store. */
    class Plan(
        val problem: String?,
        val name: String = "",
        val terms: LoanTerms? = null,
        /** The shared "Loan interest" and "Escrow" categories have to exist after this save. */
        val needsEscrowCategory: Boolean = false,
    )

    private fun refuse(text: String): Plan = Plan(problem = text)

    fun plan(form: LoanDetailsForm, account: Account?, current: LoanTerms?, accounts: List<Account>, categories: List<Category>, owed: Long): Plan {
        if (account == null || account.type != AccountType.LOAN) return refuse("Details belong to a loan or mortgage account.")
        if (current == null) return refuse("Add this loan's terms first.")
        form.problems(current, owed).values.firstOrNull()?.let { return refuse(it) }
        val terms = form.applyTo(current).copy(accountId = account.id)
        // AccountRepository.update: the name has to be free among the other accounts.
        val others = accounts.filter { it.id != account.id }.map { it.name }
        InputRules.nameProblem(form.name, "account", taken = { n -> others.any { it.equals(n, ignoreCase = true) } })?.let { return refuse(it) }
        InputRules.balanceProblem(account.openingBalance)?.let { return refuse(it) }
        // LoanRepository.saveTerms.
        terms.problem()?.let { return refuse(it) }
        terms.payFromId?.let { id ->
            if (id == terms.accountId || accounts.none { it.id == id }) return refuse("Pick another account to pay from.")
        }
        terms.interestCategoryId?.let { id -> if (categories.none { it.id == id }) return refuse("That category isn't there any more.") }
        terms.principalCategoryId?.let { id ->
            if (categories.none { it.id == id && it.kind == CategoryKind.EXPENSE }) return refuse("Pick an expense category for the principal.")
        }
        return Plan(problem = null, name = InputRules.clean(form.name), terms = terms, needsEscrowCategory = terms.escrow > 0)
    }
}
