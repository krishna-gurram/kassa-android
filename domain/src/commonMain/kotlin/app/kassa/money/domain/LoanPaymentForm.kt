package app.kassa.money.domain

/** Which of the two parts the person typed last; the other one follows it. */
enum class SplitEdit { NONE, PRINCIPAL, INTEREST }

/** What a payment sheet needs to know about the loan. [balance] is what is owed before this payment (positive). */
data class LoanPaymentContext(
    val terms: LoanTerms,
    val changes: List<LoanTermChange>,
    val payments: List<LoanPayment>,
    val balance: Long,
    /** The payment being edited, whose own date doesn't count as the last one. */
    val editing: LoanPayment? = null,
) {
    fun lastDay(day: Int): Int = LoanEngine.lastDay(terms, payments, before = day, except = editing?.id ?: 0)
}

/** The numbers of a payment to save: they add up to [amount]. */
data class LoanPaymentParts(
    val amount: Long,
    val principal: Long,
    val interest: Long,
    val escrow: Long,
    val charge: Long,
    val edited: Boolean,
    /** The interest Kassa suggested. */
    val estimate: Long,
)

/** The fields of the payment sheet that can have a problem. */
enum class LoanPaymentField { AMOUNT, PRINCIPAL, FROM }

/**
 * The record-a-payment form, as typed: amount, date, where it's paid from, and its split into principal and interest.
 * Kassa suggests the split from the terms and the date; typing in one part makes the other follow, so the parts
 * always add up to the amount (less escrow and any early repayment charge, which Kassa works out). The date is an
 * epoch day. Nothing is preselected for [fromId] unless the loan names one.
 */
data class LoanPaymentForm(
    val amount: String = "",
    val day: Int,
    val principal: String = "",
    val interest: String = "",
    val edit: SplitEdit = SplitEdit.NONE,
    val fromId: Long? = null,
    /** The category for the interest part; null means the loan's own, which is "Loan interest" unless it names another. */
    val categoryId: Long? = null,
    /** The interest Kassa estimated, kept from a saved payment until the amount or date changes. Null means work it out. */
    val estimate: Long? = null,
    /** Where this payment's principal counts as spending; null means the loan's principal category. */
    val principalCategoryId: Long? = null,
    /** This payment only: null follows the loan's switch, true counts the principal as spending, false doesn't. */
    val countOverride: Boolean? = null,
) {
    private val amountValue: Long get() = Money.parseCents(amount) ?: 0

    /** What Kassa suggests for this amount and date. */
    fun suggestion(c: LoanPaymentContext): SplitSuggestion =
        LoanEngine.suggest(amountValue, day, c.balance, c.lastDay(day), c.terms, c.changes)

    /** What the principal and interest have to add up to: the amount less escrow and any early repayment charge. */
    fun available(c: LoanPaymentContext): Long = suggestion(c).let { amountValue - it.escrow - it.charge }.coerceAtLeast(0)

    private fun part(text: String): Long = Money.parseCents(text) ?: 0

    /** A new amount or date: the split is suggested again unless a part was typed, which then stays and the other follows. */
    private fun resplit(c: LoanPaymentContext): LoanPaymentForm {
        if (amountValue <= 0) return copy(principal = "", interest = "", estimate = null)
        val s = suggestion(c)
        val avail = amountValue - s.escrow - s.charge
        return when (edit) {
            SplitEdit.NONE -> copy(estimate = null, principal = Money.toInput(s.principal), interest = Money.toInput(s.interest))
            SplitEdit.INTEREST -> {
                val i = minOf(part(interest), avail)
                copy(estimate = null, principal = Money.toInput(avail - i), interest = if (i < part(interest)) Money.toInput(i) else interest)
            }
            SplitEdit.PRINCIPAL -> {
                val p = minOf(part(principal), avail)
                copy(estimate = null, interest = Money.toInput(avail - p), principal = if (p < part(principal)) Money.toInput(p) else principal)
            }
        }
    }

    fun withAmount(text: String, c: LoanPaymentContext): LoanPaymentForm = copy(amount = text).resplit(c)

    fun withDay(d: Int, c: LoanPaymentContext): LoanPaymentForm = copy(day = d).resplit(c)

    /** Principal typed: interest becomes what's left of the amount. */
    fun withPrincipal(text: String, c: LoanPaymentContext): LoanPaymentForm {
        val avail = available(c)
        val p = minOf(part(text), avail)
        return copy(principal = if (p < part(text)) Money.toInput(p) else text, interest = Money.toInput(avail - p), edit = SplitEdit.PRINCIPAL)
    }

    /** Interest typed: principal becomes what's left of the amount. */
    fun withInterest(text: String, c: LoanPaymentContext): LoanPaymentForm {
        val avail = available(c)
        val i = minOf(part(text), avail)
        return copy(interest = if (i < part(text)) Money.toInput(i) else text, principal = Money.toInput(avail - i), edit = SplitEdit.INTEREST)
    }

    /** The interest Kassa estimated for this payment. */
    fun estimate(c: LoanPaymentContext): Long = estimate ?: suggestion(c).interest

    /** Changed from Kassa's estimate by hand: typed, and not the same figure. */
    fun isEdited(c: LoanPaymentContext): Boolean = edit != SplitEdit.NONE && part(interest) != estimate(c)

    /** The line under the split: how it was estimated, or that it was edited. */
    fun hint(c: LoanPaymentContext): String {
        val word = c.terms.interestWord.lowercase()
        if (isEdited(c)) return "Edited by you. Kassa's estimate was ${Money.format(estimate(c))} $word."
        val s = suggestion(c)
        val eff = c.terms.effective(c.changes, day)
        val rate = LoanRate.display(eff.rate)
        return when (eff.type) {
            InterestType.FLAT -> "Estimated from ${Money.format(c.terms.flatBase(c.balance))} at $rate flat for one month."
            InterestType.DAILY -> "Estimated from ${Money.format(c.balance)} at $rate for ${s.days ?: 0} ${if (s.days == 1) "day" else "days"}."
            else -> "Estimated from ${Money.format(c.balance)} at $rate for one month."
        }
    }

    /** The scheduled payment on this date with escrow, which is what "Scheduled payment" shows. */
    fun scheduled(c: LoanPaymentContext): Long = LoanEngine.amountDue(c.terms, c.changes, c.balance, c.lastDay(day), day)

    /** Whether this payment's principal counts as spending: its own choice, else the loan's. */
    fun counts(c: LoanPaymentContext): Boolean = countOverride ?: c.terms.countAllAsSpending

    /** The switch on the sheet: it only overrides this payment, so choosing what the loan already does clears the override. */
    fun withCounts(on: Boolean, c: LoanPaymentContext): LoanPaymentForm = copy(countOverride = on.takeIf { it != c.terms.countAllAsSpending })

    /** What this payment adds to spending: the interest, escrow and charge, and the principal too when it counts. */
    fun countedSpending(c: LoanPaymentContext): Long {
        val p = parts(c)
        return p.interest + p.escrow + p.charge + if (counts(c)) p.principal else 0
    }

    /** Balance after this payment, from the principal as it stands. */
    fun balanceAfter(c: LoanPaymentContext): Long = c.balance - part(principal)

    /** Each field's problem; empty when the payment can be recorded. */
    fun problems(c: LoanPaymentContext): Map<LoanPaymentField, String> = buildMap {
        InputRules.amountProblem(amountValue.takeIf { amount.isNotBlank() })?.let { put(LoanPaymentField.AMOUNT, it) }
        if (fromId == null) put(LoanPaymentField.FROM, "Pick the account it's paid from.")
        if (LoanPaymentField.AMOUNT !in this) {
            val s = suggestion(c)
            if (part(principal) > c.balance) put(LoanPaymentField.PRINCIPAL, "That's more than the ${Money.format(c.balance)} still owed.")
            else if (edit == SplitEdit.NONE && s.leftover > 0) put(LoanPaymentField.AMOUNT, "That's ${Money.format(s.leftover)} more than the loan needs.")
            else if (amountValue < s.escrow + s.charge) put(LoanPaymentField.AMOUNT, "That doesn't cover the escrow and charges.")
        }
    }

    /** The numbers to save. Call only when [problems] is empty. */
    fun parts(c: LoanPaymentContext): LoanPaymentParts {
        val s = suggestion(c)
        return LoanPaymentParts(
            amount = amountValue, principal = part(principal), interest = part(interest),
            escrow = s.escrow, charge = s.charge, edited = isEdited(c), estimate = estimate(c),
        )
    }

    companion object {
        /** An empty form dated [day], paid from [fromId] when the loan names an account. */
        fun starting(day: Int, fromId: Long?) = LoanPaymentForm(day = day, fromId = fromId)

        /**
         * The form for a payment already recorded, with the parts as they were saved. An interest part that was
         * changed by hand stays flagged, so the hint can say what Kassa estimated.
         */
        fun of(p: LoanPayment, principal: Long, interest: Long, fromId: Long?, categoryId: Long?) = LoanPaymentForm(
            principalCategoryId = p.principalCategoryId, countOverride = p.countAllAsSpending,
            amount = Money.toInput(p.amount), day = p.day, principal = Money.toInput(principal), interest = Money.toInput(interest),
            edit = if (p.edited) SplitEdit.INTEREST else SplitEdit.NONE, fromId = fromId, categoryId = categoryId, estimate = p.estimate,
        )
    }
}
