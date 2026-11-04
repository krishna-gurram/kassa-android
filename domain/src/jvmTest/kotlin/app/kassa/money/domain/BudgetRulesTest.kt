package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetRulesTest {
    private val october = YearMonth.of(2026, 10)
    private val budgets = listOf(Budget(1, 0, 60_000), Budget(2, 0, 25_000), Budget(2, 202610, 30_000))

    @Test
    fun aMonthsOverrideBeatsTheEveryMonthAmount() {
        assertEquals(30_000L, BudgetRules.lineFor(2, october, budgets, 19_223).budget)
        assertEquals(25_000L, BudgetRules.lineFor(2, YearMonth.of(2026, 5), budgets, 24_289).budget)
        assertNull(BudgetRules.lineFor(3, october, budgets, 180_000).budget)
    }

    @Test
    fun aLineIsOverOnlyPastItsBudget() {
        val under = BudgetRules.lineFor(1, october, budgets, 44_320)
        assertFalse(under.over)
        assertEquals(15_680L, under.left)
        assertEquals(738, under.usedPermille)
        val exact = BudgetRules.lineFor(1, october, budgets, 60_000)
        assertFalse(exact.over)
        assertEquals(1000, exact.usedPermille)
        val over = BudgetRules.lineFor(1, YearMonth.of(2026, 5), budgets, 61_526)
        assertTrue(over.over)
        assertEquals(-1_526L, over.left)
        assertEquals(1000, over.usedPermille)
        // No budget: never over, nothing left, no bar.
        val none = BudgetRules.lineFor(3, october, budgets, 999_999)
        assertFalse(none.over)
        assertEquals(0L, none.left)
        assertEquals(0, none.usedPermille)
    }

    @Test
    fun aStoredZeroCountsAsNoBudget() {
        val zero = listOf(Budget(1, 0, 60_000), Budget(1, 202610, 0))
        assertNull(BudgetRules.lineFor(1, october, zero, 5_000).budget)
        assertEquals(0L, BudgetRules.total(listOf(1L), october, zero))
    }

    @Test
    fun theTotalAddsWhatIsInForce() {
        assertEquals(60_000L + 30_000L, BudgetRules.total(listOf(1L, 2L, 3L), october, budgets))
        assertEquals(60_000L + 25_000L, BudgetRules.total(listOf(1L, 2L, 3L), YearMonth.of(2026, 11), budgets))
    }

    @Test
    fun savingSetsChangesAndRemovesWhatWasBlanked() {
        // Category 2 has both; blank the override and change the default.
        val plan = BudgetRules.plan(2, october, budgets, true, 27_500, false, null)
        assertTrue(plan.problems.isEmpty())
        assertEquals(
            listOf(BudgetRules.Change(2, 0, 27_500, false), BudgetRules.Change(2, 202610, 0, true)),
            plan.changes,
        )
        // Blank both on a category that has neither: nothing to write.
        assertTrue(BudgetRules.plan(3, october, budgets, false, null, false, null).changes.isEmpty())
        // A new budget for a category with none.
        assertEquals(listOf(BudgetRules.Change(3, 0, 10_000, false)), BudgetRules.plan(3, october, budgets, true, 10_000, false, null).changes)
        // Removing the default alone leaves the override.
        assertEquals(listOf(BudgetRules.Change(2, 0, 0, true)), BudgetRules.plan(2, october, budgets, false, null, true, 30_000).changes.take(1))
    }

    @Test
    fun aBadAmountWritesNothingAtAll() {
        val zero = BudgetRules.plan(1, october, budgets, true, 0, true, 5_000)
        assertEquals(listOf(BudgetRules.Field.EVERY_MONTH), zero.problems.map { it.field })
        assertTrue(zero.changes.isEmpty())
        val unreadable = BudgetRules.plan(1, october, budgets, true, 5_000, true, null)
        assertEquals(listOf(BudgetRules.Field.THIS_MONTH), unreadable.problems.map { it.field })
        assertTrue(unreadable.changes.isEmpty())
        val huge = BudgetRules.plan(1, october, budgets, true, InputRules.AMOUNT_MAX + 1, false, null)
        assertEquals(1, huge.problems.size)
        assertNull(BudgetRules.problem(InputRules.AMOUNT_MAX))
        assertEquals("Enter an amount.", BudgetRules.problem(0))
    }
}
