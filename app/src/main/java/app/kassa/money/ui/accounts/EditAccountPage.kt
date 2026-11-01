package app.kassa.money.ui.accounts

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountField
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

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
    val initial = remember { AccountForm.of(account, current) }
    var form by remember { mutableStateOf(initial) }
    var problems by remember { mutableStateOf(emptyMap<AccountField, String>()) }
    var creatingGroup by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Picker?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun edit(field: AccountField?, change: (AccountForm) -> AccountForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }
    val dirty = form != initial
    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val isCard = form.type.hasStatement
    val payFromOptions = ledger.accounts.accounts.filter { it.isActive && it.id != account.id && it.type in PAY_FROM_TYPES }
    val groups = ledger.accounts.groups
    val save = {
        problems = form.problems(takenNames = ledger.accounts.accounts.filter { it.id != account.id }.map { it.name })
        if (problems.isEmpty()) onSave(form.toAccount(account, current), form.newGroupName.takeIf { creatingGroup })
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        PageTop(if (isCard) stringResource(R.string.accounts_edit_card) else stringResource(R.string.accounts_edit_account), onBack = leave, amounts = false)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Group(if (isCard) stringResource(R.string.accounts_card) else stringResource(R.string.accounts_account)) {
                Field(stringResource(R.string.accounts_name), form.name, { v -> edit(AccountField.NAME) { it.copy(name = InputRules.typed(v, InputRules.NAME_MAX)) } },
                    problem = problems[AccountField.NAME])
                Field(stringResource(R.string.accounts_last4), form.last4, { v -> edit(AccountField.LAST4) { it.copy(last4 = InputRules.typedDigits(v, 4)) } },
                    KeyboardType.NumberPassword, stringResource(R.string.accounts_optional), problems[AccountField.LAST4])
            }
            if (isCard) Group(stringResource(R.string.accounts_statement)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        Field(stringResource(R.string.accounts_statement_day), form.statementDay, { v -> edit(AccountField.STATEMENT_DAY) { it.copy(statementDay = InputRules.typedDigits(v, 2)) } },
                            KeyboardType.NumberPassword, stringResource(R.string.accounts_one_to_31), problems[AccountField.STATEMENT_DAY])
                    }
                    Box(Modifier.weight(1f)) {
                        Field(stringResource(R.string.accounts_due_day), form.dueDay, { v -> edit(AccountField.DUE_DAY) { it.copy(dueDay = InputRules.typedDigits(v, 2)) } },
                            KeyboardType.NumberPassword, stringResource(R.string.accounts_one_to_31), problems[AccountField.DUE_DAY])
                    }
                }
                Field(stringResource(R.string.accounts_credit_limit), form.limit, { v -> edit(AccountField.LIMIT) { it.copy(limit = InputRules.typedAmount(v)) } },
                    KeyboardType.Decimal, stringResource(R.string.accounts_optional), problems[AccountField.LIMIT])
            }
            // An investment account's balance is its cash; holdings are valued separately.
            val investing = form.type == AccountType.INVESTMENT
            Group(if (investing) stringResource(R.string.accounts_cash) else stringResource(R.string.accounts_balance)) {
                Field(
                    when { investing -> stringResource(R.string.accounts_cash_today); form.type.hasStatement -> stringResource(R.string.accounts_total_balance_today); form.type.isDebt -> stringResource(R.string.accounts_outstanding_balance_today); else -> stringResource(R.string.accounts_balance_today) }, form.balance,
                    { v -> edit(AccountField.BALANCE) { it.copy(balance = InputRules.typedBalance(v)) } }, KeyboardType.Decimal, problem = problems[AccountField.BALANCE],
                )
                Text(
                    if (investing) stringResource(R.string.accounts_edit_investing_note)
                    else stringResource(R.string.accounts_edit_balance_note),
                    style = KassaType.tiny, color = Kassa.colors.ink2,
                )
            }
            Group(stringResource(R.string.accounts_details)) {
                if (isCard) PickRow(stringResource(R.string.accounts_default_payment_account), payFromOptions.firstOrNull { it.id == form.payFromId }?.name ?: stringResource(R.string.accounts_none)) { picking = Picker.PAY_FROM }
                PickRow(
                    stringResource(R.string.accounts_group),
                    when {
                        creatingGroup -> form.newGroup.ifBlank { stringResource(R.string.accounts_new_group) }
                        else -> groups.firstOrNull { it.id == form.groupId }?.name ?: stringResource(R.string.accounts_none)
                    },
                ) { picking = Picker.GROUP }
                if (creatingGroup) Field(stringResource(R.string.accounts_new_group_name), form.newGroup, { v -> edit(AccountField.NEW_GROUP) { it.copy(newGroup = InputRules.typed(v, InputRules.NAME_MAX)) } },
                    problem = problems[AccountField.NEW_GROUP])
                PickRow(stringResource(R.string.accounts_type), form.type.label, last = form.type != AccountType.FIXED_DEPOSIT) { picking = Picker.TYPE }
                if (form.type.hasMaturity) MaturityField(form.maturityDay, ledger.today) { d -> edit(null) { it.copy(maturityDay = d) } }
            }
            PrimaryButton(stringResource(R.string.accounts_save_changes), save)
            TextButton(onClick = { if (account.archived) onArchive(false) else confirmArchive = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (account.archived) stringResource(R.string.accounts_restore) else if (isCard) stringResource(R.string.accounts_archive_card) else stringResource(R.string.accounts_archive_account), color = Kassa.colors.ink2)
            }
            Gap(16)
        }
    }

    val close = { picking = null }
    when (picking) {
        Picker.PAY_FROM -> Choices(stringResource(R.string.accounts_default_payment_account), payFromOptions.map { it.id to it.name }, form.payFromId,
            onPick = { id -> edit(null) { it.copy(payFromId = id) }; close() }, onDismiss = close)
        Picker.GROUP -> Choices(stringResource(R.string.accounts_group), groups.map { it.id to it.name } + (NEW_GROUP to stringResource(R.string.accounts_new_group)), if (creatingGroup) NEW_GROUP else form.groupId,
            onPick = { id ->
                creatingGroup = id == NEW_GROUP
                edit(AccountField.NEW_GROUP) { if (id == NEW_GROUP) it.copy(groupId = null) else it.copy(groupId = id, newGroup = "") }
                close()
            },
            onDismiss = close)
        Picker.TYPE -> Choices(stringResource(R.string.accounts_type), AccountType.entries.map { it.ordinal.toLong() to it.label }, form.type.ordinal.toLong(),
            onPick = { i -> edit(null) { it.copy(type = AccountType.entries[i.toInt()]) }; close() }, onDismiss = close)
        null -> Unit
    }
    if (confirmArchive) ConfirmDialog(
        title = stringResource(R.string.accounts_archive_title, account.name),
        body = stringResource(R.string.accounts_archive_body),
        confirm = stringResource(R.string.accounts_archive), onConfirm = { onArchive(true) }, onDismiss = { confirmArchive = false },
    )
    if (confirmDiscard) ConfirmDialog(
        title = stringResource(R.string.accounts_discard_title), body = stringResource(R.string.accounts_discard_body),
        confirm = stringResource(R.string.accounts_discard), dismiss = stringResource(R.string.accounts_keep_editing), onConfirm = onBack, onDismiss = { confirmDiscard = false },
    )
}

private enum class Picker { PAY_FROM, GROUP, TYPE }

private val PAY_FROM_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)

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

/** A labelled field on the ground color. The caller filters what's typed; [problem] shows underneath. */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, hint: String = "", problem: String? = null) {
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
        problem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PickRow(label: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.padding(end = 12.dp))
            Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
        }
        if (!last) HorizontalDivider(color = Kassa.colors.line)
    }
}

/** A short list to pick one from; also used by the loan forms. */
@Composable
internal fun Choices(title: String, options: List<Pair<Long, String>>, selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kassa.colors.raised,
        title = { Text(title, style = KassaType.section, color = Kassa.colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Text(
                        label, style = if (id == selected) KassaType.bodyStrong else KassaType.body, color = Kassa.colors.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (id == selected) Kassa.colors.chip else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_cancel), color = Kassa.colors.ink) } },
    )
}
