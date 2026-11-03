package app.kassa.money.domain

/** A category offered on first run. [fixed] ones are bills, so they don't count toward everyday spending. */
data class StarterCategory(
    val name: String,
    val kind: CategoryKind,
    /** A name from Android's icon catalog (`IconCatalog`); each platform draws it with its own symbol. */
    val icon: String,
    /** Ticked when the first-run list opens. */
    val on: Boolean = true,
    val fixed: Boolean = false,
)

/**
 * The categories "Start fresh" offers someone starting from nothing, the same on every platform. The person ticks the ones they
 * want; all can be renamed or removed later. Nothing is saved until they choose.
 */
object StarterCategories {

    val all: List<StarterCategory> = listOf(
        StarterCategory("Bills", CategoryKind.EXPENSE, "bills", fixed = true),
        StarterCategory("Car", CategoryKind.EXPENSE, "car"),
        StarterCategory("Dining", CategoryKind.EXPENSE, "restaurant"),
        StarterCategory("Entertainment", CategoryKind.EXPENSE, "movie"),
        StarterCategory("Gifts", CategoryKind.EXPENSE, "gift", on = false),
        StarterCategory("Groceries", CategoryKind.EXPENSE, "cart"),
        StarterCategory("Health", CategoryKind.EXPENSE, "medical"),
        StarterCategory("Home", CategoryKind.EXPENSE, "home", fixed = true),
        StarterCategory("Personal care", CategoryKind.EXPENSE, "haircut", on = false),
        StarterCategory("Shopping", CategoryKind.EXPENSE, "bag"),
        StarterCategory("Subscriptions", CategoryKind.EXPENSE, "subscriptions", fixed = true),
        StarterCategory("Transport", CategoryKind.EXPENSE, "bus"),
        StarterCategory("Travel", CategoryKind.EXPENSE, "flight"),
        StarterCategory("Interest", CategoryKind.INCOME, "savings"),
        StarterCategory("Other income", CategoryKind.INCOME, "money"),
        StarterCategory("Salary", CategoryKind.INCOME, "payments"),
    )

    /** The names ticked when the list opens. */
    fun defaultNames(): List<String> = all.filter { it.on }.map { it.name }

    /**
     * The categories to create for the ticked [names], in list order (expenses, then income). Colors count up separately for
     * expense and income, so no two of a kind share one while the palette lasts.
     */
    fun categories(names: Collection<String>): List<Category> =
        all.filter { it.name in names }.groupBy { it.kind }.values.flatMap { list ->
            list.mapIndexed { i, s ->
                Category(
                    name = s.name,
                    kind = s.kind,
                    icon = CategoryIcon.Symbol(s.icon),
                    color = CategoryColors.palette[i % CategoryColors.palette.size],
                    countsTowardDaily = !s.fixed,
                )
            }
        }
}
