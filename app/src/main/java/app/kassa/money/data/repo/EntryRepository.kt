package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.SearchText
import app.kassa.money.domain.Split
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.effectOn
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
        // What was typed is text, never a pattern: "50%" finds "50%", not everything that starts with 50.
        return db.transactions().search(SearchText.contains(t), cents).map { rows -> rows.map { it.toDomain() } }
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
     * the draft breaks an input rule, or would put an entry on a closed account (an edit that keeps the accounts it
     * already had is fine). An edited transfer that still waits to be explained keeps its waiting row in step.
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
        val before = draft.id?.let { db.transactions().byId(it) }
        // Only the accounts this save newly touches must be open: an old entry on an account closed since can still be edited.
        val touched = setOfNotNull(tx.accountId, tx.toAccountId) - setOfNotNull(before?.accountId, before?.toAccountId)
        db.requireOpen(*touched.toTypedArray())
        val id = if (draft.id == null) db.transactions().insert(tx.toEntity()) else draft.id.also { db.transactions().update(tx.toEntity()) }
        if (before != null) syncWaitingTransfer(tx.copy(id = id))
        db.loans().deleteForTransaction(id)
        outcome?.owed?.takeIf { it.isNotEmpty() }?.let { owed ->
            db.loans().insertAll(owed.map { (person, amount) ->
                LoanEntry(personId = person, amount = amount, epochMillis = tx.epochMillis, note = tx.note, transactionId = id).toEntity()
            })
        }
        tx.copy(id = id)
    }

    /**
     * Saves a balance correction so that [accountId] shows [target] once it is in, dated [epochMillis]: the entry holds
     * the difference, nothing before it changes, and it is neither spending nor income (see [BalanceCorrection]). When
     * [editingId] names an earlier correction, that one is replaced and the difference is worked out without it. Throws
     * [IllegalArgumentException] with a message to show when there is nothing to correct or the figure is out of range.
     * Works for any account; the loan page is the first to offer it.
     */
    suspend fun correctBalance(accountId: Long, target: Long, epochMillis: Long, editingId: Long? = null): Transaction = db.withTransaction {
        InputRules.balanceProblem(target)?.let { throw IllegalArgumentException(it) }
        require(db.accounts().byId(accountId) != null) { "That account isn't there any more." }
        // A new correction is a new entry; changing one that is already there is not.
        if (editingId == null) db.requireOpen(accountId)
        val old = editingId?.let { db.transactions().byId(it)?.toDomain() }
        require(editingId == null || (old != null && BalanceCorrection.isCorrection(old.kind) && old.accountId == accountId)) { "That correction isn't there any more." }
        val without = (db.accounts().balanceOf(accountId) ?: 0) - (old?.effectOn(accountId) ?: 0)
        val change = BalanceCorrection.change(target, without)
        BalanceCorrection.problem(change)?.let { throw IllegalArgumentException(it) }
        val tx = checkNotNull(BalanceCorrection.entry(editingId ?: 0, accountId, change, epochMillis))
        if (editingId == null) tx.copy(id = db.transactions().insert(tx.toEntity())) else tx.also { db.transactions().update(it.toEntity()) }
    }

    /** Inserts an entry as is, for buys and sales that the investment repository records. */
    internal suspend fun insertRaw(t: Transaction): Long = db.transactions().insert(t.toEntity())

    /** Deletes an entry with its split, and stops waiting for it to be explained when it was a transfer into investments. */
    suspend fun delete(id: Long) = db.withTransaction {
        db.loans().deleteForTransaction(id)
        db.investmentTransfers().deleteForTransaction(id)
        db.transactions().delete(id)
    }

    /**
     * After an edit: a transfer into an investment account that still waits to be explained takes the entry's new amount, date
     * and account; one that no longer is such a transfer (another kind, or paid to a different kind of account) stops waiting.
     */
    private suspend fun syncWaitingTransfer(tx: Transaction) {
        val to = tx.toAccountId
        val stillAnInvestment = tx.kind == TxKind.TRANSFER && to != null && db.accounts().byId(to)?.type == AccountType.INVESTMENT.name
        if (stillAnInvestment) db.investmentTransfers().updateForTransaction(tx.id, to!!, tx.amount, tx.epochMillis)
        else db.investmentTransfers().deleteForTransaction(tx.id)
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
