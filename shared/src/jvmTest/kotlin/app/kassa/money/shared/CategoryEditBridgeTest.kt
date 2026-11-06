package app.kassa.money.shared

import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.domain.CategoryColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the iOS category editor asks of :shared. The rules themselves are tested in :domain. */
class CategoryEditBridgeTest {

    private val p = CategoryColors.palette

    private fun cat(id: Long, name: String, kind: String = "EXPENSE", parent: Long? = null, icon: String = "symbol:cart", color: Int = p[0], counts: Boolean = true, hidden: Boolean = false, order: Int = id.toInt()) =
        BackupCategory(id, name, kind, parent, icon, color, counts, hidden, order)

    private fun data() = BackupData(
        createdAt = 0, monthStartDay = 1, currency = "USD",
        categories = listOf(
            cat(1, "Groceries", icon = "emoji:🛒"),
            cat(2, "Dining", color = p[1]),
            cat(3, "Home", counts = false, color = p[1]),
            cat(4, "Old", hidden = true),
            cat(5, "Salary", kind = "INCOME", icon = "symbol:payments"),
            cat(10, "Costco", parent = 1),
            cat(11, "Farmers market", parent = 1),
            cat(12, "Retired", parent = 1, hidden = true),
        ),
    )

    private fun view(): LedgerView {
        KassaCore.installDefaults()
        return LedgerView(data(), null)
    }

    private fun col(name: String) = BackupRows.columns("categories").indexOf(name)

    @Test
    fun theListIsAToZWithBillsMarkedAndArchivedOnesLast() {
        val list = view().categoryList("EXPENSE")
        assertEquals(listOf("Dining", "Groceries", "Home"), list.active.map { it.choice.name })
        assertEquals(listOf("Old"), list.archived.map { it.choice.name })
        assertEquals(listOf(0, 2, 0), list.active.map { it.subCount })
        assertEquals(listOf(false, false, true), list.active.map { it.bill })
        assertEquals(listOf("Salary"), view().categoryList("INCOME").active.map { it.choice.name })
        assertTrue(view().categoryList("nonsense").active.isEmpty())
    }

    @Test
    fun aNewCategoryOpensBlankWithAnUnusedColour() {
        val e = view().categoryNew("EXPENSE")
        assertTrue(e.found)
        assertTrue(e.isNew)
        assertTrue(e.isTop)
        assertEquals(0L, e.input.id)
        assertEquals("", e.input.name)
        assertEquals("EXPENSE", e.input.kind)
        // p[0] is Groceries' and p[1] is Dining's and Home's (the archived Old doesn't count): the first free one is p[2].
        assertEquals(p[2], e.input.color)
        assertEquals("emoji", e.input.iconKind)
        assertTrue(e.subs.isEmpty())
        assertTrue(e.mergeTargets.isEmpty())
        assertEquals(p.size, e.colors.size)
    }

    @Test
    fun theEditorMarksColoursInUseAndNamesWhoSharesThem() {
        val e = view().categoryEdit(2)
        assertEquals("Home", e.colors.first { it.color == p[1] }.usedBy)
        assertEquals("Groceries", e.colors.first { it.color == p[0] }.usedBy)
        assertEquals("", e.colors.first { it.color == p[2] }.usedBy)
        assertEquals(listOf("Groceries", "Home"), e.mergeTargets.map { it.name })
    }

    @Test
    fun aTopLevelCategoryListsItsSubcategoriesInUseThenArchived() {
        val e = view().categoryEdit(1)
        assertFalse(e.isNew)
        assertEquals(listOf("Costco", "Farmers market", "Retired"), e.subs.map { it.name })
        assertEquals(listOf(false, false, true), e.subs.map { it.hidden })
        assertEquals("emoji", e.input.iconKind)
        assertEquals("🛒", e.input.iconValue)
    }

    @Test
    fun aSubcategoryCanMoveOrMergeButHasNoSubcategories() {
        val e = view().categoryEdit(10)
        assertFalse(e.isTop)
        assertTrue(e.subs.isEmpty())
        assertEquals(listOf("Dining", "Home"), e.moveTargets.map { it.name })
        assertEquals(listOf("Groceries", "Farmers market"), e.mergeTargets.map { it.name })
        assertFalse(view().categoryEdit(99).found)
    }

    @Test
    fun problemsAreTheDomainsSentences() {
        val v = view()
        val dup = CategoryInput(0, "EXPENSE", 0, " dining ", "symbol", "cart", p[3], true, false)
        assertEquals("dining is already used.", v.categoryProblem(dup))
        assertEquals("", v.categoryProblem(CategoryInput(0, "EXPENSE", 0, "Transport", "symbol", "bus", p[3], true, false)))
        assertEquals("Give the category a name.", v.categoryProblem(CategoryInput(0, "EXPENSE", 0, " ", "symbol", "bus", p[3], true, false)))
        assertEquals("Pick an icon.", v.categoryProblem(CategoryInput(0, "EXPENSE", 0, "Transport", "emoji", "", p[3], true, false)))
        assertEquals(CategoryRulesGone, v.categoryProblem(CategoryInput(0, "NOPE", 0, "x", "emoji", "x", 0, true, false)))
        assertEquals("costco is already used.", v.subcategoryProblem(1, "costco"))
        assertEquals("", v.subcategoryProblem(2, "Costco"))
        assertEquals(CategoryRulesGone, v.subcategoryProblem(99, "Costco"))
        assertEquals("", v.mergeProblem(1, 2))
        assertEquals("Pick another category to move them into.", v.mergeProblem(1, 5))
    }

    @Test
    fun aNewSubcategoryWearsItsParent() {
        val sub = view().subcategoryInput(1, "  Bulk  buys ")
        assertEquals(0L, sub.id)
        assertEquals(1L, sub.parentId)
        assertEquals("Bulk buys", sub.name)
        assertEquals("EXPENSE", sub.kind)
        assertEquals("emoji", sub.iconKind)
        assertEquals("🛒", sub.iconValue)
        assertEquals(p[0], sub.color)
    }

    @Test
    fun renamingArchivingAndMovingKeepTheRest() {
        val v = view()
        val renamed = v.categoryInputWith(10, "  Warehouse ", false)
        assertEquals(10L, renamed.id)
        assertEquals("Warehouse", renamed.name)
        assertEquals(1L, renamed.parentId)
        assertTrue(v.categoryInputWith(10, "Costco", true).hidden)
        assertFalse(v.categoryInputWith(12, "Retired", false).hidden)
        val moved = v.movedSubcategoryInput(10, 2)
        assertEquals(2L, moved.parentId)
        assertEquals(p[1], moved.color)
        assertEquals(10L, moved.id)
    }

    @Test
    fun savingATopLevelColourRecolorsItsSubcategories() {
        val v = view()
        val e = v.categoryEdit(1)
        val recolored = CategoryInput(1, "EXPENSE", 0, "Groceries", e.input.iconKind, e.input.iconValue, p[6], true, false)
        assertEquals(listOf("10", "11", "12"), v.recolorIds(recolored))
        assertTrue(v.recolorIds(e.input).isEmpty())
    }

    @Test
    fun theStoredRowIsTheEditorsColumnsInTableOrder() {
        val v = view()
        val input = CategoryInput(0, "EXPENSE", 0, "  Transport ", "symbol", "bus", p[3], false, false)
        assertEquals("Transport", v.categoryCell(input, col("name"), 9))
        assertEquals("EXPENSE", v.categoryCell(input, col("kind"), 9))
        assertNull(v.categoryCell(input, col("parentId"), 9))
        assertEquals("symbol:bus", v.categoryCell(input, col("icon"), 9))
        assertEquals(p[3].toString(), v.categoryCell(input, col("color"), 9))
        assertEquals("0", v.categoryCell(input, col("countsTowardDaily"), 9))
        assertEquals("0", v.categoryCell(input, col("hidden"), 9))
        assertEquals("9", v.categoryCell(input, col("sortOrder"), 9))
        assertNull(v.categoryCell(input, col("externalId"), 9))
        assertNull(v.categoryCell(input, col("id"), 9))
        val under = CategoryInput(0, "EXPENSE", 1, "Bulk", "emoji", "🛒", p[0], true, false)
        assertEquals("1", v.categoryCell(under, col("parentId"), 9))
        // A stored category keeps its order.
        val stored = CategoryInput(2, "EXPENSE", 0, "Dining", "symbol", "cart", p[1], true, false)
        assertEquals("2", v.categoryCell(stored, col("sortOrder"), 99))
    }

    @Test
    fun anEditWritesOnlyTheEditorsColumns() {
        val owned = BackupRows.columns("categories").filterIndexed { i, _ -> CategoryIconBridge.ownsColumn(i) }
        assertEquals(listOf("name", "parentId", "icon", "color", "countsTowardDaily", "hidden"), owned)
    }

    @Test
    fun theIconChooserSearchesTheCatalogAndFiltersTyping() {
        assertTrue("charging" in CategoryIconBridge.icons("tesla"))
        assertTrue(CategoryIconBridge.icons("").size > 100)
        assertEquals("🚌", CategoryIconBridge.typedEmoji(" 🚌 and text"))
        assertEquals("", CategoryIconBridge.typedEmoji("  "))
        assertEquals(30, CategoryIconBridge.nameLimit())
        assertEquals("a b", CategoryIconBridge.typedName("a\nb"))
        assertTrue(CategoryIconBridge.emoji().isNotEmpty())
    }

    private val CategoryRulesGone = "That category is no longer here."
}
