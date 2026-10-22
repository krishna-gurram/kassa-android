package dev.krishnagurram.kassa.data

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toDomain
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountGroup
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.importer.ImportPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Accounts with their current balance, plus the groups they sit in. */
data class AccountsState(
    val groups: List<AccountGroup>,
    val accounts: List<Account>,
    val balances: Map<Long, Long>,
)

class KassaRepository(private val db: KassaDatabase) {

    val entryCount: Flow<Int> = db.transactions().observeCount()

    val accounts: Flow<AccountsState> = combine(
        db.groups().observeAll(),
        db.accounts().observeAll(),
        db.accounts().observeBalances(),
    ) { g, a, b ->
        AccountsState(g.map { it.toDomain() }, a.map { it.toDomain() }, b.associate { it.id to it.balance })
    }

    val categories: Flow<List<Category>> = db.categories().observeAll().map { list -> list.map { it.toDomain() } }

    fun transactionsBetween(fromMillis: Long, toMillis: Long): Flow<List<Transaction>> =
        db.transactions().observeBetween(fromMillis, toMillis).map { list -> list.map { it.toDomain() } }

    /** Replaces everything with an import. Runs in one database transaction: all of it lands, or none of it. */
    suspend fun replaceWith(plan: ImportPlan) {
        db.withTransaction {
            db.transactions().deleteAll()
            db.budgets().deleteAll()
            db.categories().deleteAll()
            db.accounts().deleteAll()
            db.groups().deleteAll()
            db.groups().insertAll(plan.groups.map { it.toEntity() })
            db.accounts().insertAll(plan.accounts.map { it.toEntity() })
            db.categories().insertAll(plan.categories.map { it.toEntity() })
            db.transactions().insertAll(plan.transactions.map { it.toEntity() })
            db.budgets().insertAll(plan.budgets.map { it.toEntity() })
        }
    }

    /** No limit on accounts: each one is just a row. */
    suspend fun addAccount(account: Account, newGroupName: String?): Long = db.withTransaction {
        val groupId = if (!newGroupName.isNullOrBlank()) {
            db.groups().insert(AccountGroup(name = newGroupName.trim(), sortOrder = Int.MAX_VALUE / 2).toEntity())
        } else account.groupId
        val order = db.accounts().maxSortOrder() + 1
        db.accounts().insert(account.copy(id = 0, groupId = groupId, sortOrder = order).toEntity())
    }

    suspend fun updateAccount(account: Account) = db.accounts().update(account.toEntity())
}
