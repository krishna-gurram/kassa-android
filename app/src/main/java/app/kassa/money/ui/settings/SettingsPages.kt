package app.kassa.money.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Account
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.EntryRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.GlyphBadge
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** The day the budget month starts, 1 to 28, so every month has it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BudgetMonthPage(ledger: Ledger) {
    val settings = LocalSettings.current
    var custom by remember { mutableStateOf("") }
    Text("The day your budget month starts. Pick payday to budget from one paycheck to the next.", style = KassaType.small, color = Kassa.colors.ink2)
    FlowRow(Modifier.padding(top = 12.dp)) {
        listOf(1, 5, 10, 15, 20, 25).forEach { d -> Chip("Day $d", selected = ledger.monthStartDay == d) { settings.setMonthStartDay(d) } }
    }
    val day = custom.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Another day, 1 to 28") },
            isError = custom.isNotEmpty() && (day == null || day !in 1..28), modifier = Modifier.weight(1f))
        TextButton(onClick = { if (day != null && day in 1..28) { settings.setMonthStartDay(day); custom = "" } }) { Text("Set", color = Kassa.colors.ink) }
    }
}

/**
 * The one currency this phone shows. Changing it relabels what's here without converting anything, so with
 * entries already in Kassa it asks first.
 */
@Composable
internal fun CurrencyPage(ledger: Ledger) {
    val settings = LocalSettings.current
    val selected by settings.currency.collectAsState()
    var confirm by remember { mutableStateOf<CurrencyStyle?>(null) }
    val hasData = ledger.recent.isNotEmpty() || ledger.accounts.accounts.isNotEmpty()
    Currencies.all.forEach { c ->
        Column(Modifier.clickable { if (c.code != selected) { if (hasData) confirm = c else settings.setCurrency(c.code) } }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                    Text(c.code, style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(Money.format(123_450, style = c), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(end = 10.dp))
                Text(if (c.code == selected) "✓" else "", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    confirm?.let { c ->
        ConfirmDialog(
            title = "Show amounts in ${c.name}?",
            body = "Every amount already in Kassa keeps its number and shows as ${c.code}. Nothing is converted.",
            confirm = "Switch to ${c.code}", destructive = false,
            onConfirm = { settings.setCurrency(c.code); confirm = null }, onDismiss = { confirm = null },
        )
    }
    Gap(24)
}

internal fun splitwiseAccounts(ledger: Ledger): List<Account> =
    ledger.accounts.accounts.filter { !it.archived && it.name.contains("splitwise", ignoreCase = true) }

/**
 * Money Manager had no idea of people, so Splitwise balances lived in pretend accounts. Each one becomes a
 * person carrying the same balance; the account is archived with its history, so past months stay as they were.
 */
@Composable
internal fun SplitwiseCleanup(ledger: Ledger, actions: SettingsActions) {
    Text(
        "Each account becomes a person with the same balance. A positive balance means they owe you. The account is archived and keeps its history, so past months don't change.",
        style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp),
    )
    val accounts = splitwiseAccounts(ledger)
    var converting by remember { mutableStateOf<Pair<Account, String>?>(null) }
    if (accounts.isEmpty()) Text("Nothing left to clean up.", style = KassaType.body, color = Kassa.colors.ink2)
    accounts.forEach { a ->
        val balance = ledger.accounts.balances[a.id] ?: 0
        var name by remember(a.id) { mutableStateOf("Splitwise friends") }
        val problem = InputRules.nameProblem(name, "person", taken = { n -> ledger.people.people.any { it.name.equals(n, ignoreCase = true) } })
        Column(Modifier.padding(vertical = 10.dp)) {
            EntryRow(
                badge = { GlyphBadge(a.name.take(1)) },
                title = a.name,
                subtitle = when { balance > 0 -> "Receivable, they owe you"; balance < 0 -> "Payable, you owe them"; else -> "Settled" },
                amount = Money.format(Math.abs(balance)), showDivider = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = InputRules.typed(it, InputRules.NAME_MAX) }, singleLine = true, label = { Text("Person name") },
                    isError = problem != null, supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { if (problem == null) converting = a to InputRules.clean(name) }) { Text("Convert", color = Kassa.colors.ink) }
            }
            HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(top = 10.dp))
        }
    }
    converting?.let { (a, name) ->
        val balance = ledger.accounts.balances[a.id] ?: 0
        ConfirmDialog(
            title = "Turn ${a.name} into $name?",
            body = "$name starts with a balance of ${Money.format(Math.abs(balance))} ${if (balance >= 0) "receivable" else "payable"}. ${a.name} is archived with its history.",
            confirm = "Convert", destructive = false,
            onConfirm = { actions.convertToPerson(a, balance, name); converting = null }, onDismiss = { converting = null },
        )
    }
    Gap(24)
}
