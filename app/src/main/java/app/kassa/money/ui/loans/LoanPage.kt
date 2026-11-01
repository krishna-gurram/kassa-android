package app.kassa.money.ui.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.LoanRate
import app.kassa.money.domain.LoanRow
import app.kassa.money.domain.LoanSummary
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.accounts.TwoBoxes
import app.kassa.money.ui.cards.cardTint
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.today.BigMoney
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How many forecast rows the loan page shows before "Show full schedule". */
private const val NEXT_ROWS = 6

/** "Nov 1, 2026", in the phone's language. */
internal fun mediumDate(day: Int): String = dateOfLoanDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** The loan's page figures, worked out from its terms, changes, payments and balance (see [LoanSummary]). */
@Composable
internal fun rememberLoanSummary(ledger: Ledger, account: Account, terms: LoanTerms, balance: Long): LoanSummary {
    val changes = ledger.loans.changesFor(account.id)
    val payments = ledger.loans.paymentsFor(account.id)
    val interestPaid = ledger.loans.interestPaid[account.id] ?: 0
    return remember(terms, changes, payments, balance, interestPaid) { LoanSummary.of(terms, changes, payments, -balance, interestPaid) }
}

/**
 * The top of a loan's page, from the prototype: what is outstanding, a progress bar only when the original amount is
 * known, then the payment, the payoff month, and interest paid and still to pay. Figures speak; nothing is explained
 * except the one warning that matters, a payment that can't pay off the loan.
 */
@Composable
internal fun LoanHero(summary: LoanSummary, terms: LoanTerms, balance: Long) {
    val word = terms.interestWord
    // What is owed is against you, so the hero is orange while something is owed (grey while hidden).
    RaisedPanel(tone = if (balance < 0) app.kassa.money.ui.common.HeroTone.ORANGE else app.kassa.money.ui.common.HeroTone.GREEN) {
        Text(stringResource(R.string.loans_outstanding_balance), style = KassaType.tiny, color = Kassa.colors.ink2)
        // What is owed, never with a minus; a credit shows green, like a card.
        BigMoney(Math.abs(balance), sizeSp = 36f, color = cardTint(-balance))
        val fraction = summary.fractionRepaid
        val percent = summary.percentRepaid
        val original = summary.originalAmount
        // Hidden: the bar and percentage would give the balance away, so they wait.
        if (fraction != null && percent != null && original != null && !Money.isHidden) {
            Gap(10)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Kassa.colors.line)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Kassa.colors.positive))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.loans_percent_paid_off, NumberFormat.getPercentInstance(Locale.getDefault()).format(percent / 100.0)),
                    style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(stringResource(R.string.loans_of_amount, Money.format(original)), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
            }
        }
        val paying = summary.owed > 0
        TwoBoxes(
            stringResource(R.string.loans_payment) to (if (paying) Money.format(summary.payment) else stringResource(R.string.loans_none_due)),
            stringResource(R.string.loans_paid_off) to (summary.payoffDay?.let { monthYear(it) } ?: "—"),
            aNote = if (paying) stringResource(R.string.loans_due_date, mediumDate(summary.nextPaymentDay)) else null,
        )
        TwoBoxes(
            stringResource(R.string.loans_interest_paid_so_far, word) to Money.format(summary.interestPaid),
            stringResource(R.string.loans_interest_remaining, word) to (summary.interestLeft?.let { Money.format(it) } ?: "—"),
        )
        if (paying && !summary.outlook.coversInterest) Text(
            stringResource(R.string.loans_payment_doesnt_cover, word.lowercase()),
            style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** What a loan's page needs to do beyond showing figures. */
internal class LoanPageActions(
    val onPay: () -> Unit,
    val onChangeTerms: () -> Unit,
    val onEditDetails: () -> Unit,
    val onCorrectBalance: () -> Unit,
    val onRemoveChange: (Long) -> Unit,
)

/**
 * The loan page's sections under [LoanHero]: the payment schedule (the next rows, or all of them), the terms with their
 * dated changes, and the four buttons. Rows are items of the page's own list so a long schedule stays lazy.
 */
internal fun LazyListScope.loanSections(
    ledger: Ledger,
    account: Account,
    terms: LoanTerms,
    summary: LoanSummary,
    showAll: Boolean,
    onShowAll: (Boolean) -> Unit,
    actions: LoanPageActions,
) {
    val word = terms.interestWord
    val rows = if (showAll) summary.rows else summary.rows.take(NEXT_ROWS)
    if (summary.rows.isNotEmpty()) {
        item(key = "loan-schedule-head") {
            Column(Modifier.widthIn(max = 720.dp)) {
                SectionHeader(stringResource(R.string.loans_payment_schedule))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
                    ScheduleCell(stringResource(R.string.loans_col_date), Modifier.weight(1.1f), header = true, end = false)
                    ScheduleCell(stringResource(R.string.loans_col_principal), Modifier.weight(1f), header = true)
                    ScheduleCell(word, Modifier.weight(1f), header = true)
                    ScheduleCell(stringResource(R.string.loans_col_balance), Modifier.weight(1f), header = true)
                }
                HorizontalDivider(color = Kassa.colors.line)
            }
        }
        items(rows, key = { "loan-row-${it.day}" }) { r -> ScheduleRow(r) }
        if (summary.rows.size > NEXT_ROWS) item(key = "loan-schedule-more") {
            Column(Modifier.widthIn(max = 720.dp)) {
                TextButton(onClick = { onShowAll(!showAll) }) {
                    Text(if (showAll) stringResource(R.string.loans_show_fewer) else stringResource(R.string.loans_show_full_schedule), color = Kassa.colors.ink)
                }
            }
        }
    }
    item(key = "loan-terms") { Column(Modifier.widthIn(max = 720.dp)) { LoanTermsSection(ledger, terms, word, actions.onRemoveChange) } }
    item(key = "loan-buttons") {
        Column(Modifier.widthIn(max = 720.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (summary.owed > 0) PrimaryButton(stringResource(R.string.loans_record_a_payment), actions.onPay)
            SecondaryButton(stringResource(R.string.loans_change_terms_title), actions.onChangeTerms)
            SecondaryButton(stringResource(R.string.loans_edit_details), actions.onEditDetails)
            SecondaryButton(stringResource(R.string.loans_correct_balance), actions.onCorrectBalance)
        }
    }
}

@Composable
private fun ScheduleRow(r: LoanRow) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScheduleCell(monthYear(r.day), Modifier.weight(1.1f), end = false)
            ScheduleCell(Money.format(r.principal), Modifier.weight(1f))
            ScheduleCell(Money.format(r.interest), Modifier.weight(1f))
            ScheduleCell(Money.format(r.balance), Modifier.weight(1f))
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
}

@Composable
private fun ScheduleCell(text: String, modifier: Modifier, header: Boolean = false, end: Boolean = true) {
    Text(
        text, modifier = modifier.padding(horizontal = 2.dp),
        style = if (header) KassaType.tiny else KassaType.small, color = if (header) Kassa.colors.ink2 else Kassa.colors.ink,
        textAlign = if (end) TextAlign.End else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Type, rate, payment, term and due day as they stand today, then each dated change; tapping a change offers to remove it. */
@Composable
private fun LoanTermsSection(ledger: Ledger, terms: LoanTerms, word: String, onRemoveChange: (Long) -> Unit) {
    val changes = ledger.loans.changesFor(terms.accountId).sortedWith(compareBy({ it.effectiveDay }, { it.id }))
    val eff = terms.effective(changes, ledger.today.toEpochDay().toInt())
    var removing by remember { mutableStateOf<LoanTermChange?>(null) }
    SectionHeader(stringResource(R.string.loans_terms))
    TermLine(stringResource(R.string.loans_interest_type), eff.type.label)
    TermLine(stringResource(R.string.loans_interest_rate, word), LoanRate.display(eff.rate))
    TermLine(if (terms.escrow > 0) stringResource(R.string.loans_principal_and_interest) else stringResource(R.string.loans_payment), Money.format(eff.payment))
    if (terms.escrow > 0) TermLine(stringResource(R.string.loans_escrow), Money.format(terms.escrow))
    terms.termMonths?.let { TermLine(stringResource(R.string.loans_term), stringResource(R.string.loans_term_months, it)) }
    TermLine(stringResource(R.string.loans_due_day), terms.dueDay.toString())
    if (changes.isNotEmpty()) {
        Text(stringResource(R.string.loans_changes), style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
        changes.forEach { c ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { removing = c }.padding(vertical = 8.dp)) {
                Text(mediumDate(c.effectiveDay), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
                Text(c.summary(terms.profit), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    removing?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.loans_remove_change_title),
            body = stringResource(R.string.loans_remove_change_body, mediumDate(c.effectiveDay)),
            confirm = stringResource(R.string.loans_remove), onConfirm = { onRemoveChange(c.id); removing = null }, onDismiss = { removing = null },
        )
    }
}

@Composable
private fun TermLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f, fill = false))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}
