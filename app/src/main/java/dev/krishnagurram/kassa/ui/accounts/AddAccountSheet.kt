package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountField
import dev.krishnagurram.kassa.domain.AccountForm
import dev.krishnagurram.kassa.domain.AccountGroup
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.ui.common.AmountField
import dev.krishnagurram.kassa.ui.common.DigitsField
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.NameField
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/**
 * One form for every kind of account; picking a type decides which fields appear. Checking and building the account
 * is [AccountForm]'s job, shared with [EditAccountPage].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddAccountSheet(
    groups: List<AccountGroup>,
    payFromOptions: List<Account>,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (Account, String?) -> Unit,
) {
    var form by remember { mutableStateOf(AccountForm(groupId = groups.firstOrNull()?.id)) }
    var problems by remember { mutableStateOf(emptyMap<AccountField, String>()) }
    // Each edit clears that field's problem; the rest stay until the next save.
    fun edit(field: AccountField?, change: (AccountForm) -> AccountForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Add an account", style = KassaType.section, color = Kassa.colors.ink)
            Gap(8)
            Label("Type")
            FlowRow {
                AccountType.entries.forEach { t -> Choice(t.label, form.type == t) { edit(null) { it.copy(type = t) } } }
            }
            NameField(form.name, { v -> edit(AccountField.NAME) { it.copy(name = v) } }, "Name", Modifier.fillMaxWidth(), problem = problems[AccountField.NAME])
            DigitsField(form.last4, { v -> edit(AccountField.LAST4) { it.copy(last4 = v) } }, "Last 4 digits (optional)", 4, Modifier.fillMaxWidth(),
                problem = problems[AccountField.LAST4])
            AmountField(form.balance, { v -> edit(AccountField.BALANCE) { it.copy(balance = v) } },
                if (form.type.isDebt) "Amount owed today" else "Balance today", Modifier.fillMaxWidth(),
                problem = problems[AccountField.BALANCE], placeholder = "0", signed = true)
            if (form.type.hasStatement) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitsField(form.statementDay, { v -> edit(AccountField.STATEMENT_DAY) { it.copy(statementDay = v) } }, "Statement day", 2,
                        Modifier.weight(1f), problem = problems[AccountField.STATEMENT_DAY], placeholder = "1 to 31")
                    DigitsField(form.dueDay, { v -> edit(AccountField.DUE_DAY) { it.copy(dueDay = v) } }, "Due day", 2,
                        Modifier.weight(1f), problem = problems[AccountField.DUE_DAY], placeholder = "1 to 31")
                }
                AmountField(form.limit, { v -> edit(AccountField.LIMIT) { it.copy(limit = v) } }, "Credit limit (optional)", Modifier.fillMaxWidth(),
                    problem = problems[AccountField.LIMIT])
                if (payFromOptions.isNotEmpty()) {
                    Label("Pay from")
                    FlowRow { payFromOptions.forEach { a -> Choice(a.name, form.payFromId == a.id) { edit(null) { it.copy(payFromId = a.id) } } } }
                }
            }
            if (groups.isNotEmpty()) {
                Label("Group")
                FlowRow {
                    groups.forEach { g ->
                        Choice(g.name, form.groupId == g.id && form.newGroup.isBlank()) { edit(AccountField.NEW_GROUP) { it.copy(groupId = g.id, newGroup = "") } }
                    }
                }
            }
            NameField(form.newGroup, { v -> edit(AccountField.NEW_GROUP) { it.copy(newGroup = v) } }, "Or a new group", Modifier.fillMaxWidth(),
                problem = problems[AccountField.NEW_GROUP])
            Gap(12)
            PrimaryButton("Add account") {
                problems = form.problems(takenNames)
                if (problems.isEmpty()) onSave(form.toAccount(Account(name = "", type = form.type), currentBalance = 0), form.newGroupName)
            }
            Gap(24)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/** A chip in one of the sheet's pick-one rows. Long names ellipsize inside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = KassaType.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        modifier = Modifier.padding(end = 8.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Kassa.colors.ink, selectedLabelColor = Kassa.colors.onInk),
    )
}
