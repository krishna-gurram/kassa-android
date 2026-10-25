package app.kassa.money.domain

/** The fields of the account form that can have a problem. */
enum class AccountField { NAME, LAST4, BALANCE, STATEMENT_DAY, DUE_DAY, LIMIT, NEW_GROUP }

/**
 * The add and edit account forms, as typed. Both screens keep one of these, so an account is checked and built the
 * same way wherever it's entered.
 *
 * [balance] is today's balance as the person sees it: what's owed for a debt (a minus for a credit, like an overpaid
 * card), what's there for anything else (a minus when overdrawn). Saving turns it into a starting balance, so
 * existing entries never change.
 */
data class AccountForm(
    val type: AccountType = AccountType.CHECKING,
    val name: String = "",
    val last4: String = "",
    val balance: String = "",
    val statementDay: String = "",
    val dueDay: String = "",
    val limit: String = "",
    val payFromId: Long? = null,
    val groupId: Long? = null,
    /** A group to create and put the account in; blank to use [groupId]. */
    val newGroup: String = "",
) {
    private val todayCents: Long? get() = if (balance.isBlank()) 0L else Money.parseCents(balance)

    /** Each field's problem; empty when the form can be saved. [takenNames] are the other accounts' names. */
    fun problems(takenNames: Collection<String>): Map<AccountField, String> = buildMap {
        InputRules.nameProblem(name, "account", taken = { n -> takenNames.any { it.equals(n, ignoreCase = true) } })
            ?.let { put(AccountField.NAME, it) }
        InputRules.last4Problem(last4)?.let { put(AccountField.LAST4, it) }
        InputRules.balanceProblem(todayCents)?.let { put(AccountField.BALANCE, it) }
        if (type.hasStatement) {
            InputRules.dayProblem(statementDay.toIntOrNull(), "statement day")?.let { put(AccountField.STATEMENT_DAY, it) }
            InputRules.dayProblem(dueDay.toIntOrNull(), "due day")?.let { put(AccountField.DUE_DAY, it) }
            if (limit.isNotBlank()) InputRules.amountProblem(Money.parseCents(limit), zeroAllowed = true)?.let { put(AccountField.LIMIT, it) }
        }
        if (newGroup.isNotBlank()) InputRules.nameProblem(newGroup, "group")?.let { put(AccountField.NEW_GROUP, it) }
    }

    /**
     * The account to save: [base] with this form's values. [currentBalance] is the account's balance today
     * (0 for a new one); the starting balance moves by the change so entries stay as they are.
     * Call only when [problems] is empty.
     */
    fun toAccount(base: Account, currentBalance: Long): Account {
        val typed = checkNotNull(todayCents) { "toAccount called with an invalid balance" }
        // Debts are stored negative, so net worth adds up directly.
        val today = if (type.isDebt) -typed else typed
        val card = type.hasStatement
        return base.copy(
            name = InputRules.clean(name),
            type = type,
            last4 = last4.trim().ifEmpty { null },
            openingBalance = base.openingBalance + (today - currentBalance),
            statementDay = if (card) statementDay.toInt() else null,
            dueDay = if (card) dueDay.toInt() else null,
            creditLimit = if (card && limit.isNotBlank()) Money.parseCents(limit) else null,
            payFromId = if (card) payFromId else null,
            groupId = groupId,
        )
    }

    /** The new group's name to create, or null. */
    val newGroupName: String? get() = newGroup.takeIf { it.isNotBlank() }?.let(InputRules::clean)

    companion object {
        /** The form for editing [a], whose balance today is [balance]. */
        fun of(a: Account, balance: Long) = AccountForm(
            type = a.type,
            name = a.name.take(InputRules.NAME_MAX),
            last4 = a.last4.orEmpty(),
            balance = Money.toInput(if (a.type.isDebt) -balance else balance),
            statementDay = a.statementDay?.toString().orEmpty(),
            dueDay = a.dueDay?.toString().orEmpty(),
            limit = a.creditLimit?.let(Money::toInput).orEmpty(),
            payFromId = a.payFromId,
            groupId = a.groupId,
        )
    }
}
