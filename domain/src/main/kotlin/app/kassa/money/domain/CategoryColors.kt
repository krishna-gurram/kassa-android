package app.kassa.money.domain

/**
 * Category colors. Two categories may share one: Overview's top categories are separate tiles with icons, so a
 * shared color never reads as one piece. New categories get a color nobody of their kind uses while one is free.
 * Subcategories wear their parent's color; archived categories don't count.
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

    /** Which other active top-level categories of [kind] use each color, leaving out [except]. */
    fun usedBy(categories: List<Category>, kind: CategoryKind, except: Long = 0): Map<Int, List<String>> =
        categories.filter { it.counts() && it.kind == kind && it.id != except }.groupBy({ it.color }, { it.name })

    /** A new category's color: the first one nobody of its kind uses, or when all are used, the least used. */
    fun next(categories: List<Category>, kind: CategoryKind): Int {
        val counts = categories.filter { it.counts() && it.kind == kind }.groupingBy { it.color }.eachCount()
        return palette.minBy { counts[it] ?: 0 }
    }
}
