package dev.krishnagurram.kassa.data.repo

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toDomain
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.LoanEntry
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Split
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** An entry as the entry page builds it, before it's saved. */
data class EntryDraft(
    val id: Long?,
    val kind: TxKind,
    val amount: Long,
    val accountId: Long?,
    val toAccountId: Long?,
    val categoryId: Long?,
    /** For person payments: who the money went to or came from. */
    val personId: Long?,
    val epochMillis: Long,
    val note: String,
    /** People sharing an expense with you. Empty when it's all yours. */
    val splitWith: List<Long> = emptyList(),
    /** Each person's share; null means equal shares between you and [splitWith]. */
    val shares: Map<Long, Long>? = null,
    /** Who paid when it wasn't you. The entry then moves none of your accounts. */
    val paidBy: Long? = null,
)

/** Entries: reading them, and saving or deleting one together with its split. */
class EntryRepository(private val db: KassaDatabase) {

    val count: Flow<Int> = db.transactions().observeCount()

    fun between(fromMillis: Long, toMillis: Long): Flow<List<Transaction>> =
        db.transactions().observeBetween(fromMillis, toMillis).map { rows -> rows.map { it.toDomain() } }

    /** Every entry ever recorded whose text (note, category, account, person) or exact amount matches. */
    fun search(text: String): Flow<List<Transaction>> {
        val t = text.trim()
        val cents = Money.parseCents(t)?.takeIf { it > 0 } ?: -1L
        return db.transactions().search("%$t%", cents).map { rows -> rows.map { it.toDomain() } }
    }

    /** Every entry that moved money in or out of one account, newest first. */
    fun forAccount(accountId: Long): Flow<List<Transaction>> =
        db.transactions().observeForAccount(accountId).map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: Long): Transaction? = db.transactions().byId(id)?.toDomain()

    /** Who shares an entry: person to amount (positive, they owe you; negative, you owe them). */
    suspend fun sharesFor(txId: Long): Map<Long, Long> =
        db.loans().forTransaction(txId).groupBy { it.personId }.mapValues { (_, rows) -> rows.sumOf { it.amount } }

    suspend fun frequentNotes(kind: TxKind): List<String> = db.transactions().frequentNotes(kind.name)

    /**
     * Saves a new or edited entry and rewrites its split, in one database transaction. A split stores your share
     * on the entry and one loan entry per person. Throws [IllegalArgumentException] with a message to show when
     * the draft breaks an input rule.
     */
    suspend fun save(draft: EntryDraft): Transaction = db.withTransaction {
        check(draft)
        val shared = draft.kind == TxKind.EXPENSE && draft.splitWith.isNotEmpty()
        val paidBy = if (shared) draft.paidBy else null
        val outcome = if (shared) {
            val shares = draft.shares?.filterKeys { it in draft.splitWith } ?: Split.equalShares(draft.amount, draft.splitWith)
            require(shares.values.sum() <= draft.amount) { "The shares add up to more than ${Money.format(draft.amount)}." }
            Split.outcome(draft.amount, shares, paidBy)
        } else null

        val tx = Transaction(
            id = draft.id ?: 0,
            kind = draft.kind,
            amount = draft.amount,
            accountId = if (paidBy != null) null else draft.accountId,
            toAccountId = if (draft.kind == TxKind.TRANSFER) draft.toAccountId else null,
            categoryId = if (draft.kind == TxKind.EXPENSE || draft.kind == TxKind.INCOME) draft.categoryId else null,
            personId = paidBy ?: draft.personId,
            epochMillis = draft.epochMillis,
            note = InputRules.clean(draft.note),
            myShare = outcome?.myShare,
        )
        val id = if (draft.id == null) db.transactions().insert(tx.toEntity()) else draft.id.also { db.transactions().update(tx.toEntity()) }
        db.loans().deleteForTransaction(id)
        outcome?.owed?.takeIf { it.isNotEmpty() }?.let { owed ->
            db.loans().insertAll(owed.map { (person, amount) ->
                LoanEntry(personId = person, amount = amount, epochMillis = tx.epochMillis, note = tx.note, transactionId = id).toEntity()
            })
        }
        tx.copy(id = id)
    }

    /** Inserts an entry as is, for buys and sales that the investment repository records. */
    internal suspend fun insertRaw(t: Transaction): Long = db.transactions().insert(t.toEntity())

    suspend fun delete(id: Long) = db.withTransaction {
        db.loans().deleteForTransaction(id)
        db.transactions().delete(id)
    }

    private fun check(d: EntryDraft) {
        InputRules.amountProblem(d.amount)?.let { throw IllegalArgumentException(it) }
        InputRules.noteProblem(d.note)?.let { throw IllegalArgumentException(it) }
        when (d.kind) {
            TxKind.EXPENSE, TxKind.INCOME -> require(d.categoryId != null) { "Pick a category." }
            TxKind.TRANSFER -> require(d.toAccountId != null && d.toAccountId != d.accountId) { "Pick where the money went." }
            else -> {}
        }
        val someoneElsePaid = d.kind == TxKind.EXPENSE && d.paidBy != null && d.splitWith.isNotEmpty()
        require(d.accountId != null || someoneElsePaid) { "Pick an account." }
    }
}
