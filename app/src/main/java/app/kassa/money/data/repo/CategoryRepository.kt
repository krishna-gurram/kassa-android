package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.Budget
import app.kassa.money.domain.Category
import app.kassa.money.domain.InputRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Categories, subcategories and their budgets. Archived categories are the ones marked hidden. */
class CategoryRepository(private val db: KassaDatabase) {

    val categories: Flow<List<Category>> = db.categories().observeAll().map { rows -> rows.map { it.toDomain() } }

    val budgets: Flow<List<Budget>> = db.budgets().observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Creates or updates a category and returns its id. Names must be unique among siblings (top-level categories of
     * the same kind, or subcategories of the same parent).
     */
    suspend fun save(category: Category): Long = db.withTransaction {
        val siblings = db.categories().all().filter { it.id != category.id && it.parentId == category.parentId && it.kind == category.kind.name }
        InputRules.nameProblem(category.name, if (category.parentId == null) "category" else "subcategory", InputRules.CATEGORY_NAME_MAX,
            taken = { n -> siblings.any { it.name.equals(n, ignoreCase = true) } })?.let { throw IllegalArgumentException(it) }
        val clean = category.copy(name = InputRules.clean(category.name))
        if (clean.id == 0L) db.categories().insert(clean.copy(sortOrder = db.categories().maxSortOrder() + 1).toEntity())
        else clean.id.also { db.categories().update(clean.toEntity()) }
    }

    /** Sets the default monthly budget ([month] 0) or a one-month override (yyyyMM). Null removes it. */
    suspend fun setBudget(categoryId: Long, month: Int, amount: Long?) {
        if (amount == null) db.budgets().delete(categoryId, month)
        else {
            InputRules.amountProblem(amount)?.let { throw IllegalArgumentException(it) }
            db.budgets().upsert(Budget(categoryId, month, amount).toEntity())
        }
    }

    /** Moves every entry from [from] into [into], then archives [from]. */
    suspend fun merge(from: Category, into: Long) = db.withTransaction {
        db.categories().moveEntries(from.id, into)
        db.categories().update(from.copy(hidden = true).toEntity())
    }
}
