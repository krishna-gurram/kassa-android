package app.kassa.money.shared

import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.EditRules

/*
 * Editing what already exists on iOS (batch Q): an account, a card or a person. The rules are :domain's (`AccountForm`,
 * `PersonForm`, `EditRules`); this file turns them into flat values for Swift, the way `WriteBridge` does for adding. Reads that
 * need the ledger (`accountEdit`, `personEdit`, the problems and the changed cells) are members of `LedgerView`; they call the
 * helpers here.
 */

/** The Edit account form's starting values. [input] is what the form shows; [kinds] are the `AccountType` names it may change to (empty: fixed). */
data class AccountEditData(
    val found: Boolean,
    val input: AccountInput,
    val closed: Boolean,
    val archived: Boolean,
    val kinds: List<String>,
)

/** The Edit person form's starting values. [archiveBlocked] is true while money is open; [openSizeText] is how much (masked while hidden). */
data class PersonEditData(
    val found: Boolean,
    val name: String,
    val theyOwe: Boolean,
    val amount: String,
    val archived: Boolean,
    val archiveBlocked: Boolean,
    val openSizeText: String,
)

internal object EditParts {

    /** The columns of `accounts` an edit writes; id, group, sort order, archived, closed day and external id stay as they were. */
    private val owned = setOf("name", "type", "openingBalance", "statementDay", "dueDay", "payFromId", "creditLimit", "last4", "maturityDay")

    /** [a] as the form shows it, [balance] being its balance today. */
    fun input(a: Account, balance: Long): AccountInput {
        val f = AccountForm.of(a, balance)
        return AccountInput(a.type.name, f.name, f.last4, f.balance, f.statementDay, f.dueDay, f.limit, a.payFromId ?: 0L, a.maturityDay ?: 0)
    }

    /** The form for [input], which keeps [a]'s group (the iOS form has no group row). */
    fun form(a: Account, input: AccountInput): AccountForm? = input.form()?.copy(groupId = a.groupId)

    /** [a] with the form's values; the starting balance moves by the change so entries stay as they are. Call only when the form has no problem. */
    fun edited(a: Account, balance: Long, input: AccountInput): Account? = form(a, input)?.toAccount(a, balance)

    fun ownsColumn(name: String?): Boolean = name in owned

    fun cell(a: Account, name: String?): String? = when (name) {
        "name" -> a.name
        "type" -> a.type.name
        "openingBalance" -> a.openingBalance.toString()
        "statementDay" -> a.statementDay?.toString()
        "dueDay" -> a.dueDay?.toString()
        "payFromId" -> a.payFromId?.toString()
        "creditLimit" -> a.creditLimit?.toString()
        "last4" -> a.last4
        "maturityDay" -> a.maturityDay?.toString()
        else -> null
    }

    fun problems(a: Account, balance: Long, input: AccountInput, others: List<String>): List<FieldProblemData> {
        val form = form(a, input) ?: return listOf(FieldProblemData("NAME", "Pick a kind of account."))
        EditRules.typeProblem(a.type, form.type)?.let { return listOf(FieldProblemData("NAME", it)) }
        val found = form.problems(others)
        val list = app.kassa.money.domain.AccountField.entries.mapNotNull { f -> found[f]?.let { FieldProblemData(f.name, it) } }
        if (list.isNotEmpty()) return list
        val saved = form.toAccount(a, balance)
        if (saved.type.hasStatement) EditRules.payFromProblem(a.id, saved.payFromId)?.let { return listOf(FieldProblemData("PAY_FROM", it)) }
        EditRules.closedProblem(a, saved)?.let { return listOf(FieldProblemData("BALANCE", it)) }
        return emptyList()
    }
}

/** What the store asks of the rules when it writes an edit; plain values only. */
object EditBridge {

    /** True for the `accounts` columns (BackupRows order) an edit writes: the ones the form owns, so the rest stay as they were. */
    fun ownsAccountColumn(column: Int): Boolean = EditParts.ownsColumn(BackupRows.columns("accounts").getOrNull(column))

    /** True when the starting balance a person's form now holds differs from the stored one, so the store rewrites that line. */
    fun startingChanged(old: Long, typed: Long): Boolean = EditRules.startingChange(old, typed) != null
}
