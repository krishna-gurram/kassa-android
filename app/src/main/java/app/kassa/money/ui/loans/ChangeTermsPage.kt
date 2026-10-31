package app.kassa.money.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.ChangeField
import app.kassa.money.domain.ChangeKind
import app.kassa.money.domain.ChangePreview
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.LenderKept
import app.kassa.money.domain.LoanChangeContext
import app.kassa.money.domain.LoanChangeForm
import app.kassa.money.domain.PaymentBasis
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.PercentField
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * Change a loan's terms from a date, from the prototype: what changed, the new values, and when. A rate change asks what
 * the lender did and shows the result live; a refinance is a new rate, payment and term from a date. Payments before the
 * date keep the split they had. All the checking and working out is [LoanChangeForm]'s; this lays it out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChangeTermsPage(
    ledger: Ledger,
    account: Account,
    terms: LoanTerms,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: (LoanTermChange) -> Unit,
) {
    val word = terms.interestWord
    val owed = -(ledger.accounts.balances[account.id] ?: 0)
    val changes = ledger.loans.changesFor(account.id)
    val payments = ledger.loans.paymentsFor(account.id)
    val ctx = remember(terms, changes, payments, owed) { LoanChangeContext(terms, changes, payments, owed) }
    var form by remember { mutableStateOf(LoanChangeForm.starting(ledger.today.loanDay())) }
    var problems by remember { mutableStateOf(emptyMap<ChangeField, String>()) }
    val preview = remember(form, ctx) { form.preview(ctx) }
    fun edit(field: ChangeField?, change: (LoanChangeForm) -> LoanChangeForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PageTop("Change terms", onBack)
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Label("What changed")
            FlowRow {
                ChangeKind.entries.forEach { k -> Chip(k.label, form.kind == k, small = true) { edit(null) { it.copy(kind = k) } } }
            }

            when (form.kind) {
                ChangeKind.RATE -> {
                    PercentField(
                        form.rate, { v -> edit(ChangeField.RATE) { it.copy(rate = v) } }, "New ${word.lowercase()} rate a year",
                        Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[ChangeField.RATE],
                    )
                    Label("Your lender")
                    FlowRow {
                        Chip("Kept the payment, term changes", form.kept == LenderKept.PAYMENT, small = true) { edit(null) { it.copy(kept = LenderKept.PAYMENT) } }
                        Chip("Kept the term, payment changes", form.kept == LenderKept.TERM, small = true) { edit(null) { it.copy(kept = LenderKept.TERM) } }
                    }
                }
                ChangeKind.PAYMENT -> {
                    PaymentBasisChips(form, ctx, edit)
                    AmountField(
                        form.payment, { v -> edit(ChangeField.PAYMENT) { it.copy(payment = v) } }, "New payment",
                        Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[ChangeField.PAYMENT],
                        placeholder = if (ctx.terms.effective(ctx.changes, form.day).type == InterestType.INTEREST_ONLY) "Just the ${word.lowercase()}" else null,
                    )
                    form.paymentSplit(ctx)?.let { PaymentSplitLines(it) }
                }
                ChangeKind.TYPE -> {
                    Label("New interest type")
                    FlowRow {
                        InterestType.entries.forEach { t -> Chip(t.label, form.type == t, small = true) { edit(ChangeField.TYPE) { it.copy(type = t) } } }
                    }
                    problems[ChangeField.TYPE]?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
                    PaymentBasisChips(form, ctx, edit)
                    AmountField(
                        form.payment, { v -> edit(ChangeField.PAYMENT) { it.copy(payment = v) } }, "New payment",
                        Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[ChangeField.PAYMENT],
                        placeholder = form.suggestedPayment(ctx)?.let { Money.toInput(it) } ?: "Worked out from the term",
                    )
                    form.paymentSplit(ctx)?.let { PaymentSplitLines(it) }
                    DigitsField(
                        form.termMonths, { v -> edit(ChangeField.TERM) { it.copy(termMonths = v) } }, "Term in months", 3,
                        Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[ChangeField.TERM],
                    )
                }
                ChangeKind.REFINANCE -> {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PercentField(
                            form.rate, { v -> edit(ChangeField.RATE) { it.copy(rate = v) } }, "New ${word.lowercase()} rate",
                            Modifier.weight(1f), problem = problems[ChangeField.RATE],
                        )
                        DigitsField(
                            form.termMonths, { v -> edit(ChangeField.TERM) { it.copy(termMonths = v) } }, "Term in months", 3,
                            Modifier.weight(1f), problem = problems[ChangeField.TERM],
                        )
                    }
                    PaymentBasisChips(form, ctx, edit)
                    AmountField(
                        form.payment, { v -> edit(ChangeField.PAYMENT) { it.copy(payment = v) } }, "New payment",
                        Modifier.fillMaxWidth().padding(top = 8.dp), problem = problems[ChangeField.PAYMENT],
                        placeholder = form.suggestedPayment(ctx)?.let { Money.toInput(it) } ?: "Worked out for you",
                    )
                    form.paymentSplit(ctx)?.let { PaymentSplitLines(it) }
                }
            }

            Label("From")
            DateRow(dateOfLoanDay(form.day), ledger.today, allowFuture = true) { d -> edit(ChangeField.DATE) { it.copy(day = d.loanDay()) } }
            problems[ChangeField.DATE]?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }

            if (preview != null) ChangePreviewPanel(preview, form, word)

            Gap(12)
            PrimaryButton("Save change") {
                problems = form.problems(ctx)
                if (problems.isEmpty()) onSave(form.change(ctx))
            }
            Gap(24)
        }
    }
}

/**
 * Under "New payment": does the amount include the loan's escrow? A change has no escrow of its own, so the choice is
 * offered only when the loan has some; the escrow taken off the total is the loan's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentBasisChips(form: LoanChangeForm, ctx: LoanChangeContext, edit: (ChangeField?, (LoanChangeForm) -> LoanChangeForm) -> Unit) {
    if (ctx.terms.escrow <= 0) return
    Label("Payment is")
    FlowRow {
        PaymentBasis.entries.forEach { b -> Chip(b.label, form.paymentBasis == b, small = true) { edit(null) { it.copy(paymentBasis = b) } } }
    }
}

/** What the change does, live, from [LoanChangeForm.preview]: the payment, the payoff month and the interest still to pay. */
@Composable
private fun ChangePreviewPanel(p: ChangePreview, form: LoanChangeForm, word: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Line(if (p.payment == p.paymentBefore) "Payment stays" else "New payment", Money.format(p.payment))
        if (p.payment != p.paymentBefore) Line("Change", BalanceCorrection.describe(p.payment - p.paymentBefore))
        if (form.kind == ChangeKind.RATE && form.kept == LenderKept.TERM) p.paymentsLeft?.let { Line("Payments left", it.toString()) }
        Line("Paid off", p.payoffDay?.let { monthYear(it) } ?: "—")
        Line("$word remaining", p.interestLeft?.let { Money.format(it) } ?: "—")
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
