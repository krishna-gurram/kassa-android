package app.kassa.money.domain

/**
 * What a budget line says and which writes an edit of one category's budgets makes. Plain Kotlin, so iOS and Android read the same
 * rules and the JVM tests prove them. A budget row is `(categoryId, month, amount)`: [Budget.month] 0 is the amount for every month,
 * a yyyymm key (202610) is a one-month override that wins over it (`Budgets.amountFor`). An amount is above zero and within
 * `InputRules.AMOUNT_MAX`; no amount at all means no row. Only a top-level expense category has a budget.
 */
object BudgetRules {

    /** One category against its budget in one budget month. [budget] is null with none (a stored 0 counts as none, like the lines of Insights). */
    data class Line(val categoryId: Long, val budget: Long?, val spent: Long) {
        /** Past the budget; never for a category without one. */
        val over: Boolean get() = budget != null && spent > budget

        /** What is left (positive) or by how much it is past (negative); zero with no budget. */
        val left: Long get() = if (budget == null) 0L else budget - spent

        /** The part of the budget used, 0 to 1000 (a line past its budget is a full bar); 0 with no budget. */
        val usedPermille: Int
            get() = if (budget == null || budget <= 0L) 0 else (spent.coerceAtLeast(0L) * 1000L / budget).coerceIn(0L, 1000L).toInt()
    }

    fun lineFor(categoryId: Long, month: YearMonth, budgets: List<Budget>, spent: Long): Line =
        Line(categoryId, Budgets.amountFor(categoryId, month, budgets)?.takeIf { it > 0L }, spent)

    /** What the budgets of [categoryIds] add up to in [month] (a category without one adds nothing). */
    fun total(categoryIds: Collection<Long>, month: YearMonth, budgets: List<Budget>): Long =
        categoryIds.sumOf { Budgets.amountFor(it, month, budgets)?.takeIf { v -> v > 0L } ?: 0L }

    /** The sentence about a typed or stored amount, or null when it can be saved. */
    fun problem(cents: Long): String? = InputRules.amountProblem(cents)

    /** One write: set [categoryId]'s budget of [month] to [amount], or remove it ([delete]). */
    data class Change(val categoryId: Long, val month: Int, val amount: Long, val delete: Boolean)

    /** Which input a problem is about. */
    enum class Field { EVERY_MONTH, THIS_MONTH }

    data class Problem(val field: Field, val message: String)

    data class Plan(val problems: List<Problem>, val changes: List<Change>)

    /**
     * The writes that saving the budget editor of [categoryId] makes, as Android's `saveCategory` plus `setMonthBudget` do: [every] is the typed amount
     * for every month ([null] for blank: the row is removed), [thisMonth] the typed one-month override for [month] (blank removes the
     * override, but only when there is one). A typed amount that is zero, too large or not a number is a [Problem] and nothing is
     * written. [everyCents] and [monthCents] are the parsed amounts, null when blank; [everyTyped] and [monthTyped] say whether
     * anything was typed (a typed "abc" parses to null and is a problem, not "blank").
     */
    fun plan(
        categoryId: Long, month: YearMonth, budgets: List<Budget>,
        everyTyped: Boolean, everyCents: Long?, monthTyped: Boolean, monthCents: Long?,
    ): Plan {
        val problems = ArrayList<Problem>()
        if (everyTyped) {
            val p = if (everyCents == null) InputRules.amountProblem(null) else problem(everyCents)
            if (p != null) problems.add(Problem(Field.EVERY_MONTH, p))
        }
        if (monthTyped) {
            val p = if (monthCents == null) InputRules.amountProblem(null) else problem(monthCents)
            if (p != null) problems.add(Problem(Field.THIS_MONTH, p))
        }
        if (problems.isNotEmpty()) return Plan(problems, emptyList())
        val key = Budgets.key(month)
        val changes = ArrayList<Change>()
        val hadEvery = budgets.any { it.categoryId == categoryId && it.month == 0 }
        val hadMonth = budgets.any { it.categoryId == categoryId && it.month == key }
        if (everyTyped && everyCents != null) changes.add(Change(categoryId, 0, everyCents, false))
        else if (hadEvery) changes.add(Change(categoryId, 0, 0L, true))
        if (monthTyped && monthCents != null) changes.add(Change(categoryId, key, monthCents, false))
        else if (hadMonth) changes.add(Change(categoryId, key, 0L, true))
        return Plan(emptyList(), changes)
    }

    /** The budget editor opens on these: the stored amounts, or null for none. */
    fun everyMonth(categoryId: Long, budgets: List<Budget>): Long? = budgets.firstOrNull { it.categoryId == categoryId && it.month == 0 }?.amount

    fun forMonth(categoryId: Long, month: YearMonth, budgets: List<Budget>): Long? =
        budgets.firstOrNull { it.categoryId == categoryId && it.month == Budgets.key(month) }?.amount
}
