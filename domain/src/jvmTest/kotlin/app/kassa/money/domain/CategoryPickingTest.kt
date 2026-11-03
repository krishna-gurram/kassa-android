package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals

class CategoryPickingTest {
    private val groceries = Category(id = 1, name = "Groceries", kind = CategoryKind.EXPENSE, color = 1)
    private val dining = Category(id = 2, name = "dining", kind = CategoryKind.EXPENSE, color = 2)
    private val fruit = Category(id = 3, name = "Fruit", kind = CategoryKind.EXPENSE, parentId = 1, color = 1)
    private val greens = Category(id = 4, name = "Greens", kind = CategoryKind.EXPENSE, parentId = 1, color = 1)
    private val old = Category(id = 5, name = "Old", kind = CategoryKind.EXPENSE, color = 3, hidden = true)
    private val oldSub = Category(id = 6, name = "Grapes", kind = CategoryKind.EXPENSE, parentId = 5, color = 3)
    private val salary = Category(id = 7, name = "Salary", kind = CategoryKind.INCOME, color = 4)
    private val all = listOf(dining, greens, old, salary, groceries, fruit, oldSub)

    @Test
    fun topCategoriesAreOfTheKindVisibleAndAToZ() {
        assertEquals(listOf("dining", "Groceries"), CategoryPicking.top(all, CategoryKind.EXPENSE).map { it.name })
        assertEquals(listOf("Salary"), CategoryPicking.top(all, CategoryKind.INCOME).map { it.name })
    }

    @Test
    fun subcategoriesAreAToZ() {
        assertEquals(listOf("Fruit", "Greens"), CategoryPicking.subcategories(all, groceries).map { it.name })
        assertEquals(emptyList(), CategoryPicking.subcategories(all, dining))
    }

    @Test
    fun searchPutsTopLevelPrefixMatchesFirstAndSkipsHidden() {
        // Both start with "gr"; the top-level one comes first.
        assertEquals(listOf("Groceries", "Greens"), CategoryPicking.search(all, CategoryKind.EXPENSE, "gr").map { it.name })
        // Substring matches follow prefix matches: "ruit" is only in Fruit.
        assertEquals(listOf("Fruit"), CategoryPicking.search(all, CategoryKind.EXPENSE, "ruit").map { it.name })
        // "Grapes" sits under an archived parent, so it is not offered.
        assertEquals(emptyList(), CategoryPicking.search(all, CategoryKind.EXPENSE, "grapes"))
        assertEquals(emptyList(), CategoryPicking.search(all, CategoryKind.INCOME, "gr"))
    }

    @Test
    fun aPathNamesTheParent() {
        assertEquals("Groceries › Fruit", CategoryPicking.path(fruit, all))
        assertEquals("Groceries", CategoryPicking.path(groceries, all))
    }
}
