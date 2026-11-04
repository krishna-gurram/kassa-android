package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountPicking
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Balances
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.CategoryPicking
import app.kassa.money.domain.ChangeKind
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.LenderKept
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanChangeContext
import app.kassa.money.domain.LoanChangeForm
import app.kassa.money.domain.LoanDetailsForm
import app.kassa.money.domain.LoanDetailsPlanner
import app.kassa.money.domain.LoanEngine
import app.kassa.money.domain.LoanField
import app.kassa.money.domain.LoanPayment
import app.kassa.money.domain.LoanPaymentContext
import app.kassa.money.domain.LoanPaymentForm
import app.kassa.money.domain.LoanPaymentPlanner
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.Money
import app.kassa.money.domain.PaymentBasis
import app.kassa.money.domain.SplitEdit
import app.kassa.money.domain.TxKind

/*
 * Loans on iOS, stage G2 (record a payment, change terms, edit details). The rules are :domain's (`LoanPaymentForm`, `LoanPaymentPlanner`,
 * `LoanChangeForm`, `LoanDetailsForm`, `LoanDetailsPlanner`, `LoanEngine`, `CountedSpending`); like `LoanBridge.kt` this only turns them
 * into flat values for Swift: Strings, Ints, Longs, Booleans (0 means "none"), enums as their names, rows as cells in the column order of
 * `BackupRows.columns(table)` found by name. The sheets' figures come from `LedgerView.loanPaymentSheet` and friends (they delegate to
 * `LoanSheetRules`); what a save checks and writes is `*.plan(data, ...)`, asked inside the store's one transaction.
 */

private fun lpId(id: Long): Long? = if (id > 0) id else null

private fun lpFlag(b: Boolean): String = if (b) "1" else "0"

private fun lpText(x: Long?): String? = x?.toString()

// ---------- record a payment ----------

/**
 * The payment sheet as typed (`LoanPaymentForm`) plus the escrow category: amounts stay text, [day] is an epoch day, ids are 0 for none,
 * [edit] a `SplitEdit` name, [estimate] -1 for "work it out", [countOverride] "" (follow the loan), "1" or "0".
 */
class LoanPaymentInput(
    val amount: String,
    val day: Int,
    val principal: String,
    val interest: String,
    val edit: String,
    val fromId: Long,
    val categoryId: Long,
    val estimate: Long,
    val principalCategoryId: Long,
    val countOverride: String,
    val escrowCategoryId: Long,
) {
    internal fun form(): LoanPaymentForm = LoanPaymentForm(
        amount = amount,
        day = day,
        principal = principal,
        interest = interest,
        edit = SplitEdit.entries.firstOrNull { it.name == edit } ?: SplitEdit.NONE,
        fromId = lpId(fromId),
        categoryId = lpId(categoryId),
        estimate = if (estimate >= 0) estimate else null,
        principalCategoryId = lpId(principalCategoryId),
        countOverride = if (countOverride == "1") true else if (countOverride == "0") false else null,
    )

    companion object {
        internal fun of(f: LoanPaymentForm, escrowCategoryId: Long): LoanPaymentInput = LoanPaymentInput(
            amount = f.amount,
            day = f.day,
            principal = f.principal,
            interest = f.interest,
            edit = f.edit.name,
            fromId = f.fromId ?: 0L,
            categoryId = f.categoryId ?: 0L,
            estimate = f.estimate ?: -1L,
            principalCategoryId = f.principalCategoryId ?: 0L,
            countOverride = f.countOverride?.let { if (it) "1" else "0" } ?: "",
            escrowCategoryId = escrowCategoryId,
        )
    }
}

/**
 * What the payment sheet opens on. [found] is false when the loan, its terms or the edited payment is gone. [input] is the form to start
 * from (a new payment starts on the scheduled amount only when something is due; nothing is preselected for the account unless the loan
 * names one). [amountText] is the edited payment's amount as `Money.format` writes it, for the delete question.
 */
data class LoanPaymentSheetData(
    val found: Boolean,
    val accountId: Long,
    val loanName: String,
    val interestWord: String,
    val editingId: Long,
    val input: LoanPaymentInput,
    val amountText: String,
)

/**
 * Everything under the fields of the payment sheet for the form as typed. Texts go through `Money.format` (masked while hidden);
 * [problems] are never masked ("AMOUNT", "PRINCIPAL", "FROM" and the sentence). [principalDefaultKind] is "LOAN" or "INTEREST":
 * what "Same as ..." on the Principal category row refers to, named by [principalDefaultName].
 */
data class LoanPaymentPreviewData(
    val interestWord: String,
    val hint: String,
    val scheduledText: String,
    val extraText: String,
    val showCharge: Boolean,
    val chargeText: String,
    val showEscrow: Boolean,
    val escrowText: String,
    val balanceAfterText: String,
    val countedText: String,
    val counts: Boolean,
    val loanCounts: Boolean,
    val hasCosts: Boolean,
    val costIsEscrow: Boolean,
    val interestDefaultName: String,
    val principalDefaultKind: String,
    val principalDefaultName: String,
    val costDefaultName: String,
    val problems: List<FieldProblemData>,
)

/** What saving (or deleting) a payment comes to, from `LoanPaymentPlanner`. [problem] is empty when it can be saved. */
class LoanPaymentPlanData internal constructor(
    val problem: String,
    val hasPrincipal: Boolean,
    val hasInterest: Boolean,
    val hasOther: Boolean,
    /** The "Loan interest" category has to be made or unhidden first (use `LoanBridge.categoryStep`). */
    val ensureInterest: Boolean,
    /** The "Escrow" category has to be made or unhidden first. */
    val ensureCost: Boolean,
    val scheduled: Boolean,
    val nextPaymentDay: Int,
    /** The `loan_payments` row to update, 0 for a new payment. */
    val linkId: Long,
    /** The entries of the payment being edited (decimal text), to delete first. */
    val oldEntryIds: List<String>,
    internal val accountId: Long,
    internal val fromId: Long,
    internal val epochMillis: Long,
    internal val draft: LoanPaymentPlanner.Draft?,
    internal val plan: LoanPaymentPlanner.Plan?,
) {
    /** The category the interest entry goes in; 0 when [ensureInterest] says it has to be made first. */
    val interestCategoryId: Long get() = plan?.interestCategoryId ?: 0L

    /** The category the escrow and charge entry goes in; 0 when there is none or [ensureCost] says it has to be made first. */
    val costCategoryId: Long get() = plan?.costCategoryId ?: 0L
}

/** What deleting a payment comes to: [found] false when it is already gone; [nextPaymentDay] is 0 when the date stays. */
data class LoanPaymentDeleteData(
    val found: Boolean,
    val accountId: Long,
    val entryIds: List<String>,
    val nextPaymentDay: Int,
)

/** The sheets' rules over the ledger as it stands. Money must already be set up by the caller (`LedgerView.shown`). */
internal class LoanSheetRules(private val parts: LedgerParts, private val balances: Map<Long, Long>) {

    private val txById = parts.transactions.associateBy { it.id }
    private val categoryById = parts.categories.associateBy { it.id }

    private fun changes(accountId: Long): List<LoanTermChange> = parts.loanChanges.filter { it.accountId == accountId }

    private fun paymentsOf(accountId: Long): List<LoanPayment> = parts.loanPayments.filter { it.accountId == accountId }

    /** The loan account payment [paymentId] belongs to, 0 when there is no such payment. */
    fun accountOfPayment(paymentId: Long): Long = parts.loanPayments.firstOrNull { it.id == paymentId }?.accountId ?: 0L

    fun loan(accountId: Long): Account? = parts.accounts.firstOrNull { it.id == accountId && it.type == AccountType.LOAN }

    fun payment(accountId: Long, paymentId: Long): LoanPayment? =
        if (paymentId <= 0) null else parts.loanPayments.firstOrNull { it.id == paymentId && it.accountId == accountId }

    private fun principalOf(p: LoanPayment?): Long = p?.principalEntryId?.let { txById[it]?.amount } ?: 0L

    /** The payment sheet's context: what is owed before this payment (an edited payment's own principal put back). */
    fun context(accountId: Long, paymentId: Long): LoanPaymentContext? {
        val terms = parts.loanTerms[accountId] ?: return null
        val editing = payment(accountId, paymentId)
        val owedNow = -(balances[accountId] ?: 0L)
        val balance = (owedNow + principalOf(editing)).coerceAtLeast(0L)
        return LoanPaymentContext(terms, changes(accountId), paymentsOf(accountId), balance, editing)
    }

    private fun emptyInput(day: Int): LoanPaymentInput = LoanPaymentInput.of(LoanPaymentForm.starting(day, null), 0L)

    private fun lastEscrowCategory(accountId: Long): Long? {
        val ordered = paymentsOf(accountId).sortedWith(compareBy({ it.day }, { it.id }))
        return ordered.mapNotNull { p -> p.otherEntryId?.let { id -> txById[id]?.categoryId } }.lastOrNull()
    }

    fun sheet(accountId: Long, paymentId: Long, today: Int): LoanPaymentSheetData {
        val account = loan(accountId)
        val ctx = context(accountId, paymentId)
        val missing = LoanPaymentSheetData(false, accountId, "", "", 0L, emptyInput(today), "")
        if (account == null || ctx == null) return missing
        if (paymentId > 0 && ctx.editing == null) return missing
        val terms = ctx.terms
        val editing = ctx.editing
        val form: LoanPaymentForm
        var escrowCategory: Long?
        if (editing != null) {
            val principalTx = editing.principalEntryId?.let { txById[it] }
            val interestTx = editing.interestEntryId?.let { txById[it] }
            val otherTx = editing.otherEntryId?.let { txById[it] }
            val from: Long? = principalTx?.accountId ?: interestTx?.accountId ?: otherTx?.accountId
            form = LoanPaymentForm.of(editing, principalOf(editing), interestTx?.amount ?: 0L, from, interestTx?.categoryId)
            escrowCategory = otherTx?.categoryId
        } else {
            val sources = AccountPicking.payFromOptions(parts.accounts)
            val own: Long? = terms.payFromId?.takeIf { id -> sources.any { it.id == id } }
            val start = LoanPaymentForm.starting(today, own)
            val due = LoanEngine.amountDue(terms, ctx.changes, ctx.balance, ctx.lastDay(today), today)
            form = if (due > 0) start.withAmount(Money.toInput(due), ctx) else start
            escrowCategory = lastEscrowCategory(accountId)
        }
        val shownEscrow: Long? = escrowCategory?.takeIf { id -> categoryById[id]?.hidden == false }
        return LoanPaymentSheetData(
            found = true,
            accountId = accountId,
            loanName = account.name,
            interestWord = terms.interestWord,
            editingId = editing?.id ?: 0L,
            input = LoanPaymentInput.of(form, shownEscrow ?: 0L),
            amountText = if (editing != null) Money.format(editing.amount) else "",
        )
    }

    /** One edit of the sheet: [action] is AMOUNT, DAY (text is the epoch day), PRINCIPAL, INTEREST or COUNTS ("1" or "0"). */
    fun apply(accountId: Long, paymentId: Long, input: LoanPaymentInput, action: String, text: String): LoanPaymentInput {
        val ctx = context(accountId, paymentId) ?: return input
        val f = input.form()
        val next: LoanPaymentForm = when (action) {
            "AMOUNT" -> f.withAmount(text, ctx)
            "DAY" -> f.withDay(text.toIntOrNull() ?: f.day, ctx)
            "PRINCIPAL" -> f.withPrincipal(text, ctx)
            "INTEREST" -> f.withInterest(text, ctx)
            "COUNTS" -> f.withCounts(text == "1", ctx)
            else -> f
        }
        return LoanPaymentInput.of(next, input.escrowCategoryId)
    }

    /** The sheet's own problems for the form as typed (AMOUNT, PRINCIPAL, FROM). Call with Money never masked. */
    fun problems(accountId: Long, paymentId: Long, input: LoanPaymentInput): List<FieldProblemData> {
        val ctx = context(accountId, paymentId) ?: return emptyList()
        val found = input.form().problems(ctx)
        return found.entries.map { FieldProblemData(it.key.name, it.value) }
    }

    private fun pathOf(id: Long?): String? = id?.let { categoryById[it] }?.let { CategoryPicking.path(it, parts.categories) }

    fun preview(accountId: Long, paymentId: Long, input: LoanPaymentInput, problems: List<FieldProblemData>): LoanPaymentPreviewData {
        val ctx = context(accountId, paymentId)
        val empty = LoanPaymentPreviewData("", "", "", "", false, "", false, "", "", "", false, false, false, false, "", "INTEREST", "", "", problems)
        if (ctx == null) return empty
        val terms = ctx.terms
        val f = input.form()
        val s = f.suggestion(ctx)
        val p = f.parts(ctx)
        val interestDefault = pathOf(terms.interestCategoryId)
            ?: LoanCategories.interestCategory(parts.categories)?.let { CategoryPicking.path(it, parts.categories) }
            ?: LoanCategories.NAME
        val loanPrincipal = pathOf(terms.principalCategoryId)
        val interestNow = pathOf(f.categoryId ?: terms.interestCategoryId) ?: interestDefault
        val costDefault = LoanCategories.escrowCategory(parts.categories)?.let { CategoryPicking.path(it, parts.categories) }
            ?: LoanCategories.ESCROW_NAME
        return LoanPaymentPreviewData(
            interestWord = terms.interestWord,
            hint = f.hint(ctx),
            scheduledText = Money.format(f.scheduled(ctx)),
            extraText = Money.format(s.extra),
            showCharge = terms.earlyCharge > 0 || p.charge > 0,
            chargeText = Money.format(p.charge),
            showEscrow = terms.escrow > 0,
            escrowText = Money.format(p.escrow),
            balanceAfterText = Money.format(f.balanceAfter(ctx).coerceAtLeast(0L)),
            countedText = Money.format(f.countedSpending(ctx)),
            counts = f.counts(ctx),
            loanCounts = terms.countAllAsSpending,
            hasCosts = p.escrow + p.charge > 0,
            costIsEscrow = p.escrow > 0,
            interestDefaultName = interestDefault,
            principalDefaultKind = if (loanPrincipal != null) "LOAN" else "INTEREST",
            principalDefaultName = loanPrincipal ?: interestNow,
            costDefaultName = costDefault,
            problems = problems,
        )
    }

    // ----- change terms -----

    private fun changeContext(accountId: Long): LoanChangeContext? {
        val terms = parts.loanTerms[accountId] ?: return null
        return LoanChangeContext(terms, changes(accountId), paymentsOf(accountId), -(balances[accountId] ?: 0L))
    }

    fun changeSheet(accountId: Long, today: Int): LoanChangeSheetData {
        val account = loan(accountId)
        val ctx = changeContext(accountId)
        if (account == null || ctx == null) {
            return LoanChangeSheetData(false, accountId, "", "", false, LoanChangeInput.of(LoanChangeForm.starting(today)))
        }
        // A change starts on the payment that is waiting when that one is already due, so it isn't left on the old terms.
        val next = ctx.terms.nextPaymentDay
        val first = if (next > 0) minOf(next, today) else today
        return LoanChangeSheetData(
            found = true,
            accountId = accountId,
            name = account.name,
            interestWord = ctx.terms.interestWord,
            hasEscrow = ctx.terms.escrow > 0,
            input = LoanChangeInput.of(LoanChangeForm.starting(first)),
        )
    }

    fun changeProblems(accountId: Long, input: LoanChangeInput): List<FieldProblemData> {
        val ctx = changeContext(accountId) ?: return emptyList()
        return input.form().problems(ctx).entries.map { FieldProblemData(it.key.name, it.value) }
    }

    fun changePreview(accountId: Long, input: LoanChangeInput, problems: List<FieldProblemData>): LoanChangePreviewData {
        val ctx = changeContext(accountId) ?: return LoanChangePreviewData(problems, false, false, "", "", false, 0, 0, "", "", "", "", "", false)
        val f = input.form()
        val p = f.preview(ctx)
        val split = f.paymentSplit(ctx)
        val suggested = f.suggestedPayment(ctx)?.let { Money.toInput(it) } ?: ""
        val interestOnly = ctx.terms.effective(ctx.changes, f.day).type == InterestType.INTEREST_ONLY
        if (p == null) {
            return LoanChangePreviewData(
                problems, false, false, "", "", false, 0, 0, "", "", suggested, split?.let { Money.format(it.principalAndInterest) } ?: "",
                split?.let { Money.format(it.escrow) } ?: "", interestOnly,
            )
        }
        val differs = p.payment != p.paymentBefore
        return LoanChangePreviewData(
            problems = problems,
            shown = true,
            paymentStays = !differs,
            paymentText = Money.format(p.payment),
            changeText = if (differs) BalanceCorrection.describe(p.payment - p.paymentBefore) else "",
            showPaymentsLeft = f.kind == ChangeKind.RATE && f.kept == LenderKept.TERM && p.paymentsLeft != null,
            paymentsLeft = p.paymentsLeft ?: 0,
            payoffDay = p.payoffDay ?: 0,
            interestLeftText = p.interestLeft?.let { Money.format(it) } ?: "",
            warning = p.warning ?: "",
            suggestedPaymentText = suggested,
            splitPrincipalInterestText = split?.let { Money.format(it.principalAndInterest) } ?: "",
            splitEscrowText = split?.let { Money.format(it.escrow) } ?: "",
            interestOnlyNow = interestOnly,
        )
    }

    // ----- edit details -----

    fun detailsSheet(accountId: Long): LoanDetailsSheetData {
        val account = loan(accountId)
        val terms = parts.loanTerms[accountId]
        if (account == null || terms == null) {
            return LoanDetailsSheetData(false, accountId, "", LoanDetailsInput.of(LoanDetailsForm.of("", dummyTerms(accountId))), "", "")
        }
        val interestDefault = LoanCategories.interestCategory(parts.categories)?.let { CategoryPicking.path(it, parts.categories) } ?: LoanCategories.NAME
        return LoanDetailsSheetData(
            found = true,
            accountId = accountId,
            interestWord = terms.interestWord,
            input = LoanDetailsInput.of(LoanDetailsForm.of(account.name, terms)),
            interestDefaultName = interestDefault,
            dueDayAnchor = terms.dueDay.toString(),
        )
    }

    private fun dummyTerms(accountId: Long) = app.kassa.money.domain.LoanTerms(
        accountId = accountId, type = InterestType.REDUCING, rate = 0, payment = 0, nextPaymentDay = 0, dueDay = 1,
    )

    fun detailsProblems(accountId: Long, input: LoanDetailsInput): List<FieldProblemData> {
        val terms = parts.loanTerms[accountId] ?: return emptyList()
        val owed = -(balances[accountId] ?: 0L)
        val found = input.form().problems(terms, owed)
        return LoanField.entries.mapNotNull { field -> found[field]?.let { FieldProblemData(field.name, it) } }
    }

    fun detailsPrincipalDefault(accountId: Long, input: LoanDetailsInput): String {
        val terms = parts.loanTerms[accountId]
        val id: Long? = lpId(input.interestCategoryId) ?: terms?.interestCategoryId
        return pathOf(id) ?: LoanCategories.interestCategory(parts.categories)?.let { CategoryPicking.path(it, parts.categories) } ?: LoanCategories.NAME
    }
}

// ---------- change terms data ----------

/** The change-terms form as typed: [kind] a `ChangeKind` name, [type] an `InterestType` name or "", [kept] a `LenderKept` name, [basis] a `PaymentBasis` name. */
class LoanChangeInput(
    val kind: String,
    val day: Int,
    val rate: String,
    val payment: String,
    val termMonths: String,
    val type: String,
    val kept: String,
    val basis: String,
) {
    internal fun form(): LoanChangeForm = LoanChangeForm(
        kind = ChangeKind.entries.firstOrNull { it.name == kind } ?: ChangeKind.RATE,
        day = day,
        rate = rate,
        payment = payment,
        termMonths = termMonths,
        type = InterestType.entries.firstOrNull { it.name == type },
        kept = LenderKept.entries.firstOrNull { it.name == kept } ?: LenderKept.PAYMENT,
        paymentBasis = PaymentBasis.entries.firstOrNull { it.name == basis } ?: PaymentBasis.PRINCIPAL_AND_INTEREST,
    )

    companion object {
        internal fun of(f: LoanChangeForm): LoanChangeInput = LoanChangeInput(
            kind = f.kind.name,
            day = f.day,
            rate = f.rate,
            payment = f.payment,
            termMonths = f.termMonths,
            type = f.type?.name ?: "",
            kept = f.kept.name,
            basis = f.paymentBasis.name,
        )
    }
}

/** What the change-terms page opens on. */
data class LoanChangeSheetData(
    val found: Boolean,
    val accountId: Long,
    val name: String,
    val interestWord: String,
    val hasEscrow: Boolean,
    val input: LoanChangeInput,
)

/** The live result under the change form (`ChangePreview`); [shown] is false while the form has a problem. */
data class LoanChangePreviewData(
    val problems: List<FieldProblemData>,
    val shown: Boolean,
    val paymentStays: Boolean,
    val paymentText: String,
    val changeText: String,
    val showPaymentsLeft: Boolean,
    val paymentsLeft: Int,
    /** Epoch day of the payoff, 0 when it isn't within a hundred years. */
    val payoffDay: Int,
    val interestLeftText: String,
    val warning: String,
    /** The payment Kassa works out for a new term, as field text; empty when it can't say yet. */
    val suggestedPaymentText: String,
    val splitPrincipalInterestText: String,
    val splitEscrowText: String,
    /** The loan is interest only on the change date, so the payment may be left as "just the interest". */
    val interestOnlyNow: Boolean,
)

/** What saving a change comes to. [problem] is empty when it can be saved. */
class LoanChangePlanData internal constructor(val problem: String, internal val change: LoanTermChange?)

// ---------- details data ----------

/** The Edit details form as typed (`LoanDetailsForm`): ids 0 for none, [startDay] 0 for none. */
class LoanDetailsInput(
    val name: String,
    val dueDay: String,
    val payFromId: Long,
    val interestCategoryId: Long,
    val counts: Boolean,
    val principalCategoryId: Long,
    val escrow: String,
    val fee: String,
    val earlyCharge: String,
    val original: String,
    val startDay: Int,
) {
    internal fun form(): LoanDetailsForm = LoanDetailsForm(
        name = name,
        dueDay = dueDay,
        payFromId = lpId(payFromId),
        interestCategoryId = lpId(interestCategoryId),
        countAllAsSpending = counts,
        principalCategoryId = lpId(principalCategoryId),
        escrow = escrow,
        fee = fee,
        earlyCharge = earlyCharge,
        original = original,
        startDay = if (startDay > 0) startDay else null,
    )

    companion object {
        internal fun of(f: LoanDetailsForm): LoanDetailsInput = LoanDetailsInput(
            name = f.name,
            dueDay = f.dueDay,
            payFromId = f.payFromId ?: 0L,
            interestCategoryId = f.interestCategoryId ?: 0L,
            counts = f.countAllAsSpending,
            principalCategoryId = f.principalCategoryId ?: 0L,
            escrow = f.escrow,
            fee = f.fee,
            earlyCharge = f.earlyCharge,
            original = f.original,
            startDay = f.startDay ?: 0,
        )
    }
}

/** What Edit details opens on. [interestDefaultName] is the name "Loan interest" stands for; [dueDayAnchor] is the due day as a placeholder. */
data class LoanDetailsSheetData(
    val found: Boolean,
    val accountId: Long,
    val interestWord: String,
    val input: LoanDetailsInput,
    val interestDefaultName: String,
    val dueDayAnchor: String,
)

/** What saving the details comes to: [problem] is empty when they can be saved; [name] is the cleaned name for the account. */
class LoanDetailsPlanData internal constructor(
    val problem: String,
    val name: String,
    /** The shared "Escrow" category has to exist after this save (the "Loan interest" one always does). */
    val needsEscrowCategory: Boolean,
    internal val terms: app.kassa.money.domain.LoanTerms?,
)

// ---------- what Swift asks ----------

/** Record a payment: what saving and deleting check and write (`LoanPaymentPlanner`), asked on the store's data inside its transaction. */
object LoanPaymentBridge {

    /**
     * What recording the payment [input] on loan [accountId] comes to ([paymentId] 0 for a new one, else the one edited), given the
     * store as it stands ([data], before anything is written) at [epochMillis]. The sheet's own problems come first, then the
     * repository's checks in the repository's order. Mirrors `LoanRepository.recordPayment`.
     */
    fun plan(data: BackupData, accountId: Long, paymentId: Long, input: LoanPaymentInput, epochMillis: Long, currency: String): LoanPaymentPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val balances = Balances.of(parts.accounts, parts.transactions)
            val rules = LoanSheetRules(parts, balances)
            val ctx = rules.context(accountId, paymentId)
            val none = LoanPaymentPlanData("Add this loan's terms first.", false, false, false, false, false, false, 0, 0L, emptyList(), accountId, 0L, epochMillis, null, null)
            if (ctx == null) {
                none
            } else {
                val form = input.form()
                val sheetProblem = form.problems(ctx).values.firstOrNull()
                if (sheetProblem != null) {
                    LoanPaymentPlanData(sheetProblem, false, false, false, false, false, false, 0, 0L, emptyList(), accountId, 0L, epochMillis, null, null)
                } else {
                    val p = form.parts(ctx)
                    val draft = LoanPaymentPlanner.Draft(
                        id = lpId(paymentId), accountId = accountId, fromId = form.fromId, day = form.day, epochMillis = epochMillis,
                        amount = p.amount, principal = p.principal, interest = p.interest, escrow = p.escrow, charge = p.charge,
                        categoryId = form.categoryId, escrowCategoryId = lpId(input.escrowCategoryId), edited = p.edited, estimate = p.estimate,
                        countAllAsSpending = form.countOverride, principalCategoryId = form.principalCategoryId,
                    )
                    val old = rules.payment(accountId, paymentId)
                    val oldEntries = old?.entryIds?.mapNotNull { id -> parts.transactions.firstOrNull { it.id == id } } ?: emptyList()
                    val facts = LoanPaymentPlanner.Facts(
                        loan = rules.loan(accountId), terms = parts.loanTerms[accountId], changes = parts.loanChanges.filter { it.accountId == accountId },
                        accounts = parts.accounts, categories = parts.categories, old = old, oldEntries = oldEntries,
                        balance = balances[accountId] ?: 0L,
                    )
                    val plan = LoanPaymentPlanner.plan(draft, facts)
                    val problem = plan.problem
                    if (problem != null) {
                        LoanPaymentPlanData(problem, false, false, false, false, false, false, 0, 0L, emptyList(), accountId, 0L, epochMillis, draft, plan)
                    } else {
                        LoanPaymentPlanData(
                            problem = "",
                            hasPrincipal = plan.principal > 0,
                            hasInterest = plan.interest > 0,
                            hasOther = plan.other > 0,
                            ensureInterest = plan.interestCategoryId == null,
                            ensureCost = plan.other > 0 && plan.costCategoryId == null,
                            scheduled = plan.scheduled,
                            nextPaymentDay = plan.nextPaymentDay,
                            linkId = plan.linkId,
                            oldEntryIds = oldEntries.map { it.id.toString() },
                            accountId = accountId,
                            fromId = draft.fromId ?: 0L,
                            epochMillis = epochMillis,
                            draft = draft,
                            plan = plan,
                        )
                    }
                }
            }
        }

    /**
     * One cell of a `transactions` row of the payment (the columns of `BackupRows`): [which] is PRINCIPAL (a TRANSFER from the pay-from
     * account to the loan), INTEREST (an EXPENSE in [interestCategory]) or OTHER (escrow and charge, an EXPENSE in [costCategory]).
     * Pass the category ids once `ensureInterest` / `ensureCost` were taken care of; 0 for none.
     */
    fun entryCell(plan: LoanPaymentPlanData, which: String, interestCategory: Long, costCategory: Long, column: Int): String? {
        val p = plan.plan ?: return null
        val kind: String
        val amount: Long
        val note: String
        var category: Long? = null
        var to: Long? = null
        when (which) {
            "PRINCIPAL" -> { kind = TxKind.TRANSFER.name; amount = p.principal; note = p.principalNote; to = plan.accountId }
            "INTEREST" -> { kind = TxKind.EXPENSE.name; amount = p.interest; note = p.interestNote; category = lpId(interestCategory) }
            else -> { kind = TxKind.EXPENSE.name; amount = p.other; note = p.otherNote; category = lpId(costCategory) }
        }
        return when (BackupRows.columns("transactions").getOrNull(column)) {
            "kind" -> kind
            "amount" -> amount.toString()
            "accountId" -> plan.fromId.toString()
            "toAccountId" -> lpText(to)
            "categoryId" -> lpText(category)
            "epochMillis" -> plan.epochMillis.toString()
            "note" -> note
            else -> null
        }
    }

    /** One cell of the `loan_payments` row, with the ids the entries got (0 for none). */
    fun linkCell(plan: LoanPaymentPlanData, principalId: Long, interestId: Long, otherId: Long, column: Int): String? {
        val d = plan.draft ?: return null
        val p = plan.plan ?: return null
        return when (BackupRows.columns("loan_payments").getOrNull(column)) {
            "accountId" -> d.accountId.toString()
            "day" -> d.day.toString()
            "amount" -> d.amount.toString()
            "principalEntryId" -> lpText(lpId(principalId))
            "interestEntryId" -> lpText(lpId(interestId))
            "otherEntryId" -> lpText(lpId(otherId))
            "escrow" -> d.escrow.toString()
            "charge" -> d.charge.toString()
            "edited" -> lpFlag(d.edited)
            "estimate" -> d.estimate.toString()
            "scheduled" -> lpFlag(p.scheduled)
            "countAllAsSpending" -> d.countAllAsSpending?.let { lpFlag(it) }
            "principalCategoryId" -> lpText(p.principalCategoryId)
            else -> null
        }
    }

    /** What deleting payment [paymentId] comes to (`LoanRepository.deletePayment`): its entries, and the next payment date when it moves back. */
    fun deletePlan(data: BackupData, paymentId: Long): LoanPaymentDeleteData {
        val parts = LedgerParts.of(data)
        val p = parts.loanPayments.firstOrNull { it.id == paymentId } ?: return LoanPaymentDeleteData(false, 0L, emptyList(), 0)
        val next = app.kassa.money.domain.LoanPaymentPlanner.nextDayAfterDelete(p, parts.loanTerms[p.accountId])
        return LoanPaymentDeleteData(true, p.accountId, p.entryIds.map { it.toString() }, next ?: 0)
    }
}

/** Change terms: what saving a dated change checks and writes (`LoanRepository.addChange`). */
object LoanChangeBridge {

    /** The sheet's own problems first, then the repository's (`LoanTermChange.problem`); empty problem when it can be saved. */
    fun plan(data: BackupData, accountId: Long, input: LoanChangeInput, currency: String): LoanChangePlanData = withMoney(currency) {
        val parts = LedgerParts.of(data)
        val balances = Balances.of(parts.accounts, parts.transactions)
        val rules = LoanSheetRules(parts, balances)
        val sheet = rules.changeProblems(accountId, input).firstOrNull()
        val terms = parts.loanTerms[accountId]
        if (sheet != null) {
            LoanChangePlanData(sheet.message, null)
        } else if (terms == null) {
            LoanChangePlanData("Add this loan's terms first.", null)
        } else {
            val form = input.form()
            val owed = -(balances[accountId] ?: 0L)
            val ctx = LoanChangeContext(
                terms, parts.loanChanges.filter { it.accountId == accountId }, parts.loanPayments.filter { it.accountId == accountId }, owed,
            )
            val change = form.change(ctx)
            val problem = change.problem(terms, parts.loanChanges)
            LoanChangePlanData(problem ?: "", if (problem == null) change else null)
        }
    }

    /** One cell of the new `loan_term_changes` row (the columns of `BackupRows`; the id is the database's). */
    fun cell(plan: LoanChangePlanData, column: Int): String? {
        val c = plan.change ?: return null
        return when (BackupRows.columns("loan_term_changes").getOrNull(column)) {
            "accountId" -> c.accountId.toString()
            "effectiveDay" -> c.effectiveDay.toString()
            "rate" -> lpText(c.rate)
            "payment" -> lpText(c.payment)
            "termMonths" -> c.termMonths?.toString()
            "type" -> c.type?.name
            else -> null
        }
    }
}

/** Edit details: what saving checks and writes (`LoanRepository.updateDetails`). */
object LoanDetailsBridge {

    /** The sheet's problems first, then the repository's; empty problem when the details can be saved. */
    fun plan(data: BackupData, accountId: Long, input: LoanDetailsInput, currency: String): LoanDetailsPlanData = withMoney(currency) {
        val parts = LedgerParts.of(data)
        val balances = Balances.of(parts.accounts, parts.transactions)
        val owed = -(balances[accountId] ?: 0L)
        val account = parts.accounts.firstOrNull { it.id == accountId }
        val plan = LoanDetailsPlanner.plan(input.form(), account, parts.loanTerms[accountId], parts.accounts, parts.categories, owed)
        LoanDetailsPlanData(plan.problem ?: "", plan.name, plan.needsEscrowCategory, plan.terms)
    }

    /** One cell of the `loan_terms` row to store (the columns of `BackupRows`). */
    fun termsCell(plan: LoanDetailsPlanData, column: Int): String? {
        val t = plan.terms ?: return null
        return LoanBridge.termsCellOf(t, column)
    }
}
