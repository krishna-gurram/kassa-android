package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountGroup
import app.kassa.money.domain.InputRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Accounts with their current balances, and the groups they sit in. */
data class AccountsState(
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val balances: Map<Long, Long>,
)

/** Bank accounts, cards, investments and loans. */
class AccountRepository(private val db: KassaDatabase) {

    val state: Flow<AccountsState> = combine(
        db.groups().observeAll(),
        db.accounts().observeAll(),
        db.accounts().observeBalances(),
    ) { groups, accounts, balances ->
        AccountsState(groups.map { it.toDomain() }, accounts.map { it.toDomain() }, balances.associate { it.id to it.balance })
    }

    /** Adds an account, first creating its group when [newGroupName] is given. Returns the new id. */
    suspend fun add(account: Account, newGroupName: String?): Long = db.withTransaction {
        val clean = checked(account)
        val order = db.accounts().maxSortOrder() + 1
        db.accounts().insert(clean.copy(id = 0, groupId = groupFor(clean, newGroupName), sortOrder = order).toEntity())
    }

    /** Saves an edited account, first creating its group when [newGroupName] is given. */
    suspend fun update(account: Account, newGroupName: String? = null) = db.withTransaction {
        val clean = checked(account)
        db.accounts().update(clean.copy(groupId = groupFor(clean, newGroupName)).toEntity())
    }

    private suspend fun groupFor(account: Account, newGroupName: String?): Long? =
        if (newGroupName.isNullOrBlank()) account.groupId
        else {
            InputRules.nameProblem(newGroupName, "group")?.let { throw IllegalArgumentException(it) }
            db.groups().insert(AccountGroup(name = InputRules.clean(newGroupName), sortOrder = Int.MAX_VALUE / 2).toEntity())
        }

    /** The account as it will be stored, or [IllegalArgumentException] with a message to show. */
    private suspend fun checked(a: Account): Account {
        val others = db.accounts().all().filter { it.id != a.id }.map { it.name }
        InputRules.nameProblem(a.name, "account", taken = { n -> others.any { it.equals(n, ignoreCase = true) } })
            ?.let { throw IllegalArgumentException(it) }
        InputRules.balanceProblem(a.openingBalance)?.let { throw IllegalArgumentException(it) }
        a.last4?.let { l4 -> InputRules.last4Problem(l4)?.let { throw IllegalArgumentException(it) } }
        a.statementDay?.let { d -> InputRules.dayProblem(d, "statement day")?.let { throw IllegalArgumentException(it) } }
        a.dueDay?.let { d -> InputRules.dayProblem(d, "due day")?.let { throw IllegalArgumentException(it) } }
        a.creditLimit?.let { l -> InputRules.amountProblem(l, zeroAllowed = true)?.let { throw IllegalArgumentException(it) } }
        return a.copy(name = InputRules.clean(a.name), last4 = a.last4?.trim()?.ifEmpty { null })
    }
}
