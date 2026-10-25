package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryColorsTest {
    private val p = CategoryColors.palette
    private fun top(id: Long, color: Int, kind: CategoryKind = CategoryKind.EXPENSE, hidden: Boolean = false) =
        Category(id, "C$id", kind, color = color, hidden = hidden)

    @Test fun paletteColorsAreDistinct() = assertEquals(p.size, p.toSet().size)

    @Test fun clashLosesToTheBusierCategory() {
        val cats = listOf(top(1, p[5]), top(2, p[5]), top(3, p[0]), Category(4, "Sub", CategoryKind.EXPENSE, parentId = 1, color = p[5]))
        val changed = CategoryColors.separate(cats, mapOf(1L to 100L, 2L to 900L))
        // 2 spent more, so it keeps the color; 1 and its subcategory move to the first free color.
        assertEquals(listOf(1L, 4L), changed.map { it.id })
        assertTrue(changed.all { it.color == p[1] })
    }

    @Test fun kindsAndArchivedDontClash() {
        val cats = listOf(top(1, p[0]), top(2, p[0], CategoryKind.INCOME), top(3, p[0], hidden = true))
        assertTrue(CategoryColors.separate(cats, emptyMap()).isEmpty())
    }

    @Test fun separatedColorsAreUnique() {
        val cats = (1L..20L).map { top(it, p[(it % 3).toInt()]) }
        val after = cats.map { c -> CategoryColors.separate(cats, emptyMap()).firstOrNull { it.id == c.id } ?: c }
        assertEquals(20, after.map { it.color }.toSet().size)
    }

    @Test fun moreCategoriesThanColorsStopsQuietly() {
        val cats = (1L..30L).map { top(it, p[0]) }
        val changed = CategoryColors.separate(cats, emptyMap())
        assertEquals(p.size - 1, changed.size)
    }

    @Test fun problemOnlyWhenAFreeColorExists() {
        val cats = listOf(top(1, p[0]), top(2, p[1]))
        assertNotNull(CategoryColors.problem(top(3, p[0]), cats))
        assertNull(CategoryColors.problem(top(3, p[2]), cats))
        assertNull(CategoryColors.problem(top(1, p[0]), cats))
        val full = p.mapIndexed { i, c -> top(i + 1L, c) }
        assertNull(CategoryColors.problem(top(99, p[0]), full))
        assertEquals(p[2], CategoryColors.free(setOf(p[0], p[1])))
    }
}
