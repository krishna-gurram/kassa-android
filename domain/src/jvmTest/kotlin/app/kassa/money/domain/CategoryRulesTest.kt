package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryRulesTest {
    private val p = CategoryColors.palette

    private fun top(id: Long, name: String, kind: CategoryKind = CategoryKind.EXPENSE, color: Int = p[0], hidden: Boolean = false) =
        Category(id, name, kind, null, CategoryIcon.Symbol("cart"), color, true, hidden)

    private fun sub(id: Long, name: String, parent: Long, kind: CategoryKind = CategoryKind.EXPENSE, hidden: Boolean = false) =
        Category(id, name, kind, parent, CategoryIcon.Symbol("cart"), p[0], true, hidden)

    private val all = listOf(
        top(1, "Groceries"), top(2, "Dining", color = p[1]), top(3, "Old", hidden = true), top(4, "Salary", CategoryKind.INCOME),
        sub(10, "Costco", 1), sub(11, "Farmers market", 1), sub(12, "Retired", 1, hidden = true),
    )

    // ---------- names ----------

    @Test
    fun aNameIsUniqueAmongSiblingsIgnoringCaseArchivedOnesIncluded() {
        assertEquals("groceries is already used.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 0, " groceries "))
        assertEquals("old is already used.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 0, "old"))
        // The same name in another kind, or under another parent, is fine; so is keeping your own.
        assertNull(CategoryRules.nameProblem(all, CategoryKind.INCOME, null, 0, "Groceries"))
        assertNull(CategoryRules.nameProblem(all, CategoryKind.EXPENSE, 2, 0, "Costco"))
        assertNull(CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 1, "GROCERIES"))
        assertEquals("costco is already used.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, 1, 0, "costco"))
    }

    @Test
    fun aNameIsRequiredAndBounded() {
        assertEquals("Give the category a name.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 0, "  "))
        assertEquals("Give the subcategory a name.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, 1, 0, ""))
        assertEquals("Keep the name to 30 characters.", CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 0, "x".repeat(31)))
        assertNull(CategoryRules.nameProblem(all, CategoryKind.EXPENSE, null, 0, "x".repeat(30)))
    }

    @Test
    fun aCategoryMustBeThereAndItsParentATopLevelOneOfItsKind() {
        assertNull(CategoryRules.problem(all, top(0, "Transport")))
        assertEquals(CategoryRules.GONE, CategoryRules.problem(all, top(99, "Ghost")))
        assertEquals(CategoryRules.GONE, CategoryRules.problem(all, sub(0, "Shell", 99)))
        assertEquals(CategoryRules.GONE, CategoryRules.problem(all, sub(0, "Shell", 10)))
        assertEquals(CategoryRules.GONE, CategoryRules.problem(all, sub(0, "Shell", 4)))
        assertNull(CategoryRules.problem(all, sub(0, "Shell", 2)))
    }

    @Test
    fun anIconIsRequired() {
        assertEquals("Pick an icon.", CategoryRules.problem(all, top(0, "Transport").copy(icon = CategoryIcon.Emoji(" "))))
        assertEquals("Pick an icon.", CategoryRules.problem(all, top(0, "Transport").copy(icon = CategoryIcon.Symbol("nonsense"))))
        assertNull(CategoryRules.problem(all, top(0, "Transport").copy(icon = CategoryIcon.Emoji("🚌"))))
        // A picture only Android has stays as it is.
        assertNull(CategoryRules.problem(all, top(0, "Transport").copy(icon = CategoryIcon.Image("a.png"))))
    }

    // ---------- colours ----------

    @Test
    fun aNewCategoryGetsAnUnusedColourOfItsKindWhileOneIsFree() {
        val blank = CategoryRules.blank(all, CategoryKind.EXPENSE)
        assertEquals("", blank.name)
        assertEquals(0L, blank.id)
        assertEquals(CategoryKind.EXPENSE, blank.kind)
        // p[0] and p[1] are used by Groceries and Dining; the archived one doesn't count.
        assertEquals(p[2], blank.color)
        assertEquals(p[0], CategoryRules.blank(emptyList(), CategoryKind.INCOME).color)
        assertFalse(blank.hidden)
    }

    @Test
    fun savingATopLevelColourRecolorsItsSubcategories() {
        val saved = all[0].copy(color = p[5])
        assertEquals(listOf(10L, 11L, 12L), CategoryRules.recolored(all, saved).map { it.id })
        assertTrue(CategoryRules.recolored(all, saved).all { it.color == p[5] })
        assertTrue(CategoryRules.recolored(all, all[0]).isEmpty())
        assertTrue(CategoryRules.recolored(all, all[4].copy(color = p[3])).isEmpty(), "a subcategory has no children")
        assertTrue(CategoryRules.recolored(all, all[0].copy(id = 0, color = p[5])).isEmpty(), "a new category has none yet")
    }

    @Test
    fun aMovedSubcategoryTakesTheNewParentsColour() {
        val moved = CategoryRules.moved(all[4], all[1])
        assertEquals(2L, moved.parentId)
        assertEquals(p[1], moved.color)
    }

    // ---------- lists, merging, moving ----------

    @Test
    fun theListingIsAToZWithArchivedOnesApart() {
        val (active, archived) = CategoryRules.listing(all, CategoryKind.EXPENSE)
        assertEquals(listOf("Dining", "Groceries"), active.map { it.name })
        assertEquals(listOf("Old"), archived.map { it.name })
        assertEquals(listOf("Salary"), CategoryRules.listing(all, CategoryKind.INCOME).first.map { it.name })
        val (subsActive, subsArchived) = CategoryRules.subs(all, 1)
        assertEquals(listOf("Costco", "Farmers market"), subsActive.map { it.name })
        assertEquals(listOf("Retired"), subsArchived.map { it.name })
    }

    @Test
    fun aTopLevelCategoryMergesIntoAnotherOfItsKindInUse() {
        assertEquals(listOf("Dining"), CategoryRules.mergeTargets(all, all[0]).map { it.name })
        assertNull(CategoryRules.mergeProblem(all, 1, 2))
        assertEquals("Pick another category to move them into.", CategoryRules.mergeProblem(all, 1, 1))
        assertEquals("Pick another category to move them into.", CategoryRules.mergeProblem(all, 1, 3))
        assertEquals("Pick another category to move them into.", CategoryRules.mergeProblem(all, 1, 4))
        assertEquals(CategoryRules.GONE, CategoryRules.mergeProblem(all, 99, 1))
    }

    @Test
    fun aSubcategoryMergesIntoItsParentOrASiblingInUse() {
        assertEquals(listOf("Groceries", "Farmers market"), CategoryRules.mergeTargets(all, all[4]).map { it.name })
        assertNull(CategoryRules.mergeProblem(all, 10, 1))
        assertNull(CategoryRules.mergeProblem(all, 10, 11))
        assertEquals("Pick another category to move them into.", CategoryRules.mergeProblem(all, 10, 12))
        assertEquals("Pick another category to move them into.", CategoryRules.mergeProblem(all, 10, 2))
    }

    @Test
    fun aSubcategoryMovesUnderAnotherTopLevelCategoryOfItsKind() {
        assertEquals(listOf("Dining"), CategoryRules.moveTargets(all, all[4]).map { it.name })
    }

    @Test
    fun anEditedCategoryIsCleanedAndKeepsItsIdentity() {
        val saved = CategoryRules.edited(all[0], "  Food   & drink ", CategoryIcon.Emoji("🍎"), p[7], false, true)
        assertEquals("Food & drink", saved.name)
        assertEquals(1L, saved.id)
        assertEquals(CategoryIcon.Emoji("🍎"), saved.icon)
        assertEquals(p[7], saved.color)
        assertFalse(saved.countsTowardDaily)
        assertTrue(saved.hidden)
    }
}
