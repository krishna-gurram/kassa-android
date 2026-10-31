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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Category
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.IslamicContract
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanDates
import app.kassa.money.domain.LoanField
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanKind
import app.kassa.money.domain.LoanPreview
import app.kassa.money.domain.LoanRate
import app.kassa.money.domain.LoanStage
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.accounts.Choices
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PercentField
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Loan screens keep dates as epoch days (the rules know nothing of java.time); these convert at the edge. */
internal fun LocalDate.loanDay(): Int = toEpochDay().toInt()
internal fun dateOfLoanDay(day: Int): LocalDate = LocalDate.ofEpochDay(day.toLong())

/** "Nov 2030", in the phone's language. */
internal fun monthYear(day: Int): String = dateOfLoanDay(day).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

/** The accounts a payment can come from, A to Z. */
internal fun payFromChoices(accounts: List<Account>, except: Long = 0): List<Account> =
    accounts.filter { !it.archived && it.id != except && it.type in PAY_FROM_TYPES }.sortedWith(Names.order { it.name })

private val PAY_FROM_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)

/** Stands for "no category chosen" in the category list: the loan's own default. */
private const val DEFAULT_CATEGORY = -1L

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
    var pickingCategory by remember { mutableStateOf(false) }

    if (!form.stageLocked) {
        Label("This loan is")
        FlowRow {
            Chip("Starting now", form.stage == LoanStage.NEW, small = true) { edit(null) { it.copy(stage = LoanStage.NEW) } }
            Chip("Already partway", form.stage == LoanStage.PARTWAY, small = true) { edit(null) { it.copy(stage = LoanStage.PARTWAY) } }
        }
    }
    Label("How interest is charged")
    FlowRow { LoanKind.entries.forEach { k -> Chip(k.label, form.kind == k, small = true) { edit(null) { it.copy(kind = k) } } } }
    Text(
        if (form.kind == LoanKind.ISLAMIC) "UAE and other Islamic finance. Same maths, shown as profit." else form.type.hint,
        style = KassaType.small, color = Kassa.colors.ink2,
    )
    if (form.kind == LoanKind.ISLAMIC) {
        Label("Contract")
        FlowRow { IslamicContract.entries.forEach { c -> Chip(c.label, form.contract == c, small = true) { edit(null) { it.copy(contract = c) } } } }
    }

    val rateLabel = "$word rate a year" + if (form.type == InterestType.FLAT) " (flat)" else ""
    if (form.stage == LoanStage.NEW) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(form.amount, { v -> edit(LoanField.AMOUNT) { it.copy(amount = v) } }, "Amount borrowed", Modifier.weight(1f), problem = problems[LoanField.AMOUNT])
            PercentField(form.rate, { v -> edit(LoanField.RATE) { it.copy(rate = v) } }, rateLabel, Modifier.weight(1f), problem = problems[LoanField.RATE])
        }
        DigitsField(form.termMonths, { v -> edit(LoanField.TERM) { it.copy(termMonths = v) } }, "Term in months", 3, Modifier.fillMaxWidth(), problem = problems[LoanField.TERM])
        Label("First payment")
        DateRow(dateOfLoanDay(form.firstDay), today, allowFuture = true) { d -> edit(null) { it.copy(firstDay = d.loanDay()) } }
        AmountField(
            form.payment, { v -> edit(LoanField.PAYMENT) { it.copy(payment = v) } }, "Payment", Modifier.fillMaxWidth().padding(top = 8.dp),
            problem = problems[LoanField.PAYMENT], placeholder = preview?.payment?.let { Money.toInput(it) } ?: "Worked out for you",
        )
        Text("Kassa works out the payment. Type your lender's figure to use that instead.", style = KassaType.small, color = Kassa.colors.ink2)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(form.balance, { v -> edit(LoanField.BALANCE) { it.copy(balance = v) } }, "Balance today", Modifier.weight(1f), problem = problems[LoanField.BALANCE])
            PercentField(form.rate, { v -> edit(LoanField.RATE) { it.copy(rate = v) } }, rateLabel, Modifier.weight(1f), problem = problems[LoanField.RATE])
        }
        AmountField(
            form.payment, { v -> edit(LoanField.PAYMENT) { it.copy(payment = v) } }, "Payment", Modifier.fillMaxWidth(),
            problem = problems[LoanField.PAYMENT], placeholder = if (form.type == InterestType.INTEREST_ONLY) "Just the ${word.lowercase()}" else null,
        )
        Label("Next payment")
        DateRow(dateOfLoanDay(form.nextDay), today, allowFuture = true) { d -> edit(null) { it.copy(nextDay = d.loanDay()) } }
        // A flat loan's interest in each payment depends on what it started as; payments left says that.
        if (form.type == InterestType.FLAT) {
            DigitsField(
                form.paymentsLeft, { v -> edit(LoanField.PAYMENTS_LEFT) { it.copy(paymentsLeft = v) } }, "Payments left", 3, Modifier.fillMaxWidth().padding(top = 8.dp),
                problem = problems[LoanField.PAYMENTS_LEFT], placeholder = if (form.original.isNotBlank()) "Not needed" else null,
            )
        }
        TextButton(onClick = { showProgress = !showProgress }) {
            Text(if (showProgress) "Hide original amount and start" else "Optional: original amount and start, for progress", color = Kassa.colors.ink2, style = KassaType.small)
        }
        if (showProgress) {
            AmountField(form.original, { v -> edit(LoanField.ORIGINAL) { it.copy(original = v) } }, "Original amount", Modifier.fillMaxWidth(), problem = problems[LoanField.ORIGINAL])
            Label("Started")
            val start = form.startDay
            if (start == null) TextButton(onClick = { edit(null) { it.copy(startDay = today.loanDay()) } }) { Text("Add the start date", color = Kassa.colors.ink) }
            else {
                DateRow(dateOfLoanDay(start), today) { d -> edit(null) { it.copy(startDay = d.loanDay()) } }
                TextButton(onClick = { edit(null) { it.copy(startDay = null) } }) { Text("Clear the start date", color = Kassa.colors.ink2) }
            }
        }
    }

    if (payFromOptions.isNotEmpty()) {
        Label("Pay from")
        FlowRow {
            payFromChoices(payFromOptions).forEach { a ->
                // Tapping the picked account again clears it: nothing is chosen for you.
                Chip(a.name, form.payFromId == a.id, small = true) { edit(null) { it.copy(payFromId = if (form.payFromId == a.id) null else a.id) } }
            }
        }
    }
    val anchor = LoanDates.dayOfMonth(if (form.stage == LoanStage.NEW) form.firstDay else form.nextDay)
    DigitsField(
        form.dueDay, { v -> edit(LoanField.DUE_DAY) { it.copy(dueDay = v) } }, "Due day of the month", 2, Modifier.fillMaxWidth().padding(top = 8.dp),
        problem = problems[LoanField.DUE_DAY], placeholder = anchor.toString(),
    )

    TextButton(onClick = { showMore = !showMore }) {
        Text(if (showMore) "Hide details" else "More: escrow, fees, early repayment charge", color = Kassa.colors.ink2, style = KassaType.small)
    }
    if (showMore) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(form.escrow, { v -> edit(LoanField.ESCROW) { it.copy(escrow = v) } }, "Escrow in each payment", Modifier.weight(1f), problem = problems[LoanField.ESCROW], placeholder = "0")
            AmountField(form.fee, { v -> edit(LoanField.FEE) { it.copy(fee = v) } }, "Fee at start", Modifier.weight(1f), problem = problems[LoanField.FEE], placeholder = "0")
        }
        PercentField(
            form.earlyCharge, { v -> edit(LoanField.EARLY_CHARGE) { it.copy(earlyCharge = v) } }, "Early repayment charge", Modifier.fillMaxWidth().padding(top = 8.dp),
            problem = problems[LoanField.EARLY_CHARGE], placeholder = "0",
        )
        Text("Charged on extra or early payments. Shown before you record one.", style = KassaType.small, color = Kassa.colors.ink2)
        val chosen = categories.firstOrNull { it.id == form.interestCategoryId }
        TextButton(onClick = { pickingCategory = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$word category: ${chosen?.name ?: LoanCategories.NAME}", color = Kassa.colors.ink, style = KassaType.bodyStrong,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("Change", color = Kassa.colors.ink2, style = KassaType.small)
        }
    }

    if (preview != null) LoanCalc(form, preview)

    if (pickingCategory) Choices(
        "$word category",
        listOf(DEFAULT_CATEGORY to "${LoanCategories.NAME} (default)") + LoanCategories.choices(categories),
        form.interestCategoryId ?: DEFAULT_CATEGORY,
        onPick = { id -> edit(null) { it.copy(interestCategoryId = id.takeIf { v -> v != DEFAULT_CATEGORY }) }; pickingCategory = false },
        onDismiss = { pickingCategory = false },
    )
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
            p.payment?.let { Line(if (form.type == InterestType.INTEREST_ONLY) "Payment ($word only)" else "Payment", Money.format(it)) }
            p.totalInterest?.let { Line("Total $word", Money.format(it)) }
            p.payoffDay?.let { Line("Last payment", monthYear(it)) }
            p.equivalentRate?.let { Line("Rate on the balance", LoanRate.display(it)) }
            p.owedAtEnd?.let { Line("Still owed at the end", Money.format(it)) }
            if (form.type == InterestType.DAILY) Text("Worked out as 30-day months; each real payment counts its actual days.", style = KassaType.small, color = Kassa.colors.ink2)
        } else {
            p.paymentsLeft?.let { Line("Payments left", it.toString()) }
            p.interestLeft?.let { Line("${form.word} still to pay", Money.format(it)) }
            p.payoffDay?.let { Line("Paid off", monthYear(it)) }
            if (form.type == InterestType.INTEREST_ONLY) form.toTerms(0).payment.let { Line("${form.word} each month", Money.format(it)) }
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
    var form by remember { mutableStateOf(LoanForm.forAccount(owedToday = -current, today = ledger.today.loanDay(), payFromId = account.payFromId)) }
    var problems by remember { mutableStateOf(emptyMap<LoanField, String>()) }
    fun edit(field: LoanField?, change: (LoanForm) -> LoanForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Add terms to ${account.name}", style = KassaType.section, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Its entries and balance stay as they are.", style = KassaType.small, color = Kassa.colors.ink2)
            Gap(4)
            LoanFields(form, problems, ::edit, ledger.today, ledger.accounts.accounts, ledger.categories)
            Gap(12)
            PrimaryButton("Add terms") {
                problems = form.problems()
                if (problems.isEmpty()) onSave(account.copy(openingBalance = form.openingBalance(account, current)), form.toTerms(account.id), onDismiss)
            }
            Gap(24)
        }
    }
}
