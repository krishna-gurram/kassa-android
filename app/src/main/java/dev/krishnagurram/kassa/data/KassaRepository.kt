package dev.krishnagurram.kassa.data

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toDomain
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountGroup
import dev.krishnagurram.kassa.domain.Budget
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.LoanEntry
import dev.krishnagurram.kassa.domain.Person
import dev.krishnagurram.kassa.domain.Split
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
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

/** People with their loan entries and the person payments that settle them. */
data class PeopleState(
    val people: List<Person>,
    val loans: List<LoanEntry>,
    val payments: List<Transaction>,
)

/** An entry as the add/edit sheet builds it. [splitWith] lists people sharing an expense equally. */
data class EntryDraft(
    val id: Long?,
    val kind: TxKind,
    val amount: Long,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    val personId: Long?,
    val epochMillis: Long,
    val note: String,
    val splitWith: List<Long>,
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

    val budgets: Flow<List<Budget>> = db.budgets().observeAll().map { list -> list.map { it.toDomain() } }

    val people: Flow<PeopleState> = combine(
        db.people().observeAll(),
        db.loans().observeAll(),
        db.transactions().observeWithPeople(),
    ) { p, l, t -> PeopleState(p.map { it.toDomain() }, l.map { it.toDomain() }, t.map { it.toDomain() }) }

    fun transactionsBetween(fromMillis: Long, toMillis: Long): Flow<List<Transaction>> =
        db.transactions().observeBetween(fromMillis, toMillis).map { list -> list.map { it.toDomain() } }

    /** Searches every transaction ever recorded, not just one month. Matches names and exact amounts. */
    fun search(text: String): Flow<List<Transaction>> {
        val t = text.trim()
        val like = "%$t%"
        val cents = dev.krishnagurram.kassa.domain.Money.parseCents(t)?.takeIf { it > 0 } ?: -1L
        return db.transactions().search(like, cents).map { list -> list.map { it.toDomain() } }
    }

    /** Every entry that moved money in or out of one account, newest first. */
    fun accountHistory(accountId: Long): Flow<List<Transaction>> =
        db.transactions().observeForAccount(accountId).map { list -> list.map { it.toDomain() } }

    suspend fun transaction(id: Long): Transaction? = db.transactions().byId(id)?.toDomain()

    suspend fun splitPeopleFor(txId: Long): List<Long> = db.loans().forTransaction(txId).map { it.personId }

    suspend fun frequentNotes(kind: TxKind): List<String> = db.transactions().frequentNotes(kind.name)

    /**
     * Saves a new or edited entry in one database transaction. A split expense stores your share on the entry
     * and one loan entry per person for their part, so editing the split later rewrites those loan entries.
     */
    suspend fun save(draft: EntryDraft): Transaction = db.withTransaction {
        val split = if (draft.kind == TxKind.EXPENSE && draft.splitWith.isNotEmpty()) Split.equal(draft.amount, draft.splitWith.size) else null
        val tx = Transaction(
            id = draft.id ?: 0,
            kind = draft.kind,
            amount = draft.amount,
            accountId = draft.accountId,
            toAccountId = if (draft.kind == TxKind.TRANSFER) draft.toAccountId else null,
            categoryId = if (draft.kind == TxKind.EXPENSE || draft.kind == TxKind.INCOME) draft.categoryId else null,
            personId = draft.personId,
            epochMillis = draft.epochMillis,
            note = draft.note.trim(),
            myShare = split?.mine,
        )
        val id = if (draft.id == null) db.transactions().insert(tx.toEntity()) else {
            db.transactions().update(tx.toEntity()); draft.id
        }
        db.loans().deleteForTransaction(id)
        if (split != null) {
            db.loans().insertAll(draft.splitWith.mapIndexed { i, pid ->
                LoanEntry(personId = pid, amount = split.others[i], epochMillis = draft.epochMillis, note = tx.note, transactionId = id).toEntity()
            })
        }
        tx.copy(id = id)
    }

    suspend fun delete(id: Long) = db.withTransaction {
        db.loans().deleteForTransaction(id)
        db.transactions().delete(id)
    }

    /** Category of the most recent entry with this note, for prefilling captured alerts. */
    suspend fun lastCategoryFor(note: String): Long? = db.transactions().lastCategoryForNote(note)

    suspend fun saveRaw(t: Transaction): Long = db.transactions().insert(t.toEntity())

    suspend fun addPerson(name: String): Long = db.people().insert(Person(name = name.trim()).toEntity())

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

    /** Saves an edited account, creating its group first when a new group name was typed. */
    suspend fun updateAccount(account: Account, newGroupName: String?) = db.withTransaction {
        val groupId = if (!newGroupName.isNullOrBlank()) {
            db.groups().insert(AccountGroup(name = newGroupName.trim(), sortOrder = Int.MAX_VALUE / 2).toEntity())
        } else account.groupId
        db.accounts().update(account.copy(groupId = groupId).toEntity())
    }

    /** Creates or updates a category. Subcategories always follow their parent's kind. */
    suspend fun saveCategory(category: Category): Long = db.withTransaction {
        if (category.id == 0L) {
            db.categories().insert(category.copy(sortOrder = db.categories().maxSortOrder() + 1).toEntity())
        } else {
            db.categories().update(category.toEntity()); category.id
        }
    }

    /** Sets the default monthly budget ([month] 0) or a one-month override. Null removes it. */
    suspend fun setBudget(categoryId: Long, month: Int, amount: Long?) {
        if (amount == null) db.budgets().delete(categoryId, month)
        else db.budgets().upsert(Budget(categoryId, month, amount).toEntity())
    }

    /** Merges one category into another: every entry moves, then the old category is hidden. */
    suspend fun mergeCategory(from: Category, into: Long) = db.withTransaction {
        db.categories().moveEntries(from.id, into)
        db.categories().update(from.copy(hidden = true).toEntity())
    }

    /**
     * Turns an account that was standing in for "money between me and others" (like a Splitwise account) into a
     * person. The person starts with the account's balance (positive: they owe you), the account is archived,
     * and its history stays as it was, so past months don't change.
     */
    suspend fun convertAccountToPerson(account: Account, balance: Long, personName: String): Long = db.withTransaction {
        val pid = db.people().insert(Person(name = personName.trim()).toEntity())
        if (balance != 0L) db.loans().insertAll(listOf(
            LoanEntry(personId = pid, amount = balance, epochMillis = System.currentTimeMillis(), note = "Balance from ${account.name}").toEntity()
        ))
        db.accounts().update(account.copy(archived = true).toEntity())
        pid
    }
}

/* ---------- Milestone 3: assist ---------- */

data class AssistState(
    val recurring: List<dev.krishnagurram.kassa.domain.Recurring>,
    val pending: List<dev.krishnagurram.kassa.data.db.PendingEntity>,
    val holdings: List<dev.krishnagurram.kassa.domain.Holding>,
    val rules: List<dev.krishnagurram.kassa.data.db.AlertRuleEntity>,
)

class AssistRepository(private val db: KassaDatabase, private val main: KassaRepository) {

    val state: Flow<AssistState> = combine(
        db.recurring().observeAll(), db.pending().observeAll(), db.holdings().observeAll(), db.alertRules().observeAll(),
    ) { r, p, h, a -> AssistState(r.map { it.toDomain() }, p, h.map { it.toDomain() }, a) }

    suspend fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring) {
        if (r.id == 0L) db.recurring().insert(r.toEntity()) else db.recurring().update(r.toEntity())
    }

    suspend fun deleteRecurring(id: Long) = db.recurring().delete(id)

    suspend fun markHandled(r: dev.krishnagurram.kassa.domain.Recurring, month: Int) =
        db.recurring().update(r.copy(handledMonth = month).toEntity())

    suspend fun dismiss(pendingId: Long) = db.pending().delete(pendingId)

    /**
     * Stores a parsed bank alert as a pending item. The account comes from the alert rules (app plus last four
     * digits); the category comes from the last entry with the same merchant. Repeats within two minutes are
     * dropped, since some banks post the same alert twice.
     */
    suspend fun capture(alert: dev.krishnagurram.kassa.domain.ParsedAlert, source: String, at: Long): Boolean = db.withTransaction {
        if (db.pending().recentDuplicates(alert.amount, source, at - 120_000) > 0) return@withTransaction false
        val rules = db.alertRules().all().filter { it.packageName == source }
        val account = rules.firstOrNull { it.last4 != null && it.last4 == alert.last4 }?.accountId ?: rules.firstOrNull { it.last4 == null }?.accountId
        val category = alert.merchant?.let { m -> main.lastCategoryFor(m) }
        db.pending().insert(
            dev.krishnagurram.kassa.data.db.PendingEntity(
                type = "ALERT", amount = alert.amount, merchant = alert.merchant, accountId = account, categoryId = category,
                epochMillis = at, source = source, isCredit = alert.isCredit, txId = null,
            )
        )
        true
    }

    suspend fun addAllocation(txId: Long, amount: Long, accountId: Long, at: Long) {
        db.pending().insert(
            dev.krishnagurram.kassa.data.db.PendingEntity(
                type = "ALLOCATE", amount = amount, merchant = null, accountId = accountId, categoryId = null,
                epochMillis = at, source = null, isCredit = false, txId = txId,
            )
        )
    }

    /** Records a purchase of shares: cash leaves the investment account and the holding grows. */
    suspend fun buy(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long, pendingId: Long?) = db.withTransaction {
        val t = ticker.trim().uppercase()
        val cost = Math.round(microShares / 1_000_000.0 * price)
        main.saveRaw(Transaction(kind = TxKind.BUY, amount = cost, accountId = accountId, epochMillis = at, note = t))
        val existing = db.holdings().find(accountId, t)
        if (existing == null) db.holdings().insert(dev.krishnagurram.kassa.data.db.HoldingEntity(accountId = accountId, ticker = t, microShares = microShares, price = price, priceMillis = at))
        else db.holdings().update(existing.copy(microShares = existing.microShares + microShares, price = price, priceMillis = at))
        pendingId?.let { db.pending().delete(it) }
    }

    suspend fun saveHolding(h: dev.krishnagurram.kassa.domain.Holding) {
        if (h.id == 0L) db.holdings().insert(h.toEntity()) else db.holdings().update(h.toEntity())
    }

    suspend fun deleteHolding(id: Long) = db.holdings().delete(id)

    suspend fun addRule(packageName: String, last4: String?, accountId: Long) =
        db.alertRules().insert(dev.krishnagurram.kassa.data.db.AlertRuleEntity(packageName = packageName, last4 = last4?.takeIf { it.isNotBlank() }, accountId = accountId))

    suspend fun deleteRule(id: Long) = db.alertRules().delete(id)
}
