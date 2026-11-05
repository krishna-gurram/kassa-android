package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryIconsTest {

    @Test fun namesAreUniqueAndLowercase() {
        val names = CategoryIcons.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { n -> n == n.lowercase() && n.isNotEmpty() })
    }

    @Test fun everyStarterCategoryIconIsInTheCatalog() {
        val names = CategoryIcons.all.map { it.name }.toSet()
        assertTrue(StarterCategories.all.all { it.icon in names }, "a starter icon is missing from the catalog")
    }

    @Test fun searchFindsByNameOrWordsAndAnEmptySearchFindsAll() {
        assertEquals(CategoryIcons.all, CategoryIcons.search("  "))
        assertTrue(CategoryIcons.search("tesla").any { it.name == "charging" })
        assertTrue(CategoryIcons.search("FUEL").any { it.name == "fuel" })
        assertTrue(CategoryIcons.search("zzzz").isEmpty())
    }

    @Test fun theEmojiGridHasNoDuplicates() {
        assertEquals(CategoryIcons.emoji.size, CategoryIcons.emoji.toSet().size)
    }
}
