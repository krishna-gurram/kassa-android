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
    /** What [payment] means. The escrow is the loan's own (a change has none of its own), so it only matters when the loan has escrow. */
    val paymentBasis: PaymentBasis = PaymentBasis.PRINCIPAL_AND_INTEREST,
) {
    private val rateValue: Long? get() = InputRules.parseRate(rate)
    private val typedTotal: Long? get() = Money.parseCents(payment)
    private val months: Int? get() = termMonths.toIntOrNull()

    private fun includesEscrow(c: LoanChangeContext): Boolean = paymentBasis == PaymentBasis.INCLUDES_ESCROW && c.terms.escrow > 0

    /** The loan's own payment (principal and interest): the typed amount, less the loan's escrow when it includes it. */
    private fun paymentValue(c: LoanChangeContext): Long? = typedTotal?.let { if (includesEscrow(c)) it - c.terms.escrow else it }

    /** The typed total split into principal and interest and the loan's escrow, or null unless it includes escrow and is valid. */
    fun paymentSplit(c: LoanChangeContext): PaymentSplit? {
        if (!includesEscrow(c)) return null
        val total = typedTotal ?: return null
        if (InputRules.amountProblem(total) != null || total <= c.terms.escrow) return null
        return PaymentSplit(total - c.terms.escrow, c.terms.escrow)
    }

    private fun word(c: LoanChangeContext) = c.terms.interestWord.lowercase()

    /** The interest type in effect on the change date, before the change. */
    private fun typeBefore(c: LoanChangeContext): InterestType = c.terms.effective(c.changes, day).type

    /** Each field's problem; empty when the change can be saved. */
    fun problems(c: LoanChangeContext): Map<ChangeField, String> = buildMap {
        c.terms.startDay?.takeIf { day < it }?.let { put(ChangeField.DATE, "The change can't start before the loan did.") }
        when (kind) {
            ChangeKind.RATE -> InputRules.rateProblem(rateValue, "new ${word(c)} rate")?.let { put(ChangeField.RATE, it) }
            ChangeKind.PAYMENT ->
                InputRules.amountProblem(typedTotal, zeroAllowed = typeBefore(c) == InterestType.INTEREST_ONLY)?.let { put(ChangeField.PAYMENT, it) }
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
                if (payment.isNotBlank()) InputRules.amountProblem(typedTotal)?.let { put(ChangeField.PAYMENT, it) }
                if (termMonths.isNotBlank()) InputRules.monthsProblem(months, "a term in months")?.let { put(ChangeField.TERM, it) }
            }
            ChangeKind.REFINANCE -> {
                InputRules.rateProblem(rateValue, "new ${word(c)} rate")?.let { put(ChangeField.RATE, it) }
                InputRules.monthsProblem(months, "a term in months")?.let { put(ChangeField.TERM, it) }
                if (payment.isNotBlank()) InputRules.amountProblem(typedTotal)?.let { put(ChangeField.PAYMENT, it) }
            }
        }
        // A total with escrow in it has to leave something for the loan itself (interest only may be left with nothing).
        val total = typedTotal
        if (ChangeField.PAYMENT !in this && total != null && includesEscrow(c) && kind != ChangeKind.RATE) {
            val floor = if (kind == ChangeKind.PAYMENT && typeBefore(c) == InterestType.INTEREST_ONLY) c.terms.escrow else c.terms.escrow + 1
            if (total < floor) put(ChangeField.PAYMENT, "The total payment has to be more than the escrow.")
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
            ChangeKind.PAYMENT -> LoanTermChange(accountId = id, effectiveDay = day, payment = paymentValue(c))
            ChangeKind.TYPE -> LoanTermChange(
                accountId = id, effectiveDay = day, type = type, termMonths = months,
                payment = paymentValue(c) ?: if (type == InterestType.INTEREST_ONLY) null else suggestedPayment(c),
            )
            ChangeKind.REFINANCE -> LoanTermChange(
                accountId = id, effectiveDay = day, rate = rateValue, termMonths = months, payment = paymentValue(c) ?: suggestedPayment(c),
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
