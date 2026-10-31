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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    Text("Cashew backup found", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text("Read on this phone. Nothing changes until you tap Import.", style = KassaType.body, color = Kassa.colors.ink2)
    Gap(16)
    Panel {
        Column {
            Line("Accounts", count(scan.accounts.size))
            Line("Categories", if (scan.subcategories > 0) "${count(scan.categories)} · ${count(scan.subcategories)} sub" else count(scan.categories))
            Line("Entries", count(scan.entries))
            Line("Transfers", count(scan.transfers))
            if (scan.firstMillis != null) Line("From", "${day(scan.firstMillis)} to ${day(scan.lastMillis)}")
        }
    }
    Gap(12)
    Text("Next: tell Kassa what kind each account is, then review.", style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    PrimaryButton("Next", onNext)
    TextButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) { Text("Choose a different file", color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Accounts(s: ImportState.Cashew, vm: ImportViewModel) {
    Text("Your accounts", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text("Cashew doesn't say what kind of account each one is. Pick one for each; you can change it later in Accounts.", style = KassaType.body, color = Kassa.colors.ink2)
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
            if (a.pk in other) Text("Uses ${a.currency}; Kassa uses ${s.phoneCurrency}. You'll choose what happens to it next.", style = KassaType.small, color = Kassa.colors.attention)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
    Gap(20)
    val ready = s.scan.accounts.all { s.choices.accountTypes[it.pk] != null }
    if (ready) PrimaryButton("Next", vm::next)
    else Text("Pick a kind for every account to go on.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 14.dp))
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Back", color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Loans(s: ImportState.Cashew, vm: ImportViewModel) {
    Text("Your loans", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text("Cashew keeps loans you gave and loans you took in one list. Tell Kassa what each one is.", style = KassaType.body, color = Kassa.colors.ink2)
    Gap(8)
    s.scan.loans.forEach { l ->
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(l.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val payments = when (l.payments) { 0 -> "no payments yet"; 1 -> "1 payment so far"; else -> "${count(l.payments)} payments so far" }
            Text("${if (l.lent) "Lent" else "Borrowed"} · ${Money.format(l.amount)} · $payments", style = KassaType.small, color = Kassa.colors.ink2)
            FlowRow {
                listOf(LoanChoice.PERSON to "A person", LoanChoice.ACCOUNT to "A loan account", LoanChoice.ENTRIES to "Plain entries").forEach { (c, label) ->
                    Chip(label, selected = s.choices.loans[l.pk] == c, small = true) { vm.setLoan(l.pk, c) }
                }
            }
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
    Gap(12)
    Panel {
        Column {
            Bullet("A person: goes to People, with what they owe you or you owe them.")
            Bullet("A loan account: becomes an account in Accounts. What was lent or borrowed is its starting balance, and each payment is a transfer, so you see what's left.")
            Bullet("Plain entries: its payments come in as ordinary spending and income.")
        }
    }
    Gap(8)
    Text("One-off lent and borrowed entries without a loan come in as plain entries.", style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    val ready = s.scan.loans.all { s.choices.loans[it.pk] != null }
    if (ready) PrimaryButton("Next", vm::next)
    else Text("Pick one for every loan to go on.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 14.dp))
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Back", color = Kassa.colors.ink2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Currency(s: ImportState.Cashew, a: CashewScan.Wallet, vm: ImportViewModel) {
    val out = a.pk in s.choices.leaveOut
    Text("${a.name} uses ${a.currency}", style = KassaType.title, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Gap(8)
    Text("Kassa keeps one currency on this phone (${s.phoneCurrency}) and never converts amounts. Choose what happens to this account.", style = KassaType.body, color = Kassa.colors.ink2)
    Gap(12)
    FlowRow {
        Chip("Bring it as it is", selected = !out) { vm.setLeaveOut(a.pk, false) }
        Chip("Leave it out", selected = out) { vm.setLeaveOut(a.pk, true) }
    }
    Gap(8)
    Panel {
        Text(
            if (!out) "Its ${count(a.entries)} entries come in with their numbers unchanged, so ${amountIn(a.balance, a.currency)} shows as ${Money.format(a.balance)}. Totals mix both currencies."
            else "The account and its ${count(a.entries)} entries stay in Cashew." + if (a.transfers > 0) " ${count(a.transfers)} transfers with other accounts lose their other side and come in as plain entries, so those balances still match." else "",
            style = KassaType.body, color = Kassa.colors.ink,
        )
    }
    Gap(8)
    Text("Converting isn't offered: it would need exchange rates, and Kassa never goes online.", style = KassaType.small, color = Kassa.colors.ink2)
    Gap(20)
    PrimaryButton("Next", vm::next)
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text("Back", color = Kassa.colors.ink2) }
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
