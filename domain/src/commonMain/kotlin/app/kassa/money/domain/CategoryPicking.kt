package app.kassa.money.domain

/** What the category picker offers, worked out from the categories alone. Plain Kotlin so the rules have JVM tests. */
object CategoryPicking {

    /** Visible top-level categories of [kind], A to Z, so each stays in the same place in the picker. */
    fun top(categories: List<Category>, kind: CategoryKind): List<Category> =
        categories.filter { it.kind == kind && it.parentId == null && !it.hidden }.sortedWith(Names.order { it.name })

    /** A category's visible subcategories, A to Z. */
    fun subcategories(categories: List<Category>, top: Category): List<Category> =
        categories.filter { it.parentId == top.id && !it.hidden }.sortedWith(Names.order { it.name })

    /** Categories and subcategories whose name contains [query], best matches first; a subcategory needs a visible parent. */
    fun search(categories: List<Category>, kind: CategoryKind, query: String): List<Category> {
        val byId = categories.associateBy { it.id }
        return categories
            .filter { it.kind == kind && !it.hidden && it.name.contains(query, ignoreCase = true) }
            .filter { it.parentId == null || byId[it.parentId]?.hidden == false }
            .sortedWith(compareBy<Category> { !it.name.startsWith(query, ignoreCase = true) }.thenBy { it.parentId != null }.thenBy { it.name.lowercase() })
    }

    /** "Parent › Child" for a subcategory, the plain name for any other category. */
    fun path(category: Category, categories: List<Category>): String {
        val parent = category.parentId?.let { id -> categories.firstOrNull { it.id == id } }
        return if (parent != null) "${parent.name} › ${category.name}" else category.name
    }
}
