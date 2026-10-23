package dev.krishnagurram.kassa.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Recurring bills: each asks on its day with a prefilled amount and never posts on its own. */
@Composable
fun RecurringPage(ledger: Ledger, actions: SettingsActions) {
    var editing by remember { mutableStateOf<Recurring?>(null) }
    val e = editing
    if (e != null) {
        androidx.compose.runtime.key(e.id) { RecurringForm(ledger, e, onSave = { actions.saveRecurring(it); editing = null }, onDelete = { actions.deleteRecurring(it.id); editing = null }, onCancel = { editing = null }) }
        return
    }
    Text("On the bill's day, Overview asks you to approve it with a fixed amount or an estimate from your last three bills.", style = KassaType.body, color = Kassa.colors.ink2)
    Gap(10)
    val cat = ledger.categories.firstOrNull { it.kind == CategoryKind.EXPENSE && !it.countsTowardDaily && !it.hidden } ?: ledger.categories.firstOrNull { it.kind == CategoryKind.EXPENSE && !it.hidden }
    val acct = ledger.accounts.accounts.firstOrNull { !it.archived && it.type == AccountType.CHECKING } ?: ledger.accounts.accounts.firstOrNull { !it.archived }
    if (cat == null || acct == null) Text("Add an account and an expense category first.", style = KassaType.small, color = Kassa.colors.attention)
    else SecondaryButton("Add a recurring bill", {
        editing = Recurring(name = "", categoryId = cat.id, accountId = acct.id, dayOfMonth = minOf(ledger.today.dayOfMonth, 28), fixedAmount = null)
    })
    SectionHeader("Your bills")
    if (ledger.assist.recurring.isEmpty()) Text("None yet. Good candidates: mortgage, electricity, water, phone, insurance.", style = KassaType.body, color = Kassa.colors.ink2)
    ledger.assist.recurring.forEach { r ->
        val est = RecurringRules.estimate(r, ledger.recent, ledger.today.plusDays(1), ledger.zone)
        Column(Modifier.clickable { editing = r }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                    Text("Day ${r.dayOfMonth}, ${ledger.accountById[r.accountId]?.name ?: "no account"}${if (!r.active) ", paused" else ""}", style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(est?.let { (if (r.fixedAmount == null) "about " else "") + Money.format(it) } ?: "No history", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    Gap(24)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurringForm(ledger: Ledger, r: Recurring, onSave: (Recurring) -> Unit, onDelete: (Recurring) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(r.name) }
    var day by remember { mutableStateOf(r.dayOfMonth.toString()) }
    var fixed by remember { mutableStateOf(r.fixedAmount != null) }
    var amount by remember { mutableStateOf(r.fixedAmount?.let { Money.format(it).removePrefix("$").replace(",", "") } ?: "") }
    var categoryId by remember { mutableStateOf(r.categoryId) }
    var accountId by remember { mutableStateOf(r.accountId) }
    var active by remember { mutableStateOf(r.active) }
    var error by remember { mutableStateOf<String?>(null) }
    val noteSuggestions = ledger.recent.filter { it.categoryId == categoryId && it.note.isNotBlank() }.groupingBy { it.note }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }

    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, as you write it in entries") }, modifier = Modifier.fillMaxWidth())
    if (noteSuggestions.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp)) { noteSuggestions.forEach { n -> Chip(n, selected = n == name, small = true) { name = n } } }
    Text("Category", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    FlowRow {
        ledger.categories.filter { it.kind == CategoryKind.EXPENSE && !it.hidden }.forEach { c ->
            val label = c.parentId?.let { p -> "${ledger.categoryById[p]?.name}: ${c.name}" } ?: c.name
            Chip(label, selected = categoryId == c.id, small = true) { categoryId = c.id }
        }
    }
    Text("Paid from", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    FlowRow { ledger.accounts.accounts.filter { !it.archived }.forEach { a -> Chip(a.name, selected = accountId == a.id, small = true) { accountId = a.id } } }
    OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Day of the month") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Same amount every month", style = KassaType.body, color = Kassa.colors.ink)
            Text("Off: Kassa estimates from your last three bills.", style = KassaType.small, color = Kassa.colors.ink2)
        }
        Switch(fixed, { fixed = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
    }
    if (fixed) OutlinedTextField(amount, { amount = it }, singleLine = true, label = { Text("Amount") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    if (r.id != 0L) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Active", style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Switch(active, { active = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
    }
    error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 8.dp)) }
    Gap(16)
    PrimaryButton(if (r.id == 0L) "Add bill" else "Save") {
        val d = day.toIntOrNull()
        val amt = if (fixed) Money.parseCents(amount) else null
        error = when {
            name.isBlank() -> "Give it a name."
            d == null || d !in 1..31 -> "The day needs to be 1 to 31."
            fixed && (amt == null || amt <= 0) -> "Enter the amount."
            else -> null
        }
        if (error == null) onSave(r.copy(name = name.trim(), dayOfMonth = d!!, fixedAmount = amt, categoryId = categoryId, accountId = accountId, active = active))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    if (r.id != 0L) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete bill", color = Kassa.colors.attention) }
    if (confirmDelete) dev.krishnagurram.kassa.ui.common.ConfirmDialog(
        title = "Delete ${r.name}?",
        body = "Kassa stops asking about this bill. Entries already added for it stay.",
        confirm = "Delete", onConfirm = { onDelete(r) }, onDismiss = { confirmDelete = false },
    )
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
    Gap(24)
}

/** Notification capture: system permission, which apps to read, and which account each app's alerts belong to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsPage(ledger: Ledger, actions: SettingsActions, alertApps: Set<String>, seenApps: Set<String>) {
    val granted = actions.hasNotificationAccess()
    Text(
        "Kassa can read alerts from your bank and card apps and turn purchases into items on Overview for you to confirm. " +
            "Alerts are read on this phone only. Kassa has no internet access, keeps just the amount and merchant, and ignores every other app.",
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    Gap(12)
    if (!granted) {
        PrimaryButton("Allow notification access", actions::openNotificationAccess)
        Text("Android opens its settings. Turn on Kassa bank alerts, then come back.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp))
    } else {
        Text("Notification access is on.", style = KassaType.bodyStrong, color = Kassa.colors.positive)
    }

    SectionHeader("Apps")
    val apps = (seenApps + alertApps).sorted()
    if (apps.isEmpty()) Text("No bank alerts seen yet. Make a card purchase, and the app that notifies you will show up here.", style = KassaType.body, color = Kassa.colors.ink2)
    apps.forEach { pkg ->
        val on = pkg in alertApps
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(actions.appLabel(pkg), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text(pkg, style = KassaType.small, color = Kassa.colors.ink3)
            }
            Switch(on, { actions.setAlertApp(pkg, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
        }
        if (on) androidx.compose.runtime.key(pkg) { RuleEditor(ledger, actions, pkg) }
        HorizontalDivider(color = Kassa.colors.line)
    }
    Gap(24)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(ledger: Ledger, actions: SettingsActions, pkg: String) {
    val rules = ledger.assist.rules.filter { it.packageName == pkg }
    var last4 by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<Long?>(null) }
    rules.forEach { rule ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${rule.last4?.let { "Card ending $it" } ?: "Any card"} goes to ${ledger.accountById[rule.accountId]?.name ?: "a removed account"}",
                style = KassaType.small, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.deleteRule(rule.id) }) { Text("Remove", color = Kassa.colors.ink2) }
        }
    }
    Text(if (rules.isEmpty()) "Which account are these alerts for?" else "Add another card from this app", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
    FlowRow(Modifier.padding(top = 4.dp)) {
        ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CREDIT_CARD, AccountType.CHECKING) }.forEach { a ->
            Chip(a.name, selected = account == a.id, small = true) { account = a.id }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Last 4 digits (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        TextButton(onClick = { account?.let { actions.addRule(pkg, last4.ifBlank { null }, it); last4 = ""; account = null } }) { Text("Add", color = Kassa.colors.ink) }
    }
}
