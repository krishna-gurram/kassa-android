package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toEntity
import app.kassa.money.importer.ImportPlan

/** Replaces Kassa's data with a Money Manager import. */
class ImportRepository(private val db: KassaDatabase) {

    /** All of it lands, or none of it: one database transaction. People, bills and holdings are kept. */
    suspend fun replaceWith(plan: ImportPlan) {
        replace(plan)
        separateCategoryColors(db)
    }

    private suspend fun replace(plan: ImportPlan) = db.withTransaction {
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
