package app.kassa.money.domain

/** What the person says changed on the loan. */
enum class ChangeKind(val label: String) {
    RATE("Rate"),
    PAYMENT("Payment"),
    TYPE("Interest type"),
    REFINANCE("Refinanced"),
}

/** The fields of the change-terms form that can have a problem. */
enum class ChangeField { RATE, PAYMENT, TERM, TYPE, DATE }

/** The loan as the change-terms screen sees it. [owed] is what is owed today (positive). */
data class LoanChangeContext(
    val terms: LoanTerms,
    val changes: List<LoanTermChange>,
    val payments: List<LoanPayment>,
    val owed: Long,
) {
    val cursor: LoanCursor get() = LoanCursor(owed.coerceAtLeast(0), terms.nextPaymentDay, LoanEngine.lastDay(terms, payments))
}

/** What a change does, live under the form. A null means it isn't known or the loan isn't paid off within a hundred years. */
data class ChangePreview(
    /** The payment from the change date on, and what it was on that date without the change. */
    val payment: Long,
    val paymentBefore: Long,
    val paymentsLeft: Int?,
    val payoffDay: Int?,
    val interestLeft: Long?,
    val interestBefore: Long?,
    /** The payment doesn't cover the interest, so the balance never falls; the biggest thing to tell the person. */
    val warning: String?,
)

/**
 * The change-terms form, as typed. A change has a date and applies from it: payments before it keep the split they had.
 * Rate changes ask what the lender did ([LenderKept]); a refinance is a new rate, term and payment from a date; a switch
 * of interest type (interest only turning into repayment, say) takes a payment or a term. All the working out is
 * [LoanEngine]'s, so the preview is what the schedule will really do. Dates are epoch days.
 */
data class LoanChangeForm(
    val kind: ChangeKind = ChangeKind.RATE,
    val day: Int,
    val rate: String = "",
    val payment: String = "",
    val termMonths: String = "",
    val type: InterestType? = null,
    val kept: LenderKept = LenderKept.PAYMENT,
) {
    private val rateValue: Long? get() = InputRules.parseRate(rate)
    private val paymentValue: Long? get() = Money.parseCents(payment)
    private val months: Int? get() = termMonths.toIntOrNull()

    private fun word(c: LoanChangeContext) = c.terms.interestWord.lowercase()

    /** The interest type in effect on the change date, before the change. */
    private fun typeBefore(c: LoanChangeContext): InterestType = c.terms.effective(c.changes, day).type

    /** Each field's problem; empty when the change can be saved. */
    fun problems(c: LoanChangeContext): Map<ChangeField, String> = buildMap {
        c.terms.startDay?.takeIf { day < it }?.let { put(ChangeField.DATE, "The change can't start before the loan did.") }
        when (kind) {
            ChangeKind.RATE -> InputRules.rateProblem(rateValue, "new ${word(c)} rate")?.let { put(ChangeField.RATE, it) }
            ChangeKind.PAYMENT ->
                InputRules.amountProblem(paymentValue, zeroAllowed = typeBefore(c) == InterestType.INTEREST_ONLY)?.let { put(ChangeField.PAYMENT, it) }
            ChangeKind.TYPE -> {
                val t = type
                when {
                    t == null -> put(ChangeField.TYPE, "Pick the new interest type.")
                    t == typeBefore(c) -> put(ChangeField.TYPE, "That is already how interest is charged.")
                    t == InterestType.FLAT && c.terms.originalAmount == null ->
                        put(ChangeField.TYPE, "A flat rate loan needs its original amount. Add it under Edit details first.")
                }
                if (t != null && t != InterestType.INTEREST_ONLY) {
                    if (payment.isBlank() && termMonths.isBlank()) put(ChangeField.PAYMENT, "Enter the payment, or a term in months.")
                }
                if (payment.isNotBlank()) InputRules.amountProblem(paymentValue)?.let { put(ChangeField.PAYMENT, it) }
                if (termMonths.isNotBlank()) InputRules.monthsProblem(months, "a term in months")?.let { put(ChangeField.TERM, it) }
            }
            ChangeKind.REFINANCE -> {
                InputRules.rateProblem(rateValue, "new ${word(c)} rate")?.let { put(ChangeField.RATE, it) }
                InputRules.monthsProblem(months, "a term in months")?.let { put(ChangeField.TERM, it) }
                if (payment.isNotBlank()) InputRules.amountProblem(paymentValue)?.let { put(ChangeField.PAYMENT, it) }
            }
        }
    }

    /** The payment Kassa works out for a new term when none was typed; null when the form can't say yet. */
    fun suggestedPayment(c: LoanChangeContext): Long? {
        val n = months ?: return null
        val t = type ?: typeBefore(c)
        val r = if (kind == ChangeKind.REFINANCE) rateValue else c.terms.effective(c.changes, day).rate
        val balance = LoanEngine.balanceOn(c.terms, c.changes, c.cursor, day)
        return r?.let { LoanEngine.suggestedPayment(t, balance, it, n).takeIf { p -> p > 0 } }
    }

    /** The change to save. Call only when [problems] is empty. */
    fun change(c: LoanChangeContext): LoanTermChange {
        val id = c.terms.accountId
        return when (kind) {
            ChangeKind.RATE -> LoanEngine.rateChange(c.terms, c.changes, c.cursor, day, checkNotNull(rateValue), kept).change
            ChangeKind.PAYMENT -> LoanTermChange(accountId = id, effectiveDay = day, payment = paymentValue)
            ChangeKind.TYPE -> LoanTermChange(
                accountId = id, effectiveDay = day, type = type, termMonths = months,
                payment = paymentValue ?: if (type == InterestType.INTEREST_ONLY) null else suggestedPayment(c),
            )
            ChangeKind.REFINANCE -> LoanTermChange(
                accountId = id, effectiveDay = day, rate = rateValue, termMonths = months, payment = paymentValue ?: suggestedPayment(c),
            )
        }
    }

    /** What the change does to the payment, the payoff month and the interest still to pay; null while the form has a problem. */
    fun preview(c: LoanChangeContext): ChangePreview? {
        if (problems(c).isNotEmpty()) return null
        // A new change is saved last, so it wins over one on the same day: the preview gives it the highest id too.
        val change = change(c).copy(id = Long.MAX_VALUE)
        val all = c.changes + change
        val before = LoanEngine.outlook(c.terms, c.changes, c.cursor)
        val after = LoanEngine.outlook(c.terms, all, c.cursor)
        val eff = c.terms.effective(all, day)
        val balance = LoanEngine.balanceOn(c.terms, all, c.cursor, day)
        val covers = LoanEngine.coversInterest(eff.type, eff.rate, balance, eff.payment, c.terms.flatBase(balance))
        val warning = if (covers || balance <= 0) null else
            "The payment doesn't cover the ${word(c)}. It needs to be more than ${Money.format(LoanEngine.interestDue(eff.type, eff.rate, balance, null, c.terms.flatBase(balance)))}."
        return ChangePreview(
            payment = eff.payment, paymentBefore = c.terms.effective(c.changes, day).payment,
            paymentsLeft = after.paymentsLeft, payoffDay = after.payoffDay,
            interestLeft = after.interestLeft, interestBefore = before.interestLeft, warning = warning,
        )
    }

    companion object {
        /** An empty form dated [today]. */
        fun starting(today: Int) = LoanChangeForm(day = today)
    }
}
