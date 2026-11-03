package app.kassa.money.domain

/** Starting a loan from scratch, or adding one that is already under way. */
enum class LoanStage { NEW, PARTWAY }

/** The interest chips on the setup form. Islamic finance is one chip with a contract pick under it. */
enum class LoanKind(val label: String) {
    REDUCING("Monthly on balance"),
    DAILY("Daily on balance"),
    FLAT("Flat rate"),
    INTEREST_ONLY("Interest only"),
    ISLAMIC("Islamic (profit)"),
}

/** Murabaha behaves as a flat rate, Ijara as a reducing balance. */
enum class IslamicContract(val label: String, val type: InterestType) {
    MURABAHA("Murabaha (fixed profit)", InterestType.FLAT),
    IJARA("Ijara (rent on balance)", InterestType.REDUCING),
}

/**
 * What the typed payment amount means. [PRINCIPAL_AND_INTEREST] is the loan's own payment with escrow on top;
 * [INCLUDES_ESCROW] is the total the person pays each month, so the loan's payment is that total less the escrow.
 * Nothing is chosen for the person: the first is the default.
 */
enum class PaymentBasis(val label: String) {
    PRINCIPAL_AND_INTEREST("Principal and interest"),
    INCLUDES_ESCROW("Total including escrow"),
}

/** A typed total split the way it is stored: the loan's payment and the escrow on top of it. */
data class PaymentSplit(val principalAndInterest: Long, val escrow: Long)

/** The fields of the loan form that can have a problem. */
enum class LoanField { NAME, AMOUNT, RATE, TERM, BALANCE, PAYMENT, PAYMENTS_LEFT, ORIGINAL, DUE_DAY, ESCROW, FEE, EARLY_CHARGE }

/** What the live calculation under the form shows. Null fields are not worked out yet or not known. */
data class LoanPreview(
    val payment: Long?,
    val totalInterest: Long?,
    val paymentsLeft: Int?,
    val payoffDay: Int?,
    /** Interest still to pay, from today's balance, for a loan already under way. */
    val interestLeft: Long?,
    /** For a flat rate: the rate on the reducing balance that costs the same. */
    val equivalentRate: Long?,
    /** For interest only: what is still owed at the end. */
    val owedAtEnd: Long?,
    /** The payment doesn't cover the interest; the biggest thing to tell the person. */
    val warning: String?,
)

/**
 * The loan setup form, as typed. The Add account sheet and "Add terms" on an existing loan keep one of these, so
 * a loan is checked and built the same way wherever it is entered. Dates are epoch days (see [LoanDates]).
 *
 * Two ways in: [LoanStage.NEW] takes the amount, rate, term and first payment and works out the payment (type your own
 * to overwrite it); [LoanStage.PARTWAY] takes today's balance, rate, payment and next payment date, and the original
 * amount and start date only for a progress bar. A flat rate loan under way also needs its payments left (or its
 * original amount), since the interest in each payment depends on the amount it started with.
 */
data class LoanForm(
    val stage: LoanStage = LoanStage.NEW,
    /** An existing loan account can only be added as already under way. */
    val stageLocked: Boolean = false,
    val kind: LoanKind = LoanKind.REDUCING,
    val contract: IslamicContract = IslamicContract.MURABAHA,
    val amount: String = "",
    val rate: String = "",
    val termMonths: String = "",
    val firstDay: Int,
    val balance: String = "",
    /** New loans: leave empty for Kassa's payment. Loans under way: the payment. */
    val payment: String = "",
    val nextDay: Int,
    val paymentsLeft: String = "",
    val original: String = "",
    val startDay: Int? = null,
    /** Empty means the day of the month of the first or next payment. */
    val dueDay: String = "",
    val payFromId: Long? = null,
    val escrow: String = "",
    val fee: String = "",
    val earlyCharge: String = "",
    val interestCategoryId: Long? = null,
    /** Spending also counts each payment's principal (see [CountedSpending]). Off unless chosen. */
    val countAllAsSpending: Boolean = false,
    /** Where the principal counts when [countAllAsSpending]; null means the same category as the interest. */
    val principalCategoryId: Long? = null,
    /** What [payment] means; only matters while an escrow amount is typed. */
    val paymentBasis: PaymentBasis = PaymentBasis.PRINCIPAL_AND_INTEREST,
) {
    val type: InterestType
        get() = when (kind) {
            LoanKind.REDUCING -> InterestType.REDUCING
            LoanKind.DAILY -> InterestType.DAILY
            LoanKind.FLAT -> InterestType.FLAT
            LoanKind.INTEREST_ONLY -> InterestType.INTEREST_ONLY
            LoanKind.ISLAMIC -> contract.type
        }

    val profit: Boolean get() = kind == LoanKind.ISLAMIC

    /** "Profit" or "Interest", for labels. */
    val word: String get() = if (profit) "Profit" else "Interest"

    private val rateValue: Long? get() = InputRules.parseRate(rate)
    private val newAmount: Long? get() = Money.parseCents(amount)
    private val balanceValue: Long? get() = Money.parseCents(balance)
    private val months: Int? get() = termMonths.toIntOrNull()
    private val left: Int? get() = paymentsLeft.toIntOrNull()
    private fun optional(text: String): Long? = if (text.isBlank()) 0L else Money.parseCents(text)
    private fun optionalRate(text: String): Long? = if (text.isBlank()) 0L else InputRules.parseRate(text)

    private val escrowCents: Long get() = optional(escrow)?.takeIf { it > 0 } ?: 0L

    /** The payment is a total with escrow in it. With no escrow typed the choice has no effect. */
    private val includesEscrow: Boolean get() = paymentBasis == PaymentBasis.INCLUDES_ESCROW && escrowCents > 0

    /** The loan's own payment (principal and interest) from what was typed: the total less escrow when it includes it. */
    private val typedPayment: Long? get() = Money.parseCents(payment)?.let { if (includesEscrow) it - escrowCents else it }

    /** The typed total split into principal and interest and escrow, or null unless the payment includes escrow and is valid. */
    fun paymentSplit(): PaymentSplit? {
        if (!includesEscrow) return null
        val total = Money.parseCents(payment) ?: return null
        if (InputRules.amountProblem(total) != null || total <= escrowCents) return null
        return PaymentSplit(total - escrowCents, escrowCents)
    }

    /** The amount owed once the loan is added: what was borrowed, or today's balance. Null until it is valid. */
    val owed: Long? get() = if (stage == LoanStage.NEW) newAmount else balanceValue

    private val anchorDay: Int get() = LoanDates.dayOfMonth(if (stage == LoanStage.NEW) firstDay else nextDay)
    private val due: Int get() = dueDay.toIntOrNull() ?: anchorDay

    /** Each field's problem; empty when the form can be saved. */
    fun problems(): Map<LoanField, String> = buildMap {
        val sharedRate = InputRules.rateProblem(rateValue, "${word.lowercase()} rate")
        if (stage == LoanStage.NEW) {
            InputRules.amountProblem(newAmount)?.let { put(LoanField.AMOUNT, it) }
            InputRules.monthsProblem(months, "a term in months")?.let { put(LoanField.TERM, it) }
            if (payment.isNotBlank()) InputRules.amountProblem(Money.parseCents(payment))?.let { put(LoanField.PAYMENT, it) }
        } else {
            InputRules.amountProblem(balanceValue)?.let { put(LoanField.BALANCE, it) }
            // Interest only can leave the payment out: it is the interest.
            if (payment.isNotBlank() || type != InterestType.INTEREST_ONLY) InputRules.amountProblem(Money.parseCents(payment))?.let { put(LoanField.PAYMENT, it) }
            if (original.isNotBlank()) {
                val o = Money.parseCents(original)
                val b = balanceValue
                val problem = InputRules.amountProblem(o)
                    ?: if (o != null && b != null && o < b) "The original amount can't be less than what is owed." else null
                if (problem != null) put(LoanField.ORIGINAL, problem)
            }
            if (type == InterestType.FLAT && original.isBlank()) {
                InputRules.monthsProblem(left, "the payments left")?.let { put(LoanField.PAYMENTS_LEFT, it) }
            }
        }
        if (LoanField.PAYMENT !in this && includesEscrow) {
            val total = Money.parseCents(payment)
            if (total != null && total <= escrowCents) put(LoanField.PAYMENT, "The total payment has to be more than the escrow.")
        }
        sharedRate?.let { put(LoanField.RATE, it) }
        if (dueDay.isNotBlank()) InputRules.dayProblem(dueDay.toIntOrNull(), "due day")?.let { put(LoanField.DUE_DAY, it) }
        InputRules.amountProblem(optional(escrow), zeroAllowed = true)?.let { put(LoanField.ESCROW, it) }
        InputRules.amountProblem(optional(fee), zeroAllowed = true)?.let { put(LoanField.FEE, it) }
        InputRules.rateProblem(optionalRate(earlyCharge), "early repayment charge")?.let { put(LoanField.EARLY_CHARGE, it) }
        // A partway flat loan's payment has to leave something for principal.
        if (LoanField.PAYMENT !in this && LoanField.PAYMENTS_LEFT !in this && LoanField.BALANCE !in this && LoanField.RATE !in this && stage == LoanStage.PARTWAY && type == InterestType.FLAT && original.isBlank()) {
            if (derivedFlatBase() == null) put(LoanField.PAYMENT, "The payment is too small for that many payments left.")
        }
    }

    /** The original amount of a flat loan under way, worked out from what is left when it wasn't typed. */
    private fun derivedFlatBase(): Long? {
        val b = balanceValue ?: return null
        val p = typedPayment ?: return null
        val n = left ?: return null
        val r = rateValue ?: return null
        return LoanEngine.flatBaseFrom(b, p, r, n)
    }

    /**
     * The terms to save for [accountId]: call only when [problems] is empty. The payment is Kassa's own unless one
     * was typed. The first or next payment date is stored as the next payment; the start date stays empty unless
     * one was given.
     */
    fun toTerms(accountId: Long): LoanTerms {
        val rate = checkNotNull(rateValue) { "toTerms called with an invalid rate" }
        val shared = LoanTerms(
            accountId = accountId, type = type, profit = profit, rate = rate, payment = 0, dueDay = due, nextPaymentDay = 0,
            escrow = optional(escrow) ?: 0L, fee = optional(fee) ?: 0L, earlyCharge = optionalRate(earlyCharge) ?: 0L,
            interestCategoryId = interestCategoryId, payFromId = payFromId,
            countAllAsSpending = countAllAsSpending, principalCategoryId = principalCategoryId.takeIf { countAllAsSpending },
        )
        if (stage == LoanStage.NEW) {
            val amount = checkNotNull(newAmount)
            val n = checkNotNull(months)
            return shared.copy(
                payment = typedPayment ?: LoanEngine.suggestedPayment(type, amount, rate, n),
                termMonths = n, originalAmount = amount, nextPaymentDay = firstDay,
            )
        }
        val owed = checkNotNull(balanceValue)
        val typed = typedPayment
        val originalAmount = Money.parseCents(original)?.takeIf { original.isNotBlank() }
            ?: if (type == InterestType.FLAT) derivedFlatBase() else null
        return shared.copy(
            payment = typed ?: LoanEngine.monthlyInterest(owed, rate),
            originalAmount = originalAmount, startDay = startDay, nextPaymentDay = nextDay,
        )
    }

    /**
     * The calculation shown under the form, or null while the figures it needs aren't valid yet. A loan under way is
     * forecast from its next payment date.
     */
    fun preview(): LoanPreview? {
        val problems = problems()
        val needed = if (stage == LoanStage.NEW) listOf(LoanField.AMOUNT, LoanField.RATE, LoanField.TERM, LoanField.PAYMENT, LoanField.ESCROW)
        else listOf(LoanField.BALANCE, LoanField.RATE, LoanField.PAYMENT, LoanField.PAYMENTS_LEFT, LoanField.ORIGINAL, LoanField.ESCROW)
        if (needed.any { it in problems }) return null
        val terms = toTerms(0)
        val owed = checkNotNull(owed)
        val cursor = LoanCursor(owed, terms.nextPaymentDay, terms.startDay ?: LoanDates.addMonths(terms.nextPaymentDay, -1, terms.dueDay))
        val outlook = LoanEngine.outlook(terms, emptyList(), cursor)
        val covers = LoanEngine.coversInterest(terms.type, terms.rate, owed, terms.payment, terms.flatBase(owed))
        val warning = if (covers) null else
            "The payment doesn't cover the ${word.lowercase()}. It needs to be more than ${Money.format(LoanEngine.interestDue(terms.type, terms.rate, owed, null, terms.flatBase(owed)))}."
        val fresh = stage == LoanStage.NEW
        return LoanPreview(
            payment = terms.payment.takeIf { fresh },
            totalInterest = if (fresh && type != InterestType.INTEREST_ONLY) outlook.interestLeft else null,
            paymentsLeft = outlook.paymentsLeft.takeIf { !fresh },
            payoffDay = outlook.payoffDay,
            interestLeft = outlook.interestLeft.takeIf { !fresh },
            equivalentRate = if (type == InterestType.FLAT && fresh) LoanEngine.equivalentReducingRate(owed, terms.payment, checkNotNull(months)) else null,
            owedAtEnd = owed.takeIf { fresh && type == InterestType.INTEREST_ONLY },
            warning = warning,
        )
    }

    /**
     * The loan account's starting balance after this form: its balance today becomes [owed], so the account's own
     * entries stay as they are, the same way editing an account's balance works.
     */
    fun openingBalance(base: Account, currentBalance: Long): Long =
        base.openingBalance + (-checkNotNull(owed) - currentBalance)

    companion object {
        /** The form for a new loan on [today]: the first payment a month away. */
        fun starting(today: Int, payFromId: Long? = null) = LoanForm(
            firstDay = LoanDates.addMonths(today, 1), nextDay = today, payFromId = payFromId,
        )

        /** The form for adding terms to an existing loan account that owes [owedToday] now: already under way. */
        fun forAccount(owedToday: Long, today: Int, payFromId: Long? = null) = LoanForm(
            stage = LoanStage.PARTWAY, stageLocked = true,
            balance = if (owedToday > 0) Money.toInput(owedToday) else "",
            firstDay = LoanDates.addMonths(today, 1), nextDay = today, payFromId = payFromId,
        )
    }
}

/** The two categories loans make for themselves, "Loan interest" and "Escrow". Any expense category can stand in for either. */
object LoanCategories {
    /** The "Loan interest" category: the top-level expense category with that name, if it exists and isn't archived. */
    fun interestCategory(categories: List<Category>): Category? =
        categories.firstOrNull { it.parentId == null && it.kind == CategoryKind.EXPENSE && !it.hidden && it.name.equals(NAME, ignoreCase = true) }

    /** The "Escrow" category: the top-level expense category with that name, if it exists and isn't archived. */
    fun escrowCategory(categories: List<Category>): Category? =
        categories.firstOrNull { it.parentId == null && it.kind == CategoryKind.EXPENSE && !it.hidden && it.name.equals(ESCROW_NAME, ignoreCase = true) }

    const val NAME = "Loan interest"

    /** Shared by every loan with escrow, made the first time one is saved; any category can stand in for it. */
    const val ESCROW_NAME = "Escrow"
}
