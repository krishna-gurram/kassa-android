package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Recurring bills. Each asks on its day; nothing posts by itself. */
class BillRepository(private val db: KassaDatabase, private val entries: EntryRepository) {

    val bills: Flow<List<Recurring>> = db.recurring().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun save(bill: Recurring) {
        InputRules.nameProblem(bill.name, "bill")?.let { throw IllegalArgumentException(it) }
        InputRules.dayProblem(bill.dayOfMonth, "day")?.let { throw IllegalArgumentException(it) }
        bill.fixedAmount?.let { a -> InputRules.amountProblem(a)?.let { throw IllegalArgumentException(it) } }
        val clean = bill.copy(name = InputRules.clean(bill.name))
        if (clean.id == 0L) db.recurring().insert(clean.toEntity()) else db.recurring().update(clean.toEntity())
    }

    suspend fun delete(id: Long) = db.recurring().delete(id)

    /**
     * Marks the bill as dealt with for [month] (yyyyMM): approved, skipped or already entered. Only that one field changes,
     * so a bill edited meanwhile keeps its edits; a bill that is gone is left gone.
     */
    suspend fun markHandled(bill: Recurring, month: Int) { db.recurring().setHandledMonth(bill.id, month) }

    /**
     * Approves a bill: saves [draft] as its entry and marks [month] handled, in one database transaction, so a failure of
     * either leaves neither. A bill deleted in the meantime fails with a message to show, and no entry is added.
     */
    suspend fun approve(bill: Recurring, draft: EntryDraft, month: Int): Transaction = db.withTransaction {
        val saved = entries.save(draft)
        require(db.recurring().setHandledMonth(bill.id, month) > 0) { "That bill is no longer there." }
        saved
    }
}
