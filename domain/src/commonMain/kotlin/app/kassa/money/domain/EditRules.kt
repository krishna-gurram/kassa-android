package app.kassa.money.domain

import kotlin.math.abs

/**
 * The rules of editing an account, a card or a person that already exists, on top of [AccountForm] and [PersonForm] (which check
 * and build the fields). Plain Kotlin so every platform asks the same thing and the rules have JVM tests.
 */
object EditRules {

    /**
     * The kinds an account may be changed to. A loan keeps its terms and payments and an investment account its holdings, so
     * neither can change kind (and nothing else becomes one here: Add account makes them). Empty means the kind is fixed.
     */
    fun typeChoices(current: AccountType): List<AccountType> =
        if (current == AccountType.LOAN || current == AccountType.INVESTMENT) emptyList()
        else listOf(
            AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.FIXED_DEPOSIT,
            AccountType.CREDIT_CARD, AccountType.OTHER_ASSET, AccountType.OTHER_DEBT,
        )

    /** Why [new] is not an allowed kind for an account that is [current] now, or null. Keeping the kind is always fine. */
    fun typeProblem(current: AccountType, new: AccountType): String? =
        if (new == current || new in typeChoices(current)) null else "That kind of account can't be changed to that."

    /**
     * Why an edit can't be saved although the fields are fine, or null. A closed account takes nothing new (CLAUDE.md "Closed
     * accounts"), and changing its balance would add to it, so that is refused until it is reopened; its name and details can change.
     */
    fun closedProblem(before: Account, after: Account): String? =
        if (before.isClosed && before.openingBalance != after.openingBalance) EntryRules.closedMessage(before.name) else null

    /** A card can't be paid from itself; null when [payFromId] is fine (or none). */
    fun payFromProblem(accountId: Long, payFromId: Long?): String? =
        if (payFromId != null && payFromId == accountId) "A card can't be paid from itself." else null

    /** The sentence for an account that [accountId] edits while another one (or this one) is gone. */
    const val ACCOUNT_GONE = "That account is no longer here."

    /** The sentence for a person who is gone. */
    const val PERSON_GONE = "That person is no longer here."

    // ---------- people ----------

    /**
     * Archived people don't count in Receivable or Payable, so only a settled person (balance 0) can be archived. True when
     * [balance] (positive: they owe you) stops it. Restoring is always allowed.
     */
    fun archiveBlocked(balance: Long): Boolean = balance != 0L

    /** The size of an unsettled balance, for the sentence that says why archiving waits: always positive. */
    fun archiveBlockedSize(balance: Long): Long = abs(balance)

    /**
     * The starting balance to write after an edit, or null to leave it as it was: [typed] is what the form now holds (positive: they
     * owe you) and [old] what was stored.
     */
    fun startingChange(old: Long, typed: Long): Long? = typed.takeIf { it != old }

    /** The edit person form for someone whose starting balance is [starting] (positive: they owe you). */
    fun personForm(name: String, starting: Long): PersonForm = PersonForm(
        name = name.take(InputRules.NAME_LIMIT),
        theyOwe = starting >= 0,
        amount = if (starting == 0L) "" else Money.toInput(abs(starting)),
    )
}
