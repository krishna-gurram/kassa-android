package app.kassa.money.domain

/**
 * Category colors. No two top-level categories of the same kind share one while the palette has a free color:
 * expense and income never appear in the same chart, so each kind has the whole palette. Subcategories wear
 * their parent's color. Archived categories don't count; if one comes back clashing, [separate] fixes it.
 */
object CategoryColors {
    /** Readable on light and dark backgrounds. The first 14 are the original palette, so existing colors stay. */
    val palette: List<Int> = listOf(
        0xFF3F9A5E, 0xFF2B8A99, 0xFF4570D6, 0xFFC98A1E, 0xFFC4487F, 0xFF7A5BD4, 0xFFE07B53,
        0xFF9C6B3F, 0xFF3D8FC7, 0xFF2E9C8A, 0xFFA0527A, 0xFF8A9F35, 0xFFC2453F, 0xFF6F7C8F,
        0xFFB8A01F, 0xFF5FA032, 0xFF9B4FC4, 0xFFC246B0, 0xFFD0506A, 0xFF2F6F9F, 0xFFA65E2E,
        0xFF1F8F6A, 0xFF8F7AE0, 0xFF6A8A6F,
    ).map { it.toInt() }

    private fun Category.counts() = parentId == null && !hidden

    /** Colors other active top-level categories of [kind] use, leaving out [except]. */
    fun taken(categories: List<Category>, kind: CategoryKind, except: Long = 0): Set<Int> =
        categories.filter { it.counts() && it.kind == kind && it.id != except }.map { it.color }.toSet()

    /** The first palette color nobody in [taken] uses; when all are used, the [index]th, wrapping around. */
    fun free(taken: Set<Int>, index: Int = taken.size): Int =
        palette.firstOrNull { it !in taken } ?: palette[Math.floorMod(index, palette.size)]

    /** Why [category] can't have its color: another category of its kind uses it and a free one exists. */
    fun problem(category: Category, all: List<Category>): String? {
        if (!category.counts()) return null
        val taken = taken(all, category.kind, category.id)
        return if (category.color in taken && palette.any { it !in taken }) "Another category uses that color. Pick one that's free." else null
    }

    /**
     * Gives every clashing top-level category a free color, and its subcategories the same. Where two share one, the
     * category with more money through it ([use], by top-level id) keeps it. Returns only what changed.
     */
    fun separate(categories: List<Category>, use: Map<Long, Long>): List<Category> {
        val changed = mutableListOf<Category>()
        CategoryKind.entries.forEach { kind ->
            val tops = categories.filter { it.counts() && it.kind == kind }
                .sortedWith(compareByDescending<Category> { use[it.id] ?: 0L }.thenBy { it.id })
            val kept = mutableSetOf<Int>()
            val losers = tops.filter { !kept.add(it.color) }
            for (c in losers) {
                val color = palette.firstOrNull { it !in kept } ?: break
                kept += color
                changed += c.copy(color = color)
                changed += categories.filter { it.parentId == c.id && it.color != color }.map { it.copy(color = color) }
            }
        }
        return changed
    }
}
