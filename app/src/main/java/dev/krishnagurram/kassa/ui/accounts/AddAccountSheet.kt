package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountGroup
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/**
 * One form for every kind of account. Picking a type decides which fields appear; there is no cap on how many
 * accounts you can add.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddAccountSheet(
    groups: List<AccountGroup>,
    payFromOptions: List<Account>,
    onDismiss: () -> Unit,
    onSave: (Account, String?) -> Unit,
    /** An account to edit, with its balance today. Changing the balance adjusts the starting balance. */
    editing: Account? = null,
    currentBalance: Long = 0,
) {
    fun input(c: Long?) = c?.let { java.math.BigDecimal.valueOf(kotlin.math.abs(it)).movePointLeft(2).toPlainString() } ?: ""
    var type by remember { mutableStateOf(editing?.type ?: AccountType.CHECKING) }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var balance by remember {
        mutableStateOf(
            when {
                editing == null -> ""
                editing.type.isDebt -> input(currentBalance)
                else -> java.math.BigDecimal.valueOf(currentBalance).movePointLeft(2).toPlainString()
            }
        )
    }
    var statementDay by remember { mutableStateOf(editing?.statementDay?.toString() ?: "") }
    var dueDay by remember { mutableStateOf(editing?.dueDay?.toString() ?: "") }
    var limit by remember { mutableStateOf(input(editing?.creditLimit)) }
    var groupId by remember { mutableStateOf(editing?.groupId ?: groups.firstOrNull()?.id) }
    var newGroup by remember { mutableStateOf("") }
    var payFrom by remember { mutableStateOf(editing?.payFromId ?: payFromOptions.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding()) {
            Text(if (editing != null) "Edit ${editing.name}" else "Add an account", style = KassaType.title.copy(fontSize = KassaType.title.fontSize * 0.75f), color = Kassa.colors.ink)
            Gap(16)
            Label("Type")
            FlowRow {
                AccountType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t, onClick = { type = t; error = null },
                        label = { Text(t.label, style = KassaType.small) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Kassa.colors.ink, selectedLabelColor = Kassa.colors.onInk),
                    )
                }
            }
            Gap(8)
            Field(name, { name = it }, "Name", placeholder = when (type) {
                AccountType.CREDIT_CARD -> "Citi Costco"
                AccountType.INVESTMENT -> "Robinhood"
                AccountType.LOAN -> "Car loan"
                else -> "Chase checking"
            })
            Field(
                balance, { balance = it },
                label = if (type.isDebt) "Amount owed today" else "Balance today",
                keyboard = KeyboardType.Decimal, placeholder = "0.00",
            )
            if (editing != null) Text(
                "Changing the balance adjusts the account's starting balance. Entries stay as they are.",
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
            )
            if (type == AccountType.CREDIT_CARD) {
                Row {
                    Field(statementDay, { statementDay = it.filter(Char::isDigit).take(2) }, "Statement day", KeyboardType.Number, "1 to 31", Modifier.weight(1f).padding(end = 8.dp))
                    Field(dueDay, { dueDay = it.filter(Char::isDigit).take(2) }, "Due day", KeyboardType.Number, "1 to 31", Modifier.weight(1f))
                }
                Field(limit, { limit = it }, "Credit limit (optional)", KeyboardType.Decimal, "0.00")
                if (payFromOptions.isNotEmpty()) {
                    Label("Pay from")
                    FlowRow {
                        payFromOptions.forEach { a ->
                            FilterChip(selected = payFrom == a.id, onClick = { payFrom = a.id }, label = { Text(a.name, style = KassaType.small) },
                                modifier = Modifier.padding(end = 8.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Kassa.colors.ink, selectedLabelColor = Kassa.colors.onInk))
                        }
                    }
                }
            }
            Label("Group")
            FlowRow {
                groups.forEach { g ->
                    FilterChip(selected = groupId == g.id && newGroup.isBlank(), onClick = { groupId = g.id; newGroup = "" }, label = { Text(g.name, style = KassaType.small) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Kassa.colors.ink, selectedLabelColor = Kassa.colors.onInk))
                }
            }
            Field(newGroup, { newGroup = it }, "Or a new group", placeholder = "India accounts")
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 8.dp)) }
            Gap(16)
            PrimaryButton(if (editing != null) "Save changes" else "Add account") {
                val cents = if (balance.isBlank()) 0L else Money.parseCents(balance)
                val st = statementDay.toIntOrNull()
                val du = dueDay.toIntOrNull()
                error = when {
                    name.isBlank() -> "Give the account a name."
                    cents == null -> "The balance isn't a number."
                    type == AccountType.CREDIT_CARD && ((st ?: 0) !in 1..31 || (du ?: 0) !in 1..31) -> "Statement and due day need to be 1 to 31."
                    limit.isNotBlank() && Money.parseCents(limit) == null -> "The credit limit isn't a number."
                    else -> null
                }
                if (error == null) {
                    // Debts are stored as negative balances so net worth adds up directly.
                    val today = if (type.isDebt) -kotlin.math.abs(cents!!) else cents!!
                    // Editing: shift the starting balance by the difference, so entries don't change.
                    val opening = if (editing != null) editing.openingBalance + (today - currentBalance) else today
                    onSave(
                        (editing ?: Account(name = "", type = type)).copy(
                            name = name.trim(), type = type, groupId = groupId, openingBalance = opening,
                            statementDay = if (type.hasStatement) st else null,
                            dueDay = if (type.hasStatement) du else null,
                            payFromId = if (type.hasStatement) payFrom else null,
                            creditLimit = if (type.hasStatement) Money.parseCents(limit) else null,
                        ),
                        newGroup.takeIf { it.isNotBlank() },
                    )
                }
            }
            Gap(24)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String,
    keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "", modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, placeholder = { Text(placeholder, color = Kassa.colors.ink3) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = KassaType.body,
        modifier = modifier.padding(top = 8.dp),
    )
}
