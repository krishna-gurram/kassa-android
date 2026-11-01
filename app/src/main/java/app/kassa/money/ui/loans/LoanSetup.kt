package app.kassa.money.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Category
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.IslamicContract
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanDates
import app.kassa.money.domain.LoanDetailsForm
import app.kassa.money.domain.LoanField
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanKind
import app.kassa.money.domain.LoanPreview
import app.kassa.money.domain.LoanRate
import app.kassa.money.domain.LoanStage
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.PaymentSplit
import app.kassa.money.domain.PaymentBasis
import java.time.YearMonth
import app.kassa.money.ui.common.monthYearShort
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.CategoryChoiceRow
import app.kassa.money.ui.common.CategoryPickerHost
import app.kassa.money.ui.common.categoryPath
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.NameField
import app.kassa.money.ui.common.PercentField
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.settings.SwitchRow
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalDate

/** Loan screens keep dates as epoch days (the rules know nothing of java.time); these convert at the edge. */
internal fun LocalDate.loanDay(): Int = toEpochDay().toInt()
internal fun dateOfLoanDay(day: Int): LocalDate = LocalDate.ofEpochDay(day.toLong())

/** "Nov 2030", in the phone's language. */
internal fun monthYear(day: Int): String = monthYearShort(YearMonth.from(dateOfLoanDay(day)))

/** The accounts a payment can come from, A to Z. */
internal fun payFromChoices(accounts: List<Account>, except: Long = 0): List<Account> =
    accounts.filter { it.isActive && it.id != except && it.type in PAY_FROM_TYPES }.sortedWith(Names.order { it.name })

private val PAY_FROM_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)

/**
 * The loan setup fields, from the prototype: how it starts, how interest is charged, the figures, where it's paid from
 * and on which day, a collapsed "More", and the live calculation. All the checking and building is [LoanForm]'s; this
 * only lays it out. Used by the Add account sheet (a new loan) and [LoanTermsSheet] (terms on an existing loan).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoanFields(
    form: LoanForm,
    problems: Map<LoanField, String>,
    edit: (LoanField?, (LoanForm) -> LoanForm) -> Unit,
    today: LocalDate,
    payFromOptions: List<Account>,
    categories: List<Category>,
) {
    val word = form.word
    val preview = remember(form) { form.preview() }
    var showProgress by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }

    if (!form.stageLocked) {
        Label(stringResource(R.string.loans_this_loan_is))
        FlowRow {
            Chip(stringResource(R.string.loans_starting_now), form.stage == LoanStage.NEW, small = true) { edit(null) { it.copy(stage = LoanStage.NEW) } }
            Chip(stringResource(R.string.loans_already_partway), form.stage == LoanStage.PARTWAY, small = true) { edit(null) { it.copy(stage = LoanStage.PARTWAY) } }
        }
    }
    Label(stringResource(R.string.loans_how_interest_charged))
    FlowRow { LoanKind.entries.forEach { k -> Chip(k.label, form.kind == k, small = true) { edit(null) { it.copy(kind = k) } } } }
    Text(
        if (form.kind == LoanKind.ISLAMIC) stringResource(R.string.loans_islamic_hint) else form.type.hint,
        style = KassaType.small, color = Kassa.colors.ink2,
    )
    if (form.kind == LoanKind.ISLAMIC) {
        Label(stringResource(R.string.loans_contract))
        FlowRow { IslamicContract.entries.forEach { c -> Chip(c.label, form.contract == c, small = true) { edit(null) { it.copy(contract = c) } } } }
    }

    val rateLabel = if (form.type == InterestType.FLAT) stringResource(R.string.loans_rate_a_year_flat, word) else stringResource(R.string.loans_rate_a_year, word)
    if (form.stage == LoanStage.NEW) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(form.amount, { v -> edit(LoanField.AMOUNT) { it.copy(amount = v) } }, stringResource(R.string.loans_amount_borrowed), Modifier.weight(1f), problem = problems[LoanField.AMOUNT])
            PercentField(form.rate, { v -> edit(LoanField.RATE) { it.copy(rate = v) } }, rateLabel, Modifier.weight(1f), problem = problems[LoanField.RATE])
        }
        DigitsField(form.termMonths, { v -> edit(LoanField.TERM) { it.copy(termMonths = v) } }, stringResource(R.string.loans_term_in_months), 3, Modifier.fillMaxWidth(), problem = problems[LoanField.TERM])
        Label(stringResource(R.string.loans_first_payment))
        DateRow(dateOfLoanDay(form.firstDay), today, allowFuture = true) { d -> edit(null) { it.copy(firstDay = d.loanDay()) } }
        PaymentBasisFields(form, problems, edit)
        AmountField(
            form.payment, { v -> edit(LoanField.PAYMENT) { it.copy(payment = v) } }, stringResource(R.string.loans_payment), Modifier.fillMaxWidth().padding(top = 8.dp),
            problem = problems[LoanField.PAYMENT], placeholder = preview?.payment?.takeIf { form.paymentBasis == PaymentBasis.PRINCIPAL_AND_INTEREST }?.let { Money.toInput(it) } ?: stringResource(R.string.loans_worked_out_for_you),
        )
        Text(stringResource(R.string.loans_kassa_works_out), style = KassaType.small, color = Kassa.colors.ink2)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(form.balance, { v -> edit(LoanField.BALANCE) { it.copy(balance = v) } }, stringResource(R.string.loans_balance_today), Modifier.weight(1f), problem = problems[LoanField.BALANCE])
            PercentField(form.rate, { v -> edit(LoanField.RATE) { it.copy(rate = v) } }, rateLabel, Modifier.weight(1f), problem = problems[LoanField.RATE])
        }
        PaymentBasisFields(form, problems, edit)
        AmountField(
            form.payment, { v -> edit(LoanField.PAYMENT) { it.copy(payment = v) } }, stringResource(R.string.loans_payment), Modifier.fillMaxWidth(),
            problem = problems[LoanField.PAYMENT], placeholder = if (form.type == InterestType.INTEREST_ONLY) stringResource(R.string.loans_just_the_interest, word.lowercase()) else null,
        )
        Label(stringResource(R.string.loans_next_payment))
        DateRow(dateOfLoanDay(form.nextDay), today, allowFuture = true) { d -> edit(null) { it.copy(nextDay = d.loanDay()) } }
        // A flat loan's interest in each payment depends on what it started as; payments left says that.
        if (form.type == InterestType.FLAT) {
            DigitsField(
                form.paymentsLeft, { v -> edit(LoanField.PAYMENTS_LEFT) { it.copy(paymentsLeft = v) } }, stringResource(R.string.loans_payments_left), 3, Modifier.fillMaxWidth().padding(top = 8.dp),
                problem = problems[LoanField.PAYMENTS_LEFT], placeholder = if (form.original.isNotBlank()) stringResource(R.string.loans_not_needed) else null,
            )
        }
        TextButton(onClick = { showProgress = !showProgress }) {
            Text(if (showProgress) stringResource(R.string.loans_hide_original) else stringResource(R.string.loans_show_original), color = Kassa.colors.ink2, style = KassaType.small)
        }
        if (showProgress) ProgressFields(
            form.original, { v -> edit(LoanField.ORIGINAL) { it.copy(original = v) } }, problems[LoanField.ORIGINAL],
            form.startDay, today, { d -> edit(null) { it.copy(startDay = d) } },
        )
    }

    PayFromPicker(payFromOptions, form.payFromId) { id -> edit(null) { it.copy(payFromId = id) } }
    val anchor = LoanDates.dayOfMonth(if (form.stage == LoanStage.NEW) form.firstDay else form.nextDay)
    DueDayField(form.dueDay, { v -> edit(LoanField.DUE_DAY) { it.copy(dueDay = v) } }, problems[LoanField.DUE_DAY], anchor)

    TextButton(onClick = { showMore = !showMore }) {
        Text(if (showMore) stringResource(R.string.loans_hide_details) else stringResource(R.string.loans_show_more), color = Kassa.colors.ink2, style = KassaType.small)
    }
    if (showMore) {
        CostFields(
            form.escrow, form.fee, form.earlyCharge, problems,
            onEscrow = { v -> edit(LoanField.ESCROW) { it.copy(escrow = v) } },
            onFee = { v -> edit(LoanField.FEE) { it.copy(fee = v) } },
            onEarlyCharge = { v -> edit(LoanField.EARLY_CHARGE) { it.copy(earlyCharge = v) } },
            // With a total that includes escrow, the escrow is typed beside the payment instead.
            showEscrow = form.paymentBasis != PaymentBasis.INCLUDES_ESCROW,
        )
    }

    Label(stringResource(R.string.loans_categories))
    LoanCategoryFields(
        word, categories, form.interestCategoryId, { id -> edit(null) { it.copy(interestCategoryId = id) } },
        form.countAllAsSpending, { on -> edit(null) { it.copy(countAllAsSpending = on) } },
        form.principalCategoryId, { id -> edit(null) { it.copy(principalCategoryId = id) } },
    )

    if (preview != null) LoanCalc(form, preview)
}

/** The live calculation under the form: what the figures come to, with a warning when the payment can't pay it off. */
@Composable
private fun LoanCalc(form: LoanForm, p: LoanPreview) {
    val word = form.word.lowercase()
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (form.stage == LoanStage.NEW) {
            p.payment?.let { Line(if (form.type == InterestType.INTEREST_ONLY) stringResource(R.string.loans_payment_interest_only, word) else stringResource(R.string.loans_payment), Money.format(it)) }
            p.totalInterest?.let { Line(stringResource(R.string.loans_total_interest, word), Money.format(it)) }
            p.payoffDay?.let { Line(stringResource(R.string.loans_last_payment), monthYear(it)) }
            p.equivalentRate?.let { Line(stringResource(R.string.loans_rate_on_balance), LoanRate.display(it)) }
            p.owedAtEnd?.let { Line(stringResource(R.string.loans_still_owed_end), Money.format(it)) }
            if (form.type == InterestType.DAILY) Text(stringResource(R.string.loans_daily_note), style = KassaType.small, color = Kassa.colors.ink2)
        } else {
            p.paymentsLeft?.let { Line(stringResource(R.string.loans_payments_left), it.toString()) }
            p.interestLeft?.let { Line(stringResource(R.string.loans_interest_still_to_pay, form.word), Money.format(it)) }
            p.payoffDay?.let { Line(stringResource(R.string.loans_paid_off), monthYear(it)) }
            if (form.type == InterestType.INTEREST_ONLY) form.toTerms(0).payment.let { Line(stringResource(R.string.loans_interest_each_month, form.word), Money.format(it)) }
        }
        p.warning?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f, fill = false))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/*
 * The pieces the setup form shares with Edit details, so a field is the same wherever the loan is entered or changed.
 */

/** Where payments start from. Tapping the picked account again clears it: nothing is chosen for you. */
@Composable
internal fun PayFromPicker(options: List<Account>, selected: Long?, onPick: (Long?) -> Unit) {
    if (options.isEmpty()) return
    // The shared account picker (see AccountPicker.kt), opened over the sheet. "No account" clears it: nothing is chosen for you.
    app.kassa.money.ui.common.AccountChoiceRow(
        stringResource(R.string.loans_pay_from), selected, payFromChoices(options), onPick, modifier = Modifier.padding(top = 10.dp), noneOption = stringResource(R.string.loans_no_account),
    )
}

/** The day of the month payments fall due; empty means [anchor], the day of the first or next payment. */
@Composable
internal fun DueDayField(value: String, onChange: (String) -> Unit, problem: String?, anchor: Int) {
    DigitsField(value, onChange, stringResource(R.string.loans_due_day_of_month), 2, Modifier.fillMaxWidth().padding(top = 8.dp), problem = problem, placeholder = anchor.toString())
}

/**
 * Under "Payment": does the amount typed include escrow? With "Total including escrow" the escrow is typed here and the
 * quiet lines say what is stored: principal and interest, and escrow. [LoanForm] does the working out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentBasisFields(form: LoanForm, problems: Map<LoanField, String>, edit: (LoanField?, (LoanForm) -> LoanForm) -> Unit) {
    Label(stringResource(R.string.loans_payment_is))
    FlowRow {
        PaymentBasis.entries.forEach { b -> Chip(b.label, form.paymentBasis == b, small = true) { edit(null) { it.copy(paymentBasis = b) } } }
    }
    if (form.paymentBasis == PaymentBasis.INCLUDES_ESCROW) {
        AmountField(
            form.escrow, { v -> edit(LoanField.ESCROW) { it.copy(escrow = v) } }, stringResource(R.string.loans_escrow_each_payment),
            Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[LoanField.ESCROW], placeholder = "0",
        )
        form.paymentSplit()?.let { PaymentSplitLines(it) }
    }
}

/** The two figures a total with escrow comes to. They go through [Money.format], so Hide balances masks them. */
@Composable
internal fun PaymentSplitLines(split: PaymentSplit) {
    Text(stringResource(R.string.loans_pi_amount, Money.format(split.principalAndInterest)), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(stringResource(R.string.loans_escrow_amount, Money.format(split.escrow)), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Escrow in each payment, the fee at the start, and the early repayment charge. */
@Composable
internal fun CostFields(
    escrow: String, fee: String, earlyCharge: String, problems: Map<LoanField, String>,
    onEscrow: (String) -> Unit, onFee: (String) -> Unit, onEarlyCharge: (String) -> Unit,
    showEscrow: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showEscrow) AmountField(escrow, onEscrow, stringResource(R.string.loans_escrow_each_payment), Modifier.weight(1f), problem = problems[LoanField.ESCROW], placeholder = "0")
        AmountField(fee, onFee, stringResource(R.string.loans_fee_at_start), Modifier.weight(1f), problem = problems[LoanField.FEE], placeholder = "0")
    }
    PercentField(
        earlyCharge, onEarlyCharge, stringResource(R.string.loans_early_repayment_charge), Modifier.fillMaxWidth().padding(top = 8.dp),
        problem = problems[LoanField.EARLY_CHARGE], placeholder = "0",
    )
    Text(stringResource(R.string.loans_early_charge_note), style = KassaType.small, color = Kassa.colors.ink2)
}

/**
 * The loan's interest category: the shared category row (see [CategoryChoiceRow]). Null means the default, "Loan interest",
 * or [loanDefaultId] when a payment is choosing and its loan names another.
 */
@Composable
internal fun InterestCategoryRow(word: String, categories: List<Category>, selectedId: Long?, onPick: (Long?) -> Unit, loanDefaultId: Long? = null) {
    CategoryChoiceRow(
        stringResource(R.string.loans_cost_category, word), selectedId, categories, onPick,
        defaultCategory = categories.firstOrNull { it.id == loanDefaultId } ?: LoanCategories.interestCategory(categories),
        defaultText = categories.firstOrNull { it.id == loanDefaultId }?.let { categoryPath(it, categories) } ?: LoanCategories.NAME,
    )
}

/**
 * Where the principal counts as spending: null means the same category as the interest. [interestId] is that category's id
 * (null for the default), so the row can say which it is.
 */
@Composable
internal fun PrincipalCategoryRow(categories: List<Category>, selectedId: Long?, interestId: Long?, onPick: (Long?) -> Unit) {
    val interest = categories.firstOrNull { it.id == interestId } ?: LoanCategories.interestCategory(categories)
    val same = stringResource(R.string.loans_same_as_interest, interest?.let { categoryPath(it, categories) } ?: LoanCategories.NAME)
    CategoryChoiceRow(stringResource(R.string.loans_principal_category), selectedId, categories, onPick, defaultCategory = interest, defaultText = same, topOption = same)
}

/** The loan's switch for counting the whole payment as spending, with the text under it. The same on the setup form and in Edit details. */
@Composable
internal fun SpendingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        stringResource(R.string.loans_count_whole_payment),
        stringResource(R.string.loans_spending_switch_body),
        on, onChange,
    )
}

/**
 * The loan's category rows and spending switch, from the mockup: the interest category, the switch, and, while it is on, the
 * principal category. Shared by the setup form and Edit details, so both read the same.
 */
@Composable
internal fun LoanCategoryFields(
    word: String, categories: List<Category>,
    interestId: Long?, onInterest: (Long?) -> Unit,
    counts: Boolean, onCounts: (Boolean) -> Unit,
    principalId: Long?, onPrincipal: (Long?) -> Unit,
) {
    InterestCategoryRow(word, categories, interestId, onInterest)
    if (counts) PrincipalCategoryRow(categories, principalId, interestId, onPrincipal)
    SpendingSwitch(counts, onCounts)
}

/** The original amount and start date, which only feed the progress bar on the loan page. */
@Composable
internal fun ProgressFields(
    original: String, onOriginal: (String) -> Unit, problem: String?, startDay: Int?, today: LocalDate, onStart: (Int?) -> Unit,
) {
    AmountField(original, onOriginal, stringResource(R.string.loans_original_amount), Modifier.fillMaxWidth(), problem = problem)
    Label(stringResource(R.string.loans_started))
    if (startDay == null) TextButton(onClick = { onStart(today.loanDay()) }) { Text(stringResource(R.string.loans_add_start_date), color = Kassa.colors.ink) }
    else {
        DateRow(dateOfLoanDay(startDay), today) { d -> onStart(d.loanDay()) }
        TextButton(onClick = { onStart(null) }) { Text(stringResource(R.string.loans_clear_start_date), color = Kassa.colors.ink2) }
    }
}

/**
 * "Add terms" on a loan account that is already there: the same form, already partway and filled with today's balance.
 * The loan's entries stay; if the balance is changed, the starting balance moves by the difference, as editing an
 * account does. [onSave] gets a `done` to call once saved, so the sheet stays open on a problem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanTermsSheet(
    account: Account,
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSave: (Account, LoanTerms, () -> Unit) -> Unit,
) {
    val current = ledger.accounts.balances[account.id] ?: 0
    var form by rememberSaveable(stateSaver = LoanFormSaver) { mutableStateOf(LoanForm.forAccount(owedToday = -current, today = ledger.today.loanDay(), payFromId = account.payFromId)) }
    var problems by remember { mutableStateOf(emptyMap<LoanField, String>()) }
    fun edit(field: LoanField?, change: (LoanForm) -> LoanForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        CategoryPickerHost(ledger.categories, Modifier.padding(horizontal = 20.dp).imePadding().navigationBarsPadding(), accounts = payFromChoices(ledger.accounts.accounts)) {
            Text(stringResource(R.string.loans_add_terms_to, account.name), style = KassaType.section, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.loans_add_terms_note), style = KassaType.small, color = Kassa.colors.ink2)
            Gap(4)
            LoanFields(form, problems, ::edit, ledger.today, ledger.accounts.accounts, ledger.categories)
            Gap(12)
            PrimaryButton(stringResource(R.string.loans_add_terms)) {
                problems = form.problems()
                if (problems.isEmpty()) onSave(account.copy(openingBalance = form.openingBalance(account, current)), form.toTerms(account.id), onDismiss)
            }
            Gap(24)
        }
    }
}

/**
 * Edit details on a loan: its name, where it's paid from, the due day, escrow, fee and early repayment charge, the
 * interest category, and the original amount and start date for the progress bar. The fields are the setup form's own
 * pieces; the checking is [LoanDetailsForm]'s. The rate, payment, term and interest type are dated, so they change
 * under Change terms. [onSave] gets a `done` to call once saved, so the sheet stays open on a problem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanDetailsSheet(
    account: Account,
    terms: LoanTerms,
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSave: (Account, LoanTerms, () -> Unit) -> Unit,
) {
    val owed = -(ledger.accounts.balances[account.id] ?: 0)
    var form by rememberSaveable(stateSaver = LoanDetailsFormSaver) { mutableStateOf(LoanDetailsForm.of(account.name, terms)) }
    var problems by remember { mutableStateOf(emptyMap<LoanField, String>()) }
    fun edit(field: LoanField?, change: (LoanDetailsForm) -> LoanDetailsForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        CategoryPickerHost(ledger.categories, Modifier.padding(horizontal = 20.dp).imePadding().navigationBarsPadding(), accounts = payFromChoices(ledger.accounts.accounts, except = account.id)) {
            Text(stringResource(R.string.loans_edit_details), style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(4)
            NameField(form.name, { v -> edit(LoanField.NAME) { it.copy(name = v) } }, stringResource(R.string.loans_name), Modifier.fillMaxWidth(), problem = problems[LoanField.NAME])
            PayFromPicker(ledger.accounts.accounts.filter { it.id != account.id }, form.payFromId) { id -> edit(null) { it.copy(payFromId = id) } }
            DueDayField(form.dueDay, { v -> edit(LoanField.DUE_DAY) { it.copy(dueDay = v) } }, problems[LoanField.DUE_DAY], terms.dueDay)
            Gap(8)
            CostFields(
                form.escrow, form.fee, form.earlyCharge, problems,
                onEscrow = { v -> edit(LoanField.ESCROW) { it.copy(escrow = v) } },
                onFee = { v -> edit(LoanField.FEE) { it.copy(fee = v) } },
                onEarlyCharge = { v -> edit(LoanField.EARLY_CHARGE) { it.copy(earlyCharge = v) } },
            )
            Label(stringResource(R.string.loans_categories))
            LoanCategoryFields(
                terms.interestWord, ledger.categories, form.interestCategoryId, { id -> edit(null) { it.copy(interestCategoryId = id) } },
                form.countAllAsSpending, { on -> edit(null) { it.copy(countAllAsSpending = on) } },
                form.principalCategoryId, { id -> edit(null) { it.copy(principalCategoryId = id) } },
            )
            Label(stringResource(R.string.loans_for_progress_bar))
            ProgressFields(
                form.original, { v -> edit(LoanField.ORIGINAL) { it.copy(original = v) } }, problems[LoanField.ORIGINAL],
                form.startDay, ledger.today, { d -> edit(null) { it.copy(startDay = d) } },
            )
            Gap(12)
            PrimaryButton(stringResource(R.string.loans_save_details)) {
                problems = form.problems(terms, owed)
                if (problems.isEmpty()) onSave(account.copy(name = form.name), form.applyTo(terms), onDismiss)
            }
            Gap(24)
        }
    }
}
