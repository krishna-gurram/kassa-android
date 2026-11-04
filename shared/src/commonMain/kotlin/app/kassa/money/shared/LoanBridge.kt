package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Balances
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryColors
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.IslamicContract
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanField
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanKind
import app.kassa.money.domain.LoanRate
import app.kassa.money.domain.LoanStage
import app.kassa.money.domain.Money
import app.kassa.money.domain.PaymentBasis
import app.kassa.money.domain.TxKind

/*
 * Loans on iOS, stage G1 (setup, the loan page, balance correction): what Swift calls to check and build the rows it writes. The rules
 * are :domain's (`LoanForm`, `LoanEngine`, `LoanSummary`, `LoanCategories`, `BalanceCorrection`); like `WriteBridge.kt` this only turns
 * them into flat values: Strings, Ints, Longs, Booleans (0 means "none"; ids start at 1, epoch day 0 is never a loan date), enums
 * as their names, rows as cells in the column order of `BackupRows.columns(table)`, found by name so a column that moves can't be
 * bound to the wrong value. The loan page itself is `LedgerView.loanPage`.
 */

private fun loanId(id: Long): Long? = if (id > 0) id else null

private fun loanFlag(b: Boolean): String = if (b) "1" else "0"

private fun loanText(x: Long?): String? = x?.toString()

/** Runs [block] with Money in the currency [code] (fallback when unknown) and masked when [hidden], then puts Money back. */
internal fun <T> withMoneyHidden(code: String?, hidden: Boolean, block: () -> T): T {
    val style = Money.style
    val hide = Money.hideAmounts
    Money.style = Currencies.forCode(code) ?: Currencies.fallback
    Money.hideAmounts = { hidden }
    try {
        return block()
    } finally {
        Money.style = style
        Money.hideAmounts = hide
    }
}

/**
 * The loan setup form as Swift has it, as typed (`LoanForm`): amounts and rates stay text (the person's own marks are read by the
 * domain), days are epoch days, [startDay] and [payFromId] and [interestCategoryId] are 0 for none. [stage] is a `LoanStage` name,
 * [kind] a `LoanKind` name, [contract] an `IslamicContract` name and [basis] a `PaymentBasis` name.
 */
class LoanInput(
    val stage: String,
    val locked: Boolean,
    val kind: String,
    val contract: String,
    val amount: String,
    val rate: String,
    val termMonths: String,
    val firstDay: Int,
    val balance: String,
    val payment: String,
    val nextDay: Int,
    val paymentsLeft: String,
    val original: String,
    val startDay: Int,
    val dueDay: String,
    val payFromId: Long,
    val escrow: String,
    val fee: String,
    val earlyCharge: String,
    val interestCategoryId: Long,
    val basis: String,
) {
    internal fun form(): LoanForm = LoanForm(
        stage = LoanStage.entries.firstOrNull { it.name == stage } ?: LoanStage.NEW,
        stageLocked = locked,
        kind = LoanKind.entries.firstOrNull { it.name == kind } ?: LoanKind.REDUCING,
        contract = IslamicContract.entries.firstOrNull { it.name == contract } ?: IslamicContract.MURABAHA,
        amount = amount,
        rate = rate,
        termMonths = termMonths,
        firstDay = firstDay,
        balance = balance,
        payment = payment,
        nextDay = nextDay,
        paymentsLeft = paymentsLeft,
        original = original,
        startDay = if (startDay > 0) startDay else null,
        dueDay = dueDay,
        payFromId = loanId(payFromId),
        escrow = escrow,
        fee = fee,
        earlyCharge = earlyCharge,
        interestCategoryId = loanId(interestCategoryId),
        paymentBasis = PaymentBasis.entries.firstOrNull { it.name == basis } ?: PaymentBasis.PRINCIPAL_AND_INTEREST,
    )

    companion object {
        internal fun of(f: LoanForm): LoanInput = LoanInput(
            stage = f.stage.name,
            locked = f.stageLocked,
            kind = f.kind.name,
            contract = f.contract.name,
            amount = f.amount,
            rate = f.rate,
            termMonths = f.termMonths,
            firstDay = f.firstDay,
            balance = f.balance,
            payment = f.payment,
            nextDay = f.nextDay,
            paymentsLeft = f.paymentsLeft,
            original = f.original,
            startDay = f.startDay ?: 0,
            dueDay = f.dueDay,
            payFromId = f.payFromId ?: 0L,
            escrow = f.escrow,
            fee = f.fee,
            earlyCharge = f.earlyCharge,
            interestCategoryId = f.interestCategoryId ?: 0L,
            basis = f.paymentBasis.name,
        )
    }
}

/**
 * The live calculation under the loan form (`LoanPreview`), as flat text. Empty text, -1 and 0 mean "not worked out or not known".
 * [warning] is the domain's own sentence for a payment that can't pay the loan off. Amounts go through `Money.format`, so Hide
 * balances masks them.
 */
data class LoanPreviewData(
    val shown: Boolean,
    val paymentText: String,
    val totalInterestText: String,
    val paymentsLeft: Int,
    /** Epoch day of the last payment (a new loan) or of the payoff (a loan under way); 0 when it isn't within a hundred years. */
    val payoffDay: Int,
    val interestLeftText: String,
    val equivalentRateText: String,
    val owedAtEndText: String,
    /** An interest-only loan under way: what each month's payment is (the interest). */
    val interestEachMonthText: String,
    val warning: String,
)

/** What the first loan (or the first with escrow) has to do about its category: [action] is KEEP, UNHIDE or CREATE; [id] is the existing one. */
data class LoanCategoryStep(val action: String, val id: Long)

/** What Swift asks of the loan setup. See `LoanForm`. */
object LoanBridge {

    /** The interest chips of the form, in order (`LoanKind` names). */
    fun kinds(): List<String> = LoanKind.entries.map { it.name }

    /** The two Islamic contracts (`IslamicContract` names). */
    fun contracts(): List<String> = IslamicContract.entries.map { it.name }

    /** What the typed payment can mean (`PaymentBasis` names). */
    fun bases(): List<String> = PaymentBasis.entries.map { it.name }

    /** The form for a new loan on [todayEpochDay]: the first payment a month away; [payFromId] 0 for none. */
    fun starting(todayEpochDay: Int, payFromId: Long): LoanInput =
        LoanInput.of(LoanForm.starting(todayEpochDay, loanId(payFromId)))

    /** The `InterestType` name the form describes: FLAT asks for payments left, INTEREST_ONLY may leave the payment out. */
    fun type(input: LoanInput): String = input.form().type.name

    /** True when the form is for Islamic finance, so screens say "Profit" where they would say "Interest". */
    fun profit(input: LoanInput): Boolean = input.form().profit

    /**
     * The first problem of every field (a `LoanField` name and the sentence) in field order, with the account's own NAME and LAST4
     * problems ahead of them (`AccountField` names): empty when the loan can be added. [takenNames] are the other accounts' names.
     */
    fun problems(account: AccountInput, loan: LoanInput, takenNames: List<String>, currency: String): List<FieldProblemData> =
        withMoney(currency) {
            val result = ArrayList<FieldProblemData>()
            val accountForm = account.form()
            if (accountForm != null) {
                val found = accountForm.problems(takenNames)
                for (field in listOf(app.kassa.money.domain.AccountField.NAME, app.kassa.money.domain.AccountField.LAST4)) {
                    found[field]?.let { result.add(FieldProblemData(field.name, it)) }
                }
            }
            result.addAll(termsProblems(loan, currency))
            result
        }

    /** Each loan field's problem (a `LoanField` name and the sentence) in field order; empty when the terms can be saved. */
    fun termsProblems(loan: LoanInput, currency: String): List<FieldProblemData> = withMoney(currency) {
        val found = loan.form().problems()
        LoanField.entries.mapNotNull { f -> found[f]?.let { FieldProblemData(f.name, it) } }
    }

    /** The calculation under the form (`LoanForm.preview`); [shown] is false until the figures it needs are valid. */
    fun preview(loan: LoanInput, currency: String, hidden: Boolean): LoanPreviewData = withMoneyHidden(currency, hidden) {
        val form = loan.form()
        val p = form.preview()
        if (p == null) {
            LoanPreviewData(false, "", "", -1, 0, "", "", "", "", "")
        } else {
            val each = if (form.stage == LoanStage.PARTWAY && form.type == app.kassa.money.domain.InterestType.INTEREST_ONLY) {
                Money.format(form.toTerms(0).payment)
            } else ""
            LoanPreviewData(
                shown = true,
                paymentText = p.payment?.let { Money.format(it) } ?: "",
                totalInterestText = p.totalInterest?.let { Money.format(it) } ?: "",
                paymentsLeft = p.paymentsLeft ?: -1,
                payoffDay = p.payoffDay ?: 0,
                interestLeftText = p.interestLeft?.let { Money.format(it) } ?: "",
                equivalentRateText = p.equivalentRate?.let { LoanRate.display(it) } ?: "",
                owedAtEndText = p.owedAtEnd?.let { Money.format(it) } ?: "",
                interestEachMonthText = each,
                warning = p.warning ?: "",
            )
        }
    }

    /** What is owed once the loan is added, as the balance text the account form takes (Android types it into the balance field). */
    fun owedInput(loan: LoanInput, currency: String): String = withMoney(currency) {
        val owed = loan.form().owed
        if (owed == null) "" else Money.toInput(owed)
    }

    // ---------- saving ----------

    /**
     * What the save refuses beyond the form's own problems, with the sentence (empty when nothing): the terms' own rules, a pay-from
     * account that is gone or is the loan itself, a category that is gone. Mirrors `LoanRepository.saveTerms`. [accountId] is the
     * loan's account (0 for one not made yet); [data] is the store as it stands.
     */
    fun saveProblem(loan: LoanInput, accountId: Long, data: BackupData, currency: String): String = withMoney(currency) {
        val form = loan.form()
        val first = form.problems().values.firstOrNull()
        if (first != null) {
            first
        } else {
            val terms = form.toTerms(accountId)
            val payFrom = terms.payFromId
            val category = terms.interestCategoryId
            val problem = terms.problem()
            when {
                problem != null -> problem
                payFrom != null && (payFrom == accountId || data.accounts.none { it.id == payFrom }) -> "Pick another account to pay from."
                category != null && data.categories.none { it.id == category } -> "That category isn't there any more."
                else -> ""
            }
        }
    }

    /** One cell of the new `loan_terms` row for [accountId] (the columns of `BackupRows`); call only when `saveProblem` is empty. */
    fun termsCell(loan: LoanInput, accountId: Long, column: Int): String? = withMoney(null) {
        val t = loan.form().toTerms(accountId)
        when (BackupRows.columns("loan_terms").getOrNull(column)) {
            "accountId" -> t.accountId.toString()
            "type" -> t.type.name
            "profit" -> loanFlag(t.profit)
            "rate" -> t.rate.toString()
            "payment" -> t.payment.toString()
            "termMonths" -> t.termMonths?.toString()
            "originalAmount" -> loanText(t.originalAmount)
            "startDay" -> t.startDay?.toString()
            "nextPaymentDay" -> t.nextPaymentDay.toString()
            "dueDay" -> t.dueDay.toString()
            "escrow" -> t.escrow.toString()
            "fee" -> t.fee.toString()
            "earlyCharge" -> t.earlyCharge.toString()
            "interestCategoryId" -> loanText(t.interestCategoryId)
            "payFromId" -> loanText(t.payFromId)
            "countAllAsSpending" -> loanFlag(t.countAllAsSpending)
            // A principal category only means something while the whole payment counts as spending.
            "principalCategoryId" -> loanText(t.principalCategoryId.takeIf { t.countAllAsSpending })
            else -> null
        }
    }

    /** True when escrow is set, so the shared "Escrow" category has to exist (`LoanRepository.saveTerms`). */
    fun hasEscrow(loan: LoanInput): Boolean = withMoney(null) { loan.form().toTerms(0).escrow > 0 }

    /**
     * What the shared category [which] ("INTEREST" for "Loan interest", "ESCROW" for "Escrow") needs: KEEP when an open top-level
     * expense category has the name, UNHIDE (with its [LoanCategoryStep.id]) when an archived one does, CREATE otherwise.
     * Subcategories are never made here. Mirrors `LoanRepository.interestCategory` and `escrowCategory`.
     */
    fun categoryStep(data: BackupData, which: String): LoanCategoryStep {
        val all = LedgerParts.of(data).categories
        val name = nameOf(which)
        val open = if (which == "ESCROW") LoanCategories.escrowCategory(all) else LoanCategories.interestCategory(all)
        if (open != null) return LoanCategoryStep("KEEP", open.id)
        val archived = all.firstOrNull { it.parentId == null && it.kind == CategoryKind.EXPENSE && it.name.equals(name, ignoreCase = true) }
        if (archived != null) return LoanCategoryStep("UNHIDE", archived.id)
        return LoanCategoryStep("CREATE", 0L)
    }

    private fun nameOf(which: String): String = if (which == "ESCROW") LoanCategories.ESCROW_NAME else LoanCategories.NAME

    /**
     * One cell of the `categories` row to create for [which] (the columns of `BackupRows`): the name, an expense, the "bank" (or "home")
     * symbol, the first colour nobody of its kind uses, and not counted toward the safe-to-spend figure (a loan is a standing cost).
     * [sortOrder] puts it last.
     */
    fun categoryCell(data: BackupData, which: String, column: Int, sortOrder: Int): String? {
        val all = LedgerParts.of(data).categories
        val c = Category(
            name = nameOf(which), kind = CategoryKind.EXPENSE,
            icon = CategoryIcon.Symbol(if (which == "ESCROW") "home" else "bank"),
            color = CategoryColors.next(all, CategoryKind.EXPENSE), countsTowardDaily = false, sortOrder = sortOrder,
        )
        return when (BackupRows.columns("categories").getOrNull(column)) {
            "name" -> c.name
            "kind" -> c.kind.name
            "icon" -> c.icon.encode()
            "color" -> c.color.toString()
            "countsTowardDaily" -> loanFlag(c.countsTowardDaily)
            "hidden" -> loanFlag(c.hidden)
            "sortOrder" -> c.sortOrder.toString()
            else -> null
        }
    }

    /**
     * The account's starting balance once terms are added to the existing loan account [accountId] from this form: its balance today
     * becomes what the form says is owed, so the account's own entries stay as they are (`LoanForm.openingBalance`). Call only when
     * the form has no problems.
     */
    fun openingBalanceAfter(loan: LoanInput, accountId: Long, data: BackupData): Long {
        val parts = LedgerParts.of(data)
        val account = parts.accounts.first { it.id == accountId }
        val current = Balances.of(parts.accounts, parts.transactions)[accountId] ?: 0L
        return loan.form().openingBalance(account, current)
    }

    /** True for a loan account (`AccountType.LOAN`) from its `AccountType` name. */
    fun isLoanType(type: String): Boolean = type == AccountType.LOAN.name
}

/** What the balance correction sheet shows under the figure typed: the difference and the problem (empty when it can be saved). */
data class CorrectionPreviewData(val differenceText: String, val problem: String)

/** What a balance correction comes to: [problem] is the domain's sentence (empty when none), else the [kind] (`TxKind` name) and positive [amount] to store. */
data class CorrectionPlanData(val problem: String, val kind: String, val amount: Long)

/** The balance correction sheet's rules (`BalanceCorrection`). */
object CorrectionBridge {

    /** True for ADJUST_IN and ADJUST_OUT (kinds are `TxKind` names). */
    fun isCorrection(kind: String): Boolean {
        val k = TxKind.entries.firstOrNull { it.name == kind } ?: return false
        return BalanceCorrection.isCorrection(k)
    }

    /**
     * The difference line and the problem for [typedShown] (what the account should show, a debt as what is owed, cents, may be
     * negative) against [shownWithout] (what it shows now without the correction being edited). [typed] is false while nothing has been
     * typed. Amounts in the problem are never masked; the difference is, while [hidden].
     */
    fun preview(shownWithout: Long, typedShown: Long, typed: Boolean, debt: Boolean, currency: String, hidden: Boolean): CorrectionPreviewData {
        if (!typed) return CorrectionPreviewData("", "")
        val difference = typedShown - shownWithout
        val text = if (difference != 0L) withMoneyHidden(currency, hidden) { BalanceCorrection.describe(difference) } else ""
        val without = BalanceCorrection.targetFrom(shownWithout, debt)
        val change = BalanceCorrection.change(BalanceCorrection.targetFrom(typedShown, debt), without)
        val problem = withMoney(currency) {
            InputRules.balanceProblem(typedShown) ?: BalanceCorrection.problem(change)
        }
        return CorrectionPreviewData(text, problem ?: "")
    }

    /** One cell of the new correction's `transactions` row (the columns of `BackupRows`): null for what a correction doesn't have (category, person, note is empty). */
    fun cell(kind: String, amount: Long, accountId: Long, epochMillis: Long, column: Int): String? =
        when (BackupRows.columns("transactions").getOrNull(column)) {
            "kind" -> kind
            "amount" -> amount.toString()
            "accountId" -> accountId.toString()
            "epochMillis" -> epochMillis.toString()
            "note" -> ""
            else -> null
        }
}
