package dev.krishnagurram.kassa.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import kotlin.math.abs

private const val NEW_GROUP = -1L

/**
 * Edit an account or card on its own page, grouped like Settings: what it's called, its statement days,
 * its balance, then the details you rarely change, each one row that opens a list. Saving changes the
 * balance by adjusting the starting balance, so entries stay as they are.
 */
@Composable
fun EditAccountPage(
    ledger: Ledger,
    account: Account,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSave: (Account, String?) -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    val current = ledger.accounts.balances[account.id] ?: 0
    fun input(c: Long?, signed: Boolean) = c?.let { java.math.BigDecimal.valueOf(if (signed) it else abs(it)).movePointLeft(2).toPlainString() } ?: ""
    var type by remember { mutableStateOf(account.type) }
    var name by remember { mutableStateOf(account.name) }
    var last4 by remember { mutableStateOf(account.last4 ?: "") }
    var balance by remember { mutableStateOf(input(current, signed = !account.type.isDebt)) }
    var statementDay by remember { mutableStateOf(account.statementDay?.toString() ?: "") }
    var dueDay by remember { mutableStateOf(account.dueDay?.toString() ?: "") }
    var limit by remember { mutableStateOf(input(account.creditLimit, signed = false)) }
    var payFrom by remember { mutableStateOf(account.payFromId) }
    var groupId by remember { mutableStateOf(account.groupId) }
    var newGroup by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val initial = remember { listOf(type, name, last4, balance, statementDay, dueDay, limit, payFrom, groupId, newGroup) }
    val dirty = listOf(type, name, last4, balance, statementDay, dueDay, limit, payFrom, groupId, newGroup) != initial
    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val isCard = type.hasStatement
    val payFromOptions = ledger.accounts.accounts.filter { !it.archived && it.id != account.id && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) }
    val groups = ledger.accounts.groups

    fun save() {
        val cents = if (balance.isBlank()) 0L else Money.parseCents(balance)
        val st = statementDay.toIntOrNull()
        val du = dueDay.toIntOrNull()
        error = when {
            name.isBlank() -> "Give it a name."
            last4.isNotEmpty() && last4.length != 4 -> "Last 4 digits needs four numbers."
            cents == null -> "The balance isn't a number."
            isCard && ((st ?: 0) !in 1..31 || (du ?: 0) !in 1..31) -> "Statement day and due day need to be 1 to 31."
            limit.isNotBlank() && Money.parseCents(limit) == null -> "The credit limit isn't a number."
            else -> null
        }
        if (error != null) return
        val today = if (type.isDebt) -abs(cents!!) else cents!!
        onSave(
            account.copy(
                name = name.trim(), type = type, last4 = last4.takeIf { it.length == 4 },
                openingBalance = account.openingBalance + (today - current),
                statementDay = if (isCard) st else null, dueDay = if (isCard) du else null,
                creditLimit = if (isCard) Money.parseCents(limit) else null,
                payFromId = if (isCard) payFrom else null,
                groupId = if (groupId == NEW_GROUP) null else groupId,
            ),
            newGroup.takeIf { groupId == NEW_GROUP && it.isNotBlank() },
        )
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        PageTop(if (isCard) "Edit card" else "Edit account", onBack = leave)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Group(if (isCard) "Card" else "Account") {
                Field("Name", name, { name = it })
                Field("Last 4 digits", last4, { last4 = it.filter(Char::isDigit).take(4) }, KeyboardType.Number, "Optional")
            }
            if (isCard) Group("Statement") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("Statement day", statementDay, { statementDay = it.filter(Char::isDigit).take(2) }, KeyboardType.Number, "1 to 31") }
                    Box(Modifier.weight(1f)) { Field("Due day", dueDay, { dueDay = it.filter(Char::isDigit).take(2) }, KeyboardType.Number, "1 to 31") }
                }
                Field("Credit limit", limit, { limit = it }, KeyboardType.Decimal, "Optional")
            }
            Group("Balance") {
                Field(if (type.isDebt) "Amount owed today" else "Balance today", balance, { balance = it }, KeyboardType.Decimal)
                Text("Adjusts the starting balance. Entries stay as they are.", style = KassaType.tiny, color = Kassa.colors.ink2)
            }
            Group("Details") {
                if (isCard) PickRow("Default payment account", payFromOptions.firstOrNull { it.id == payFrom }?.name ?: "None") { picking = "payFrom" }
                PickRow(
                    "Group",
                    when (groupId) { NEW_GROUP -> newGroup.ifBlank { "New group" }; null -> "None"; else -> groups.firstOrNull { it.id == groupId }?.name ?: "None" },
                ) { picking = "group" }
                if (groupId == NEW_GROUP) Field("New group name", newGroup, { newGroup = it })
                PickRow("Type", type.label, last = true) { picking = "type" }
            }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            PrimaryButton("Save changes", ::save)
            TextButton(onClick = { if (account.archived) onArchive(false) else confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (account.archived) "Restore" else if (isCard) "Archive card" else "Archive account", color = Kassa.colors.ink2)
            }
            Gap(16)
        }
    }

    when (picking) {
        "payFrom" -> Choices("Default payment account", payFromOptions.map { it.id to it.name }, payFrom, onPick = { payFrom = it; picking = null }, onDismiss = { picking = null })
        "group" -> Choices("Group", groups.map { it.id to it.name } + (NEW_GROUP to "New group"), groupId, onPick = { groupId = it; picking = null }, onDismiss = { picking = null })
        "type" -> Choices("Type", AccountType.entries.mapIndexed { i, t -> i.toLong() to t.label }, AccountType.entries.indexOf(type).toLong(),
            onPick = { type = AccountType.entries[it.toInt()]; picking = null }, onDismiss = { picking = null })
    }
    if (confirmArchive) ConfirmDialog(
        title = "Archive ${account.name}?",
        body = "It leaves your lists and totals. Its entries stay in your history, and you can restore it from Accounts.",
        confirm = "Archive", onConfirm = { onArchive(true) }, onDismiss = { confirmArchive = false },
    )
    if (confirmDiscard) ConfirmDialog(
        title = "Discard your changes?", body = "The account stays as it was.",
        confirm = "Discard", dismiss = "Keep editing", onConfirm = onBack, onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), style = KassaType.tiny.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.6f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Kassa.colors.ink2)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, hint: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = KassaType.tiny, color = Kassa.colors.ink2)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = KassaType.body.copy(color = Kassa.colors.ink),
            cursorBrush = SolidColor(Kassa.colors.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Kassa.colors.ground).padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3); inner() } },
        )
    }
}

@Composable
private fun PickRow(label: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink2, maxLines = 1)
            Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
        }
        if (!last) HorizontalDivider(color = Kassa.colors.line)
    }
}

/** A short list to pick one from. */
@Composable
private fun Choices(title: String, options: List<Pair<Long, String>>, selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kassa.colors.raised,
        title = { Text(title, style = KassaType.section, color = Kassa.colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Text(
                        label, style = if (id == selected) KassaType.bodyStrong else KassaType.body, color = Kassa.colors.ink,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (id == selected) Kassa.colors.chip else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Kassa.colors.ink) } },
    )
}
