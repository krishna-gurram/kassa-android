package app.kassa.money.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
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
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.CategoryPickerHost
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.IconBadge
import app.kassa.money.ui.common.NameField
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.accountIcon
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.loans.LoanFields
import app.kassa.money.ui.loans.loanDay
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalDate

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
                Text(stringResource(R.string.accounts_add_an_account), style = KassaType.section, color = Kassa.colors.ink)
                TypeList(onPick = { t -> edit(null) { it.copy(type = t) }; typed = true }, onAddPerson = onAddPerson)
                Gap(24)
                return@CategoryPickerHost
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { typed = false }) { Icon(Chevron(left = true), stringResource(R.string.accounts_back), tint = Kassa.colors.ink) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.accounts_add_an_account), style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            PrimaryButton(if (isLoan) stringResource(R.string.accounts_add_loan) else stringResource(R.string.accounts_add_account)) {
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
        Label(stringResource(R.string.accounts_type))
        FlowRow {
            types.forEach { t -> Chip(t.label, form.type == t, small = true) { edit(null) { it.copy(type = t) } } }
        }
    }
    NameField(form.name, { v -> edit(AccountField.NAME) { it.copy(name = v) } }, stringResource(R.string.accounts_name), Modifier.fillMaxWidth(), problem = problems[AccountField.NAME])
    DigitsField(form.last4, { v -> edit(AccountField.LAST4) { it.copy(last4 = v) } }, stringResource(R.string.accounts_last4_optional), 4, Modifier.fillMaxWidth(),
        problem = problems[AccountField.LAST4])
    if (form.type == AccountType.LOAN && loanFields != null) loanFields()
    else AmountField(form.balance, { v -> edit(AccountField.BALANCE) { it.copy(balance = v) } },
        if (form.type.hasStatement) stringResource(R.string.accounts_total_balance_today) else if (form.type.isDebt) stringResource(R.string.accounts_outstanding_balance_today) else stringResource(R.string.accounts_balance_today), Modifier.fillMaxWidth(),
        problem = problems[AccountField.BALANCE], placeholder = "0", signed = true)
    if (form.type.hasMaturity && today != null) MaturityField(form.maturityDay, today) { d -> edit(null) { it.copy(maturityDay = d) } }
    if (form.type.hasStatement) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DigitsField(form.statementDay, { v -> edit(AccountField.STATEMENT_DAY) { it.copy(statementDay = v) } }, stringResource(R.string.accounts_statement_day), 2,
                Modifier.weight(1f), problem = problems[AccountField.STATEMENT_DAY], placeholder = stringResource(R.string.accounts_one_to_31))
            DigitsField(form.dueDay, { v -> edit(AccountField.DUE_DAY) { it.copy(dueDay = v) } }, stringResource(R.string.accounts_due_day), 2,
                Modifier.weight(1f), problem = problems[AccountField.DUE_DAY], placeholder = stringResource(R.string.accounts_one_to_31))
        }
        AmountField(form.limit, { v -> edit(AccountField.LIMIT) { it.copy(limit = v) } }, stringResource(R.string.accounts_credit_limit_optional), Modifier.fillMaxWidth(),
            problem = problems[AccountField.LIMIT])
        if (payFromOptions.isNotEmpty()) {
            app.kassa.money.ui.common.AccountChoiceRow(
                stringResource(R.string.accounts_pay_from), form.payFromId, payFromOptions, { id -> edit(null) { it.copy(payFromId = id) } },
                modifier = Modifier.padding(top = 10.dp), noneOption = stringResource(R.string.accounts_no_account),
            )
        }
    }
    if (showGroup && groups.isNotEmpty()) {
        Label(stringResource(R.string.accounts_group))
        FlowRow {
            groups.forEach { g ->
                Chip(g.name, form.groupId == g.id && form.newGroup.isBlank(), small = true) { edit(AccountField.NEW_GROUP) { it.copy(groupId = g.id, newGroup = "") } }
            }
        }
    }
    if (showGroup) NameField(form.newGroup, { v -> edit(AccountField.NEW_GROUP) { it.copy(newGroup = v) } }, stringResource(R.string.accounts_or_new_group), Modifier.fillMaxWidth(),
        problem = problems[AccountField.NEW_GROUP])
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/** What each kind of account is, in a few words, under its name in the type list. */
@Composable
private fun typeHint(t: AccountType): String = when (t) {
    AccountType.CHECKING -> stringResource(R.string.accounts_hint_checking)
    AccountType.SAVINGS -> stringResource(R.string.accounts_hint_savings)
    AccountType.CASH -> stringResource(R.string.accounts_hint_cash)
    AccountType.PREPAID -> stringResource(R.string.accounts_hint_prepaid)
    AccountType.FIXED_DEPOSIT -> stringResource(R.string.accounts_hint_fixed_deposit)
    AccountType.CREDIT_CARD -> stringResource(R.string.accounts_hint_credit_card)
    AccountType.LOAN -> stringResource(R.string.accounts_hint_loan)
    AccountType.INVESTMENT -> stringResource(R.string.accounts_hint_investment)
    AccountType.OTHER_ASSET -> stringResource(R.string.accounts_hint_other_asset)
    AccountType.OTHER_DEBT -> stringResource(R.string.accounts_hint_other_debt)
}

/** The kinds of account in groups, as hairline rows with an icon, a name, a hint and a chevron; then Add a person. */
@Composable
private fun TypeList(onPick: (AccountType) -> Unit, onAddPerson: () -> Unit) {
    val groups = listOf(
        stringResource(R.string.accounts_kind_cash_bank) to listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.FIXED_DEPOSIT),
        stringResource(R.string.accounts_kind_credit_loans) to listOf(AccountType.CREDIT_CARD, AccountType.LOAN),
        stringResource(R.string.accounts_kind_investments) to listOf(AccountType.INVESTMENT),
        stringResource(R.string.accounts_kind_other) to listOf(AccountType.OTHER_ASSET, AccountType.OTHER_DEBT),
    )
    groups.forEach { (title, types) ->
        SectionHeader(title)
        types.forEach { t -> TypeRow(accountIcon(t), t.label, typeHint(t)) { onPick(t) } }
    }
    HorizontalDivider(color = Kassa.colors.line)
    TypeRow(androidx.compose.material.icons.Icons.Outlined.Person, stringResource(R.string.accounts_add_a_person), stringResource(R.string.accounts_add_person_hint), onAddPerson)
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
