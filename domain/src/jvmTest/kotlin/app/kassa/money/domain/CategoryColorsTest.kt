package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals

class CategoryColorsTest {
    private val p = CategoryColors.palette
    private fun top(id: Long, color: Int, kind: CategoryKind = CategoryKind.EXPENSE, hidden: Boolean = false) =
        Category(id, "C$id", kind, color = color, hidden = hidden)

    @Test fun paletteColorsAreDistinct() = assertEquals(p.size, p.toSet().size)

    @Test fun nextPicksAnUnusedColorOfTheSameKind() {
        val cats = listOf(top(1, p[0]), top(2, p[1]), top(3, p[2], CategoryKind.INCOME), top(4, p[3], hidden = true))
        assertEquals(p[2], CategoryColors.next(cats, CategoryKind.EXPENSE))
        assertEquals(p[0], CategoryColors.next(cats, CategoryKind.INCOME))
    }

    @Test fun nextPicksTheLeastUsedWhenAllAreTaken() {
        val cats = p.mapIndexed { i, c -> top(i + 1L, c) } + top(99, p[0])
        assertEquals(p[1], CategoryColors.next(cats, CategoryKind.EXPENSE))
    }

    @Test fun usedByNamesOtherCategories() {
        val cats = listOf(top(1, p[0]), top(2, p[0]), top(3, p[1]))
        assertEquals(listOf("C2"), CategoryColors.usedBy(cats, CategoryKind.EXPENSE, except = 1)[p[0]])
    }
}
