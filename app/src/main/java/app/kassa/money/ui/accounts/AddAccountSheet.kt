package app.kassa.money.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.common.IconBadge
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.accountIcon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import java.time.LocalDate
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountField
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.AccountGroup
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Category
import app.kassa.money.domain.LoanField
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.CategoryPickerHost
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.NameField
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.loans.LoanFields
import app.kassa.money.ui.loans.loanDay
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * One form for every kind of account; picking a type decides which fields appear. Checking and building the account
 * is [AccountForm]'s job, shared with [EditAccountPage]. A loan or mortgage shows the loan setup instead of a balance
 * and is built by [LoanForm]; [onSaveLoan] gets the account and its terms.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddAccountSheet(
    groups: List<AccountGroup>,
    payFromOptions: List<Account>,
    takenNames: List<String>,
    categories: List<Category>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Account, String?) -> Unit,
    onSaveLoan: (Account, LoanTerms, String?) -> Unit,
    /** The "Add a person" row at the end of the type list; the caller closes this sheet and opens its person sheet. */
    onAddPerson: () -> Unit = {},
) {
    var typed by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(AccountForm(groupId = groups.firstOrNull()?.id)) }
    var problems by remember { mutableStateOf(emptyMap<AccountField, String>()) }
    var loan by remember { mutableStateOf(LoanForm.starting(today.loanDay())) }
    var loanProblems by remember { mutableStateOf(emptyMap<LoanField, String>()) }
    fun editLoan(field: LoanField?, change: (LoanForm) -> LoanForm) {
        loan = change(loan)
        if (field != null) loanProblems = loanProblems - field
    }
    // Each edit clears that field's problem; the rest stay until the next save.
    fun edit(field: AccountField?, change: (AccountForm) -> AccountForm) {
        form = change(form)
        if (field != null) problems = problems - field
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        CategoryPickerHost(categories, Modifier.padding(horizontal = 20.dp).imePadding().navigationBarsPadding(), accounts = payFromOptions) {
            if (!typed) {
                // Type first: a short list of what the account is, then a form with only that type's fields.
                Text("Add an account", style = KassaType.section, color = Kassa.colors.ink)
                TypeList(onPick = { t -> edit(null) { it.copy(type = t) }; typed = true }, onAddPerson = onAddPerson)
                Gap(24)
                return@CategoryPickerHost
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { typed = false }) { Icon(Chevron(left = true), "Back", tint = Kassa.colors.ink) }
                Column(Modifier.weight(1f)) {
                    Text("Add an account", style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(form.type.label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Gap(8)
            val isLoan = form.type == AccountType.LOAN
            AccountFields(
                form, problems, ::edit, groups, payFromOptions, today = today, showType = false,
                loanFields = { LoanFields(loan, loanProblems, ::editLoan, today, payFromOptions, categories) },
            )
            Gap(12)
            PrimaryButton(if (isLoan) "Add loan" else "Add account") {
                problems = form.problems(takenNames)
                if (isLoan) {
                    loanProblems = loan.problems()
                    // The loan's own figure stands in for the balance: what is owed becomes the starting balance.
                    if (problems.isEmpty() && loanProblems.isEmpty()) {
                        val account = form.copy(balance = Money.toInput(checkNotNull(loan.owed))).toAccount(Account(name = "", type = form.type), currentBalance = 0)
                        onSaveLoan(account, loan.toTerms(0), form.newGroupName)
                    }
                } else if (problems.isEmpty()) onSave(form.toAccount(Account(name = "", type = form.type), currentBalance = 0), form.newGroupName)
            }
            Gap(24)
        }
    }
}

/**
 * The fields of the account form: type, name, last 4, balance, card details and group. Shared by [AddAccountSheet]
 * and the first-run setup, which offers fewer [types] and no groups.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountFields(
    form: AccountForm,
    problems: Map<AccountField, String>,
    edit: (AccountField?, (AccountForm) -> AccountForm) -> Unit,
    groups: List<AccountGroup>,
    payFromOptions: List<Account>,
    types: List<AccountType> = AccountType.entries,
    showGroup: Boolean = true,
    /** The Type chips; the Add account sheet picks the type first, so it hides them. */
    showType: Boolean = true,
    /** Today, for the fixed deposit's maturity date; without it that field isn't offered. */
    today: LocalDate? = null,
    /** Replaces the balance field for a loan or mortgage, which has its own setup (see [LoanFields]). */
    loanFields: (@Composable () -> Unit)? = null,
) {
    if (showType) {
        Label("Type")
        FlowRow {
            types.forEach { t -> Chip(t.label, form.type == t, small = true) { edit(null) { it.copy(type = t) } } }
        }
    }
    NameField(form.name, { v -> edit(AccountField.NAME) { it.copy(name = v) } }, "Name", Modifier.fillMaxWidth(), problem = problems[AccountField.NAME])
    DigitsField(form.last4, { v -> edit(AccountField.LAST4) { it.copy(last4 = v) } }, "Last 4 digits (optional)", 4, Modifier.fillMaxWidth(),
        problem = problems[AccountField.LAST4])
    if (form.type == AccountType.LOAN && loanFields != null) loanFields()
    else AmountField(form.balance, { v -> edit(AccountField.BALANCE) { it.copy(balance = v) } },
        if (form.type.hasStatement) "Total balance today" else if (form.type.isDebt) "Outstanding balance today" else "Balance today", Modifier.fillMaxWidth(),
        problem = problems[AccountField.BALANCE], placeholder = "0", signed = true)
    if (form.type.hasMaturity && today != null) MaturityField(form.maturityDay, today) { d -> edit(null) { it.copy(maturityDay = d) } }
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
            app.kassa.money.ui.common.AccountChoiceRow(
                "Pay from", form.payFromId, payFromOptions, { id -> edit(null) { it.copy(payFromId = id) } },
                modifier = Modifier.padding(top = 10.dp), noneOption = "No account",
            )
        }
    }
    if (showGroup && groups.isNotEmpty()) {
        Label("Group")
        FlowRow {
            groups.forEach { g ->
                Chip(g.name, form.groupId == g.id && form.newGroup.isBlank(), small = true) { edit(AccountField.NEW_GROUP) { it.copy(groupId = g.id, newGroup = "") } }
            }
        }
    }
    if (showGroup) NameField(form.newGroup, { v -> edit(AccountField.NEW_GROUP) { it.copy(newGroup = v) } }, "Or a new group", Modifier.fillMaxWidth(),
        problem = problems[AccountField.NEW_GROUP])
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/** What each kind of account is, in a few words, under its name in the type list. */
private fun typeHint(t: AccountType): String = when (t) {
    AccountType.CHECKING -> "Day to day account"
    AccountType.SAVINGS -> "Money you set aside"
    AccountType.CASH -> "Notes and coins"
    AccountType.PREPAID -> "Loaded card or app balance"
    AccountType.FIXED_DEPOSIT -> "Locked until a date"
    AccountType.CREDIT_CARD -> "Statement and due day"
    AccountType.LOAN -> "Rate, term and payments"
    AccountType.INVESTMENT -> "Cash now, holdings after"
    AccountType.OTHER_ASSET -> "Something you own"
    AccountType.OTHER_DEBT -> "Something you owe"
}

/** The kinds of account in groups, as hairline rows with an icon, a name, a hint and a chevron; then Add a person. */
@Composable
private fun TypeList(onPick: (AccountType) -> Unit, onAddPerson: () -> Unit) {
    val groups = listOf(
        "Cash and bank" to listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.FIXED_DEPOSIT),
        "Credit and loans" to listOf(AccountType.CREDIT_CARD, AccountType.LOAN),
        "Investments" to listOf(AccountType.INVESTMENT),
        "Other" to listOf(AccountType.OTHER_ASSET, AccountType.OTHER_DEBT),
    )
    groups.forEach { (title, types) ->
        SectionHeader(title)
        types.forEach { t -> TypeRow(accountIcon(t), t.label, typeHint(t)) { onPick(t) } }
    }
    HorizontalDivider(color = Kassa.colors.line)
    TypeRow(androidx.compose.material.icons.Icons.Outlined.Person, "Add a person", "Someone you lend to, borrow from or split costs with", onAddPerson)
}

@Composable
private fun TypeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, onClick: () -> Unit) {
    HorizontalDivider(color = Kassa.colors.line)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, size = 36)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
    }
}
