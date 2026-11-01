package app.kassa.money.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.data.repo.LoanPaymentDetail
import app.kassa.money.data.repo.LoanPaymentDraft
import app.kassa.money.domain.Account
import app.kassa.money.domain.LoanCategories
import app.kassa.money.domain.LoanEngine
import app.kassa.money.domain.LoanPaymentContext
import app.kassa.money.domain.LoanPaymentField
import app.kassa.money.domain.LoanPaymentForm
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.SplitEdit
import app.kassa.money.domain.Money
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.CategoryChoiceRow
import app.kassa.money.ui.common.CategoryPickerHost
import app.kassa.money.ui.common.categoryPath
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.momentOn
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.settings.SwitchRow
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Keeps what's typed in the payment sheet across the activity being recreated (folding, rotating), as plain strings. */
private val LoanPaymentFormSaver: Saver<LoanPaymentForm, Any> = listSaver(
    save = { f ->
        listOf(
            f.amount, f.day.toString(), f.principal, f.interest, f.edit.name, f.fromId?.toString().orEmpty(), f.categoryId?.toString().orEmpty(),
            f.estimate?.toString().orEmpty(), f.principalCategoryId?.toString().orEmpty(), f.countOverride?.toString().orEmpty(),
        )
    },
    restore = { v ->
        if (v.size < 10) null
        else v[1].toIntOrNull()?.let { day ->
            LoanPaymentForm(
                amount = v[0], day = day, principal = v[2], interest = v[3],
                edit = SplitEdit.entries.firstOrNull { it.name == v[4] } ?: SplitEdit.NONE,
                fromId = v[5].toLongOrNull(), categoryId = v[6].toLongOrNull(), estimate = v[7].toLongOrNull(),
                principalCategoryId = v[8].toLongOrNull(), countOverride = v[9].toBooleanStrictOrNull(),
            )
        }
    },
)

/**
 * Record a payment on a loan, or open one already recorded. Mirrors Pay a card: amount, date, the account it's paid from,
 * and then what the payment is made of. Kassa suggests the principal and interest from the loan's terms and the date; typing
 * in one makes the other follow, so they always add up. All of that is [LoanPaymentForm]'s; this lays it out.
 *
 * Nothing posts by itself: the amount starts as the scheduled payment only because the person opened this, and the
 * account it's paid from starts empty unless the loan names one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LoanPaymentSheet(
    loan: Account,
    terms: LoanTerms,
    ledger: Ledger,
    editing: LoanPaymentDetail?,
    onDismiss: () -> Unit,
    onSave: (LoanPaymentDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val word = terms.interestWord
    // What is owed before this payment: today's balance, with an edited payment's own principal put back.
    val owedNow = -(ledger.accounts.balances[loan.id] ?: 0)
    val ctx = remember(ledger.loans, owedNow, editing) {
        LoanPaymentContext(
            terms, ledger.loans.changesFor(loan.id), ledger.loans.paymentsFor(loan.id),
            balance = (owedNow + (editing?.principal ?: 0)).coerceAtLeast(0), editing = editing?.payment,
        )
    }
    val sources = remember(ledger.accounts) { payFromChoices(ledger.accounts.accounts, except = loan.id) }
    var form by rememberSaveable(stateSaver = LoanPaymentFormSaver) {
        mutableStateOf(
            if (editing != null) LoanPaymentForm.of(editing.payment, editing.principal, editing.interest, editing.fromId, editing.categoryId)
            else {
                val today = ledger.today.loanDay()
                val start = LoanPaymentForm.starting(today, terms.payFromId?.takeIf { id -> sources.any { it.id == id } })
                val due = LoanEngine.amountDue(terms, ctx.changes, ctx.balance, ctx.lastDay(today), today)
                if (due > 0) start.withAmount(Money.toInput(due), ctx) else start
            }
        )
    }
    var problems by remember { mutableStateOf(emptyMap<LoanPaymentField, String>()) }
    // The escrow category: the one on this payment when it is opened again, else the one the loan's last escrow went to.
    var escrowCategoryId by rememberSaveable {
        mutableStateOf(
            (if (editing != null) editing.escrowCategoryId else ledger.loans.escrowCategory[loan.id])?.takeIf { id -> ledger.categoryById[id]?.hidden == false }
        )
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val suggestion = form.suggestion(ctx)
    val parts = form.parts(ctx)
    val principalColor = Kassa.colors.ink
    val interestColor = interestColor(ledger, form.categoryId ?: terms.interestCategoryId)
    val escrowColor = Kassa.colors.ink3

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        CategoryPickerHost(ledger.categories, Modifier.padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 20.dp).widthIn(max = 640.dp), accounts = sources) {
            Text(
                if (editing == null) stringResource(R.string.loans_pay_account, loan.name) else stringResource(R.string.loans_account_payment, loan.name), style = KassaType.section, color = Kassa.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Gap(8)
            AmountField(
                form.amount, { v -> form = form.withAmount(v, ctx); problems = problems - LoanPaymentField.AMOUNT }, stringResource(R.string.loans_amount), Modifier.fillMaxWidth(),
                problem = problems[LoanPaymentField.AMOUNT],
            )
            Label(stringResource(R.string.loans_date))
            DateRow(dateOfLoanDay(form.day), ledger.today) { d -> form = form.withDay(d.loanDay(), ctx) }
            // The shared account picker; "No account" clears it: nothing is chosen for you.
            app.kassa.money.ui.common.AccountChoiceRow(
                stringResource(R.string.loans_from_account), form.fromId, sources,
                { id -> form = form.copy(fromId = id); problems = problems - LoanPaymentField.FROM },
                modifier = Modifier.padding(top = 10.dp), recentIds = app.kassa.money.ui.common.recentAccountIds(ledger), noneOption = stringResource(R.string.loans_no_account),
            )
            problems[LoanPaymentField.FROM]?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }

            Gap(8)
            SplitBar(parts.principal, parts.interest, parts.escrow + parts.charge, principalColor, interestColor, escrowColor)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(stringResource(R.string.loans_principal), principalColor)
                Legend(word, interestColor)
                if (parts.escrow + parts.charge > 0) Legend(if (parts.escrow > 0) stringResource(R.string.loans_escrow) else stringResource(R.string.loans_charge), escrowColor)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(
                    form.principal, { v -> form = form.withPrincipal(v, ctx); problems = problems - LoanPaymentField.PRINCIPAL }, stringResource(R.string.loans_principal), Modifier.weight(1f),
                    problem = problems[LoanPaymentField.PRINCIPAL],
                )
                AmountField(form.interest, { v -> form = form.withInterest(v, ctx) }, word, Modifier.weight(1f))
            }
            Text(form.hint(ctx), style = KassaType.small, color = Kassa.colors.ink2)
            Label(stringResource(R.string.loans_categories))
            InterestCategoryRow(word, ledger.categories, form.categoryId, { id -> form = form.copy(categoryId = id) }, loanDefaultId = terms.interestCategoryId)
            val counts = form.counts(ctx)
            if (counts) {
                // Null means the loan's principal category; failing that, wherever this payment's interest goes.
                val loanPrincipal = terms.principalCategoryId?.let { ledger.categoryById[it] }
                val interestId = form.categoryId ?: terms.interestCategoryId
                if (loanPrincipal != null) {
                    val same = stringResource(R.string.loans_same_as_loan, categoryPath(loanPrincipal, ledger.categories))
                    CategoryChoiceRow(
                        stringResource(R.string.loans_principal_category), form.principalCategoryId, ledger.categories, { id -> form = form.copy(principalCategoryId = id) },
                        defaultCategory = loanPrincipal, defaultText = same, topOption = same,
                    )
                } else PrincipalCategoryRow(ledger.categories, form.principalCategoryId, interestId) { id -> form = form.copy(principalCategoryId = id) }
            }
            if (parts.escrow + parts.charge > 0) {
                val costWord = if (parts.escrow > 0) stringResource(R.string.loans_escrow) else stringResource(R.string.loans_charge)
                CategoryChoiceRow(
                    stringResource(R.string.loans_cost_category, costWord), escrowCategoryId, ledger.categories, { id -> escrowCategoryId = id },
                    defaultCategory = LoanCategories.escrowCategory(ledger.categories), defaultText = LoanCategories.ESCROW_NAME,
                )
            }
            // One payment only: choosing what the loan already does clears it (see LoanPaymentForm.withCounts).
            SwitchRow(
                stringResource(R.string.loans_count_whole_payment),
                if (terms.countAllAsSpending) stringResource(R.string.loans_count_on_for_loan) else stringResource(R.string.loans_count_just_this),
                counts,
            ) { on -> form = form.withCounts(on, ctx) }

            // Scheduled, extra, any charge (shown before saving), escrow when the loan has it, and what is left.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Line(stringResource(R.string.loans_scheduled_payment), Money.format(form.scheduled(ctx)))
                Line(stringResource(R.string.loans_extra_to_principal), Money.format(suggestion.extra))
                if (terms.earlyCharge > 0 || parts.charge > 0) Line(stringResource(R.string.loans_early_repayment_charge), Money.format(parts.charge))
                if (terms.escrow > 0) Line(stringResource(R.string.loans_escrow), Money.format(parts.escrow))
                Line(stringResource(R.string.loans_balance_after), Money.format(form.balanceAfter(ctx).coerceAtLeast(0)))
                Line(stringResource(R.string.loans_counts_as_spending), Money.format(form.countedSpending(ctx)), strong = true)
            }
            Gap(12)
            PrimaryButton(if (editing == null) stringResource(R.string.loans_record_payment) else stringResource(R.string.loans_save_payment)) {
                problems = form.problems(ctx)
                if (problems.isEmpty()) onSave(
                    LoanPaymentDraft(
                        id = editing?.payment?.id, accountId = loan.id, fromId = form.fromId, day = form.day,
                        epochMillis = momentOn(dateOfLoanDay(form.day), ledger.today, ledger.zone),
                        amount = parts.amount, principal = parts.principal, interest = parts.interest, escrow = parts.escrow, charge = parts.charge,
                        categoryId = form.categoryId, escrowCategoryId = escrowCategoryId, edited = parts.edited, estimate = parts.estimate,
                        countAllAsSpending = form.countOverride, principalCategoryId = form.principalCategoryId,
                    )
                )
            }
            if (onDelete != null) SecondaryButton(stringResource(R.string.loans_delete_payment), { confirmDelete = true }, Modifier.padding(top = 8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.loans_cancel), color = Kassa.colors.ink2) }
        }
    }

    if (confirmDelete && onDelete != null) ConfirmDialog(
        title = stringResource(R.string.loans_delete_payment_title),
        body = stringResource(R.string.loans_delete_payment_body, word.lowercase()),
        confirm = stringResource(R.string.loans_delete), onConfirm = onDelete, onDismiss = { confirmDelete = false },
    )
}

/** The color of the interest part: the interest category's own, so it reads the same here as in Insights. */
@Composable
private fun interestColor(ledger: Ledger, categoryId: Long?): Color {
    val id = categoryId ?: LoanCategories.interestCategory(ledger.categories)?.id
    val category = id?.let { ledger.categoryById[it] }
    return category?.let { Color(it.color) } ?: Kassa.colors.ink2
}

/** One bar for the whole payment, cut into principal, interest and escrow in proportion. Nothing to read; the figures are below. */
@Composable
private fun SplitBar(principal: Long, interest: Long, other: Long, principalColor: Color, interestColor: Color, otherColor: Color) {
    val total = principal + interest + other
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Kassa.colors.chip)) {
        if (total > 0) listOf(principal to principalColor, interest to interestColor, other to otherColor).forEach { (part, color) ->
            if (part > 0) Box(Modifier.weight(part.toFloat() / total).height(12.dp).background(color))
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun Line(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (strong) KassaType.bodyStrong else KassaType.body, color = if (strong) Kassa.colors.ink else Kassa.colors.ink2, modifier = Modifier.weight(1f, fill = false))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
