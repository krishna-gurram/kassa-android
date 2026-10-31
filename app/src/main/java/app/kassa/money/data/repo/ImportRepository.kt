package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toEntity
import app.kassa.money.importer.ImportPlan

/** Replaces Kassa's data with an import from another app. */
class ImportRepository(private val db: KassaDatabase) {

    /**
     * All of it lands, or none of it: one database transaction. Accounts, categories, entries and budgets are replaced,
     * and so is everything that points at them: holdings, trades, waiting investment transfers, loan terms and
     * payments, bills, and the split shares of the old entries. People already in Kassa are kept (their starting
     * balances too); the plan's people join them (one with the same name is reused) and its bills are added.
     * The caller also calls [app.kassa.money.KassaApplication.forgetAccountReferences], since the phone's own settings
     * name old account ids.
     */
    suspend fun replaceWith(plan: ImportPlan) = db.withTransaction {
        db.transactions().deleteAll()
        db.budgets().deleteAll()
        db.categories().deleteAll()
        db.accounts().deleteAll()
        db.groups().deleteAll()
        // Loan terms and payments belong to the accounts and entries being replaced.
        db.loanTerms().deleteAll()
        db.loanChanges().deleteAll()
        db.loanPayments().deleteAll()
        // Rows that point at the accounts and entries just deleted would otherwise stay behind, pointing at nothing.
        db.holdings().deleteAll()
        db.trades().deleteAll()
        db.investmentTransfers().deleteAll()
        db.loans().deleteTiedToEntries()
        db.recurring().deleteAll()
        db.groups().insertAll(plan.groups.map { it.toEntity() })
        db.accounts().insertAll(plan.accounts.map { it.toEntity() })
        db.categories().insertAll(plan.categories.map { it.toEntity() })

        // The plan numbers its people from 1; map them onto people in Kassa by name, or add them.
        val existing = db.people().all()
        val personIds = plan.people.associate { p ->
            p.id to (existing.firstOrNull { it.name.equals(p.name, ignoreCase = true) }?.id ?: db.people().insert(p.copy(id = 0).toEntity()))
        }
        db.transactions().insertAll(plan.transactions.map { t -> t.copy(personId = t.personId?.let { personIds.getValue(it) }).toEntity() })
        db.budgets().insertAll(plan.budgets.map { it.toEntity() })
        if (plan.personLoans.isNotEmpty()) db.loans().insertAll(plan.personLoans.map { it.copy(id = 0, personId = personIds.getValue(it.personId)).toEntity() })
        plan.bills.forEach { db.recurring().insert(it.copy(id = 0).toEntity()) }
    }
}
