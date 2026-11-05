package app.kassa.money.domain

/**
 * The rules of making and editing categories and subcategories, the same on every platform (Android's `CategoryRepository.save`
 * and `merge`, its category editor and its subcategory list). Plain Kotlin so they have JVM tests. Nothing here is ever deleted:
 * a category is archived (`hidden`: it leaves the pickers and budgets, its entries stay in reports) or merged into another (every
 * entry moves, then it is archived).
 */
object CategoryRules {

    /** The sentence for a category, or the one it belongs to, that is no longer there. */
    const val GONE = "That category is no longer here."

    /** Names are unique among siblings (the top-level categories of one kind, or the subcategories of one parent), ignoring case. */
    fun nameProblem(all: List<Category>, kind: CategoryKind, parentId: Long?, exceptId: Long, name: String): String? {
        val siblings = all.filter { it.id != exceptId && it.parentId == parentId && it.kind == kind }
        return InputRules.nameProblem(
            name, if (parentId == null) "category" else "subcategory", InputRules.CATEGORY_NAME_MAX,
            taken = { n -> siblings.any { it.name.equals(n, ignoreCase = true) } },
        )
    }

    /**
     * Why [c] can't be saved, or null: it (or its parent) is gone, its parent isn't a top-level category of its kind, it has no icon,
     * or its name is empty, too long or taken. Archived siblings count for the name, so a restore can never make a duplicate.
     */
    fun problem(all: List<Category>, c: Category): String? {
        if (c.id != 0L && all.none { it.id == c.id }) return GONE
        val parentId = c.parentId
        if (parentId != null) {
            val parent = all.firstOrNull { it.id == parentId }
            if (parent == null || parent.parentId != null || parent.kind != c.kind || parent.id == c.id) return GONE
        }
        val icon = c.icon
        if (icon is CategoryIcon.Emoji && icon.value.isBlank()) return "Pick an icon."
        if (icon is CategoryIcon.Symbol && CategoryIcons.all.none { it.name == icon.name } ) return "Pick an icon."
        return nameProblem(all, c.kind, c.parentId, c.id, c.name)
    }

    /** A blank top-level category of [kind] for the editor: the first colour nobody of its kind uses while one is free. */
    fun blank(all: List<Category>, kind: CategoryKind): Category =
        Category(name = "", kind = kind, color = CategoryColors.next(all, kind))

    /** [original] with what the editor holds, saved: the name cleaned. [hidden] is true when the person archives it. */
    fun edited(original: Category, name: String, icon: CategoryIcon, color: Int, counts: Boolean, hidden: Boolean): Category =
        original.copy(name = InputRules.clean(name), icon = icon, color = color, countsTowardDaily = counts, hidden = hidden)

    /** Subcategories wear their parent's colour: those of a saved top-level [edited] whose colour differs, with the new one. */
    fun recolored(all: List<Category>, edited: Category): List<Category> =
        if (edited.parentId != null || edited.id == 0L) emptyList()
        else all.filter { it.parentId == edited.id && it.color != edited.color }.map { it.copy(color = edited.color) }

    /** A subcategory moved under another top-level category takes that category's colour. */
    fun moved(sub: Category, top: Category): Category = sub.copy(parentId = top.id, color = top.color)

    /** The top-level categories of [kind], A to Z, split into those in use and the archived ones. */
    fun listing(all: List<Category>, kind: CategoryKind): Pair<List<Category>, List<Category>> {
        val tops = all.filter { it.kind == kind && it.parentId == null }.sortedWith(Names.order { it.name })
        return tops.filter { !it.hidden } to tops.filter { it.hidden }
    }

    /** [parent]'s subcategories, A to Z, split into those in use and the archived ones. */
    fun subs(all: List<Category>, parentId: Long): Pair<List<Category>, List<Category>> {
        val mine = all.filter { it.parentId == parentId }.sortedWith(Names.order { it.name })
        return mine.filter { !it.hidden } to mine.filter { it.hidden }
    }

    /**
     * Where every entry of [from] can move to before it is archived: for a top-level category the other categories of its kind in
     * use; for a subcategory its parent ("no subcategory") first, then its sibling subcategories in use.
     */
    fun mergeTargets(all: List<Category>, from: Category): List<Category> {
        val parentId = from.parentId
        if (parentId == null) {
            return all.filter { it.kind == from.kind && it.parentId == null && it.id != from.id && !it.hidden }.sortedWith(Names.order { it.name })
        }
        val parent = all.firstOrNull { it.id == parentId } ?: return emptyList()
        val siblings = all.filter { it.parentId == parentId && it.id != from.id && !it.hidden }.sortedWith(Names.order { it.name })
        return listOf(parent) + siblings
    }

    /** Why [from] can't be merged into [into], or null. */
    fun mergeProblem(all: List<Category>, from: Long, into: Long): String? {
        val source = all.firstOrNull { it.id == from } ?: return GONE
        return if (mergeTargets(all, source).any { it.id == into }) null else "Pick another category to move them into."
    }

    /** The top-level categories a subcategory can move under: those of its kind in use, except the one it is under, A to Z. */
    fun moveTargets(all: List<Category>, sub: Category): List<Category> =
        all.filter { it.parentId == null && it.kind == sub.kind && !it.hidden && it.id != sub.parentId }.sortedWith(Names.order { it.name })
}
