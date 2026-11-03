package app.kassa.money.domain

/**
 * Edit details on a loan: everything that has no date. Name, due day, pay from, interest category, whether the whole payment counts as spending and its principal category, escrow, fee, early
 * repayment charge, and the original amount and start date that only feed the progress bar. The rate, payment, term and
 * interest type are dated changes and live in [LoanChangeForm]. Plain Kotlin like [LoanForm], whose fields and
 * [LoanField] problems it shares, so a figure is checked the same way wherever it is typed.
 */
data class LoanDetailsForm(
    val name: String,
    val dueDay: String,
    val payFromId: Long?,
    val interestCategoryId: Long?,
    val countAllAsSpending: Boolean,
    val principalCategoryId: Long?,
    val escrow: String,
    val fee: String,
    val earlyCharge: String,
    val original: String,
    val startDay: Int?,
) {
    private fun optionalAmount(text: String): Long? = if (text.isBlank()) 0L else Money.parseCents(text)
    private fun optionalRate(text: String): Long? = if (text.isBlank()) 0L else InputRules.parseRate(text)

    /** Each field's problem; empty when the details can be saved. [owed] is what is owed now, [terms] the loan's current terms. */
    fun problems(terms: LoanTerms, owed: Long): Map<LoanField, String> = buildMap {
        InputRules.nameProblem(name, "loan")?.let { put(LoanField.NAME, it) }
        InputRules.dayProblem(dueDay.toIntOrNull(), "due day")?.let { put(LoanField.DUE_DAY, it) }
        InputRules.amountProblem(optionalAmount(escrow), zeroAllowed = true)?.let { put(LoanField.ESCROW, it) }
        InputRules.amountProblem(optionalAmount(fee), zeroAllowed = true)?.let { put(LoanField.FEE, it) }
        InputRules.rateProblem(optionalRate(earlyCharge), "early repayment charge")?.let { put(LoanField.EARLY_CHARGE, it) }
        if (original.isNotBlank()) {
            val o = Money.parseCents(original)
            val problem = InputRules.amountProblem(o)
                ?: if (o != null && o < owed) "The original amount can't be less than what is owed." else null
            if (problem != null) put(LoanField.ORIGINAL, problem)
        } else if (terms.type == InterestType.FLAT) {
            put(LoanField.ORIGINAL, "A flat rate loan needs its original amount.")
        }
    }

    /**
     * The terms with these details applied. Call only when [problems] is empty. A new due day moves the next payment
     * date to that day of the same month, so the schedule follows it.
     */
    fun applyTo(terms: LoanTerms): LoanTerms = terms.copy(
        dueDay = checkNotNull(dueDay.toIntOrNull()),
        nextPaymentDay = checkNotNull(dueDay.toIntOrNull()).let { due -> if (due == terms.dueDay) terms.nextPaymentDay else LoanDates.addMonths(terms.nextPaymentDay, 0, due) },
        payFromId = payFromId, interestCategoryId = interestCategoryId,
        countAllAsSpending = countAllAsSpending, principalCategoryId = principalCategoryId.takeIf { countAllAsSpending },
        escrow = optionalAmount(escrow) ?: 0, fee = optionalAmount(fee) ?: 0, earlyCharge = optionalRate(earlyCharge) ?: 0,
        originalAmount = Money.parseCents(original)?.takeIf { original.isNotBlank() }, startDay = startDay,
    )

    companion object {
        fun of(name: String, terms: LoanTerms) = LoanDetailsForm(
            name = name, dueDay = terms.dueDay.toString(), payFromId = terms.payFromId, interestCategoryId = terms.interestCategoryId,
            countAllAsSpending = terms.countAllAsSpending, principalCategoryId = terms.principalCategoryId,
            escrow = terms.escrow.takeIf { it > 0 }?.let { Money.toInput(it) } ?: "", fee = terms.fee.takeIf { it > 0 }?.let { Money.toInput(it) } ?: "",
            earlyCharge = terms.earlyCharge.takeIf { it > 0 }?.let { LoanRate.toInput(it) } ?: "",
            original = terms.originalAmount?.let { Money.toInput(it) } ?: "", startDay = terms.startDay,
        )
    }
}
