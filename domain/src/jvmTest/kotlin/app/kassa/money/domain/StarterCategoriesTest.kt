package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarterCategoriesTest {

    @Test
    fun theListIsAndroidsSixteenCategories() {
        val names = StarterCategories.all.map { it.name }
        assertEquals(
            listOf(
                "Bills", "Car", "Dining", "Entertainment", "Gifts", "Groceries", "Health", "Home", "Personal care", "Shopping",
                "Subscriptions", "Transport", "Travel", "Interest", "Other income", "Salary",
            ),
            names,
        )
        assertEquals(13, StarterCategories.all.count { it.kind == CategoryKind.EXPENSE })
        assertEquals(3, StarterCategories.all.count { it.kind == CategoryKind.INCOME })
    }

    @Test
    fun giftsAndPersonalCareStartUnticked() {
        val on = StarterCategories.defaultNames()
        assertEquals(14, on.size)
        assertFalse("Gifts" in on)
        assertFalse("Personal care" in on)
        assertTrue("Salary" in on)
    }

    @Test
    fun billsAreFixedSoTheyDontCountTowardDailySpending() {
        val made = StarterCategories.categories(StarterCategories.defaultNames()).associateBy { it.name }
        for (name in listOf("Bills", "Home", "Subscriptions")) assertFalse(made.getValue(name).countsTowardDaily, name)
        for (name in listOf("Car", "Dining", "Groceries", "Salary")) assertTrue(made.getValue(name).countsTowardDaily, name)
    }

    @Test
    fun iconsAreSymbolsAndColorsCountUpPerKind() {
        val made = StarterCategories.categories(StarterCategories.all.map { it.name })
        assertEquals(16, made.size)
        assertEquals(CategoryIcon.Symbol("bills"), made.first { it.name == "Bills" }.icon)
        assertEquals(CategoryIcon.Symbol("payments"), made.first { it.name == "Salary" }.icon)
        val expense = made.filter { it.kind == CategoryKind.EXPENSE }.map { it.color }
        assertEquals(CategoryColors.palette.take(13), expense)
        val income = made.filter { it.kind == CategoryKind.INCOME }.map { it.color }
        assertEquals(CategoryColors.palette.take(3), income)
        assertTrue(made.all { it.parentId == null && !it.hidden && it.id == 0L })
    }

    @Test
    fun expensesComeBeforeIncomeAndOnlyTickedNamesAreMade() {
        val made = StarterCategories.categories(listOf("Salary", "Car", "Nope"))
        assertEquals(listOf("Car", "Salary"), made.map { it.name })
        assertEquals(CategoryColors.palette[0], made[0].color)
        assertEquals(CategoryColors.palette[0], made[1].color)
    }

    @Test
    fun everyStarterNameFitsTheCategoryLimit() {
        for (s in StarterCategories.all) assertEquals(null, InputRules.nameProblem(s.name, "category", InputRules.CATEGORY_NAME_MAX), s.name)
    }
}
