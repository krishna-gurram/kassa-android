package dev.krishnagurram.kassa.data.repo

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.importer.ImportPlan

/** Replaces Kassa's data with a Money Manager import. */
class ImportRepository(private val db: KassaDatabase) {

    /** All of it lands, or none of it: one database transaction. People, bills and holdings are kept. */
    suspend fun replaceWith(plan: ImportPlan) = db.withTransaction {
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
