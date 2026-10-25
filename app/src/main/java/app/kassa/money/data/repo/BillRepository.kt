package app.kassa.money.data.repo

import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Recurring
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Recurring bills. Each asks on its day; nothing posts by itself. */
class BillRepository(private val db: KassaDatabase) {

    val bills: Flow<List<Recurring>> = db.recurring().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun save(bill: Recurring) {
        InputRules.nameProblem(bill.name, "bill")?.let { throw IllegalArgumentException(it) }
        InputRules.dayProblem(bill.dayOfMonth, "day")?.let { throw IllegalArgumentException(it) }
        bill.fixedAmount?.let { a -> InputRules.amountProblem(a)?.let { throw IllegalArgumentException(it) } }
        val clean = bill.copy(name = InputRules.clean(bill.name))
        if (clean.id == 0L) db.recurring().insert(clean.toEntity()) else db.recurring().update(clean.toEntity())
    }

    suspend fun delete(id: Long) = db.recurring().delete(id)

    /** Marks the bill as dealt with for [month] (yyyyMM): approved, skipped or already entered. */
    suspend fun markHandled(bill: Recurring, month: Int) = db.recurring().update(bill.copy(handledMonth = month).toEntity())
}
