package app.kassa.money.shared

import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryIcons
import app.kassa.money.domain.InputRules

/*
 * The category and subcategory editor on iOS (batch Q). The rules are :domain's (`CategoryRules`, `CategoryColors`,
 * `CategoryIcons`, `InputRules`); this file turns them into flat values for Swift, the way `WriteBridge` does for accounts. Ids are
 * 0 for "none", an icon crosses as a kind ("emoji", "symbol" or "image") and a value, a colour is an ARGB Int, and kinds are
 * `CategoryKind` names. The reads and the checks that need the ledger are members of `LedgerView`.
 */

/**
 * One category as the editor holds it, or as the store saves it: [id] 0 for a new one, [parentId] 0 for a top-level category,
 * [counts] whether it counts toward today's number (bills like a mortgage don't), [hidden] whether it is archived.
 */
class CategoryInput(
    val id: Long,
    val kind: String,
    val parentId: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val counts: Boolean,
    val hidden: Boolean,
) {
    internal fun icon(): CategoryIcon = when (iconKind) {
        "symbol" -> CategoryIcon.Symbol(iconValue)
        "image" -> CategoryIcon.Image(iconValue)
        else -> CategoryIcon.Emoji(iconValue)
    }

    /** The category this input describes; null for a kind that isn't one. */
    internal fun category(): Category? {
        val k = categoryKindOf(kind) ?: return null
        return Category(
            id = id, name = name, kind = k, parentId = if (parentId > 0) parentId else null, icon = icon(), color = color,
            countsTowardDaily = counts, hidden = hidden,
        )
    }

    internal companion object {
        fun of(c: Category): CategoryInput {
            val (kind, value) = iconParts(c.icon)
            return CategoryInput(c.id, c.kind.name, c.parentId ?: 0L, c.name, kind, value, c.color, c.countsTowardDaily, c.hidden)
        }
    }
}

/** One colour of the palette; [usedBy] names the other categories of the kind that use it, A to Z ("Dining, Gifts"), empty if none. */
data class ColorChoiceData(val color: Int, val usedBy: String)

/** A subcategory in the editor's list. */
data class SubcategoryRowData(val id: Long, val name: String, val hidden: Boolean)

/** A category another one can be merged into or moved under. */
data class CategoryTargetData(val id: Long, val name: String)

/**
 * What the category editor opens with. [input] is what is stored (or a blank category with an unused colour when [isNew]); [subs]
 * (in use first, A to Z, then the archived ones) only for a stored top-level category; [mergeTargets] are where all its entries can
 * move before it is archived; [moveTargets] where a subcategory can move to.
 */
data class CategoryEditData(
    val found: Boolean,
    val input: CategoryInput,
    val isNew: Boolean,
    val isTop: Boolean,
    val colors: List<ColorChoiceData>,
    val subs: List<SubcategoryRowData>,
    val mergeTargets: List<CategoryTargetData>,
    val moveTargets: List<CategoryTargetData>,
)

/** One row of Settings > Categories: the category as the pickers show it, how many subcategories it has in use, and whether it is a bill. */
data class CategoryListRowData(val choice: CategoryChoiceData, val subCount: Int, val bill: Boolean)

/** A kind's categories: in use first (A to Z), then the archived ones. */
data class CategoryListData(val active: List<CategoryListRowData>, val archived: List<CategoryListRowData>)

/** What the editor's icon chooser needs. */
object CategoryIconBridge {

    /** The built-in icons (stored names) that match [query] by name or by the words they answer to; all of them for an empty search. */
    fun icons(query: String): List<String> = CategoryIcons.search(query).map { it.name }

    /** The emoji grid, in order. */
    fun emoji(): List<String> = CategoryIcons.emoji

    /** What a typed or pasted emoji field keeps: the first emoji and what is glued to it (a skin tone, a joined family). */
    fun typedEmoji(raw: String): String = InputRules.typedEmoji(raw)

    /** A category name as it is typed: no line breaks, at most the limit. */
    fun typedName(raw: String): String = InputRules.typed(raw, InputRules.CATEGORY_NAME_MAX)

    /** The longest a category name may be, for the counter. */
    fun nameLimit(): Int = InputRules.CATEGORY_NAME_MAX

    /** True for the columns of `categories` (BackupRows order) an edit writes: name, parent, icon, colour, counts and archived. */
    fun ownsColumn(column: Int): Boolean =
        BackupRows.columns("categories").getOrNull(column) in setOf("name", "parentId", "icon", "color", "countsTowardDaily", "hidden")
}
