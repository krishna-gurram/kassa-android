package app.kassa.money.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberText
import app.kassa.money.importer.CashewScan
import app.kassa.money.importer.LoanChoice
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.Panel
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The questions a Cashew backup needs answered before its review: what each account and loan is, and other currencies. */
@Composable
internal fun CashewSteps(s: ImportState.Cashew, vm: ImportViewModel) {
    when (val step = s.step) {
        CashewStep.Found -> Found(s.scan, vm::next, vm::reset)
        CashewStep.Accounts -> Accounts(s, vm)
        CashewStep.Loans -> Loans(s, vm)
        is CashewStep.Currency -> Currency(s, s.otherCurrency[step.index], vm)
    }
}

@Composable
private fun Found(scan: CashewScan, onNext: () -> Unit, onPickAgain: () -> Unit) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(date) } ?: ""
    Text(stringResource(R.string.importer_cashew_found_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(stringResource(R.string.importer_cashew_found_body), style = KassaType.body, color = Kassa.colors.ink2)
    Gap(16)
    Panel {
        Column {
            Line(stringResource(R.string.importer_accounts), count(scan.accounts.size))
            Line(stringResource(R.string.importer_categories), if (scan.subcategories > 0) stringResource(R.string.importer_cashew_categories_sub, count(scan.categories), count(scan.subcategories)) else count(scan.categories))
            Line(stringResource(R.string.importer_cashew_entries), count(scan.entries))
            Line(stringResource(R.string.importer_transfers), count(scan.transfers))
            if (scan.firstMillis != null) Line(stringResource(R.string.importer_cashew_from), stringResource(R.string.importer_cashew_range, day(scan.firstMillis), day(scan.lastMillis)))
        }
    }
    Gap(12)
    Text(stringResource(R.string.importer_cashew_next_hint), style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    PrimaryButton(stringResource(R.string.importer_next), onNext)
    TextButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_choose_different), color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Accounts(s: ImportState.Cashew, vm: ImportViewModel) {
    Text(stringResource(R.string.importer_cashew_accounts_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(stringResource(R.string.importer_cashew_accounts_body), style = KassaType.body, color = Kassa.colors.ink2)
    Gap(8)
    val other = s.otherCurrency.map { it.pk }.toSet()
    s.scan.accounts.forEach { a ->
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(a.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Text(amountIn(a.balance, if (a.pk in other) a.currency else null), style = KassaType.body, color = Kassa.colors.ink, maxLines = 1)
            }
            FlowRow {
                AccountType.entries.forEach { t -> Chip(t.label, selected = s.choices.accountTypes[a.pk] == t, small = true) { vm.setAccountType(a.pk, t) } }
            }
            if (a.pk in other) Text(stringResource(R.string.importer_cashew_other_currency_note, a.currency.orEmpty(), s.phoneCurrency), style = KassaType.small, color = Kassa.colors.attention)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
    Gap(20)
    val ready = s.scan.accounts.all { s.choices.accountTypes[it.pk] != null }
    if (ready) PrimaryButton(stringResource(R.string.importer_next), vm::next)
    else Text(stringResource(R.string.importer_cashew_accounts_pick_all), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 14.dp))
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_back), color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Loans(s: ImportState.Cashew, vm: ImportViewModel) {
    Text(stringResource(R.string.importer_cashew_loans_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(stringResource(R.string.importer_cashew_loans_body), style = KassaType.body, color = Kassa.colors.ink2)
    Gap(8)
    s.scan.loans.forEach { l ->
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(l.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val payments = if (l.payments == 0) stringResource(R.string.importer_cashew_loan_no_payments) else pluralStringResource(R.plurals.importer_cashew_loan_payments, l.payments, count(l.payments))
            Text(stringResource(R.string.importer_cashew_loan_line, if (l.lent) stringResource(R.string.importer_cashew_loan_lent) else stringResource(R.string.importer_cashew_loan_borrowed), Money.format(l.amount), payments), style = KassaType.small, color = Kassa.colors.ink2)
            FlowRow {
                listOf(LoanChoice.PERSON to stringResource(R.string.importer_cashew_choice_person), LoanChoice.ACCOUNT to stringResource(R.string.importer_cashew_choice_account), LoanChoice.ENTRIES to stringResource(R.string.importer_cashew_choice_entries)).forEach { (c, label) ->
                    Chip(label, selected = s.choices.loans[l.pk] == c, small = true) { vm.setLoan(l.pk, c) }
                }
            }
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
    Gap(12)
    Panel {
        Column {
            Bullet(stringResource(R.string.importer_cashew_bullet_person))
            Bullet(stringResource(R.string.importer_cashew_bullet_account))
            Bullet(stringResource(R.string.importer_cashew_bullet_entries))
        }
    }
    Gap(8)
    Text(stringResource(R.string.importer_cashew_loans_oneoff), style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    val ready = s.scan.loans.all { s.choices.loans[it.pk] != null }
    if (ready) PrimaryButton(stringResource(R.string.importer_next), vm::next)
    else Text(stringResource(R.string.importer_cashew_loans_pick_all), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 14.dp))
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_back), color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Currency(s: ImportState.Cashew, a: CashewScan.Wallet, vm: ImportViewModel) {
    val out = a.pk in s.choices.leaveOut
    Text(stringResource(R.string.importer_cashew_currency_title, a.name, a.currency.orEmpty()), style = KassaType.title, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Gap(8)
    Text(stringResource(R.string.importer_cashew_currency_body, s.phoneCurrency), style = KassaType.body, color = Kassa.colors.ink2)
    Gap(12)
    FlowRow {
        Chip(stringResource(R.string.importer_cashew_bring_as_is), selected = !out) { vm.setLeaveOut(a.pk, false) }
        Chip(stringResource(R.string.importer_cashew_leave_out), selected = out) { vm.setLeaveOut(a.pk, true) }
    }
    Gap(8)
    Panel {
        Text(
            if (!out) stringResource(R.string.importer_cashew_bring_detail, count(a.entries), amountIn(a.balance, a.currency), Money.format(a.balance))
            else if (a.transfers > 0) stringResource(R.string.importer_cashew_leave_detail_transfers, count(a.entries), count(a.transfers))
            else stringResource(R.string.importer_cashew_leave_detail, count(a.entries)),
            style = KassaType.body, color = Kassa.colors.ink,
        )
    }
    Gap(8)
    Text(stringResource(R.string.importer_cashew_no_convert), style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    PrimaryButton(stringResource(R.string.importer_next), vm::next)
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_back), color = Kassa.colors.ink2) }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text("•", style = KassaType.body, color = Kassa.colors.ink3, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = KassaType.body, color = Kassa.colors.ink)
    }
}

/** An amount in the phone's currency, or as a plain number with its code when it's another currency. */
private fun amountIn(cents: Long, otherCode: String?): String =
    if (otherCode == null) Money.format(cents)
    else NumberText.decimal(java.math.BigDecimal.valueOf(cents, 2), 2) + " " + otherCode

private fun count(n: Int): String = NumberText.group(n.toString())
