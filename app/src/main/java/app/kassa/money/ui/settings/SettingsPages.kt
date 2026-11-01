package app.kassa.money.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
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
    Text(stringResource(R.string.settings_budget_month_intro), style = KassaType.small, color = Kassa.colors.ink2)
    FlowRow(Modifier.padding(top = 12.dp)) {
        listOf(1, 5, 10, 15, 20, 25).forEach { d -> Chip(stringResource(R.string.settings_budget_month_day, d), selected = ledger.monthStartDay == d) { settings.setMonthStartDay(d) } }
    }
    val day = custom.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text(stringResource(R.string.settings_budget_month_other)) },
            isError = custom.isNotEmpty() && (day == null || day !in 1..28), modifier = Modifier.weight(1f))
        TextButton(onClick = { if (day != null && day in 1..28) { settings.setMonthStartDay(day); custom = "" } }) { Text(stringResource(R.string.settings_set), color = Kassa.colors.ink) }
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
    CurrencyList(selected) { c -> if (c.code != selected) { if (hasData) confirm = c else settings.setCurrency(c.code) } }
    confirm?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.settings_currency_confirm_title, c.name),
            body = stringResource(R.string.settings_currency_confirm_body, c.code),
            confirm = stringResource(R.string.settings_currency_confirm_switch, c.code), destructive = false,
            onConfirm = { settings.setCurrency(c.code); confirm = null }, onDismiss = { confirm = null },
        )
    }
    Gap(24)
}

/**
 * The accounts that can be turned into a person: open accounts that are plain balances. Cards, loans and investments
 * have their own pages, holdings and terms, so they stay out. Nothing is guessed from a name; the user picks.
 */
internal fun convertibleAccounts(ledger: Ledger): List<Account> =
    ledger.accounts.accounts.filter {
        it.isActive && it.type !in setOf(AccountType.CREDIT_CARD, AccountType.LOAN, AccountType.INVESTMENT)
    }.sortedWith(Names.order { it.name })

/**
 * For an account that stands in for money between you and someone (an app that had no idea of people kept their
 * balances in pretend accounts). The user picks it; it becomes a person carrying the same balance, and the account is
 * archived with its history, so past months stay as they were.
 */
@Composable
internal fun AccountsToPeople(ledger: Ledger, actions: SettingsActions) {
    Text(
        stringResource(R.string.settings_people_intro),
        style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp),
    )
    val accounts = convertibleAccounts(ledger)
    var pickedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var converting by remember { mutableStateOf<Pair<Account, String>?>(null) }
    if (accounts.isEmpty()) Text(stringResource(R.string.settings_people_none), style = KassaType.body, color = Kassa.colors.ink2)
    accounts.forEach { a ->
        val balance = ledger.accounts.balances[a.id] ?: 0
        val open = pickedId == a.id
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pickedId = if (open) null else a.id; typed = a.name.take(InputRules.NAME_MAX) }) {
                EntryRow(
                    badge = { GlyphBadge(a.name.take(1)) },
                    title = a.name,
                    subtitle = when { balance > 0 -> stringResource(R.string.settings_people_receivable); balance < 0 -> stringResource(R.string.settings_people_payable); else -> stringResource(R.string.settings_people_settled) },
                    amount = Money.format(Math.abs(balance)), showDivider = false,
                )
            }
            if (open) {
                val problem = InputRules.nameProblem(typed, "person", taken = { n -> ledger.people.people.any { it.name.equals(n, ignoreCase = true) } })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        typed, { typed = InputRules.typed(it, InputRules.NAME_MAX) }, singleLine = true, label = { Text(stringResource(R.string.settings_people_name)) },
                        isError = problem != null, supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { if (problem == null) converting = a to InputRules.clean(typed) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_people_convert), color = Kassa.colors.ink)
                    }
                }
            }
            HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(top = 6.dp))
        }
    }
    converting?.let { (a, name) ->
        val balance = ledger.accounts.balances[a.id] ?: 0
        ConfirmDialog(
            title = stringResource(R.string.settings_people_confirm_title, a.name, name),
            body = stringResource(
                if (balance >= 0) R.string.settings_people_confirm_body_receivable else R.string.settings_people_confirm_body_payable,
                name, Money.format(Math.abs(balance)), a.name,
            ),
            confirm = stringResource(R.string.settings_people_convert), destructive = false,
            onConfirm = { actions.convertToPerson(a, balance, name); converting = null; pickedId = null }, onDismiss = { converting = null },
        )
    }
    Gap(24)
}
