package app.kassa.money.domain

/**
 * How wide each of Overview's top-category tiles is, the same on every platform. A tile is as wide as its category's share of the
 * three shown, with a floor so a small one still holds its icon, name and amount. Widths are weights in parts per thousand (the
 * row splits its width in proportion to them), whole numbers, no floating point.
 */
object OverviewTiles {

    /** The least a tile weighs: 0.3 of the share of the whole, so the smallest of three still fits an icon and an amount. */
    const val FLOOR_PERMILLE = 300

    /** What every tile weighs while Hide balances is on: equal, so no width gives a size or a share away. */
    const val EQUAL_PERMILLE = 1000

    /**
     * The weight of each of [amounts] (largest first, as Overview lists them): its share of their sum in parts per thousand, rounded down,
     * never below [FLOOR_PERMILLE]; a negative amount counts as zero, and a sum of zero is read as one. While [hidden] every tile weighs
     * [EQUAL_PERMILLE].
     */
    fun weights(amounts: List<Long>, hidden: Boolean): List<Int> {
        if (hidden) return amounts.map { EQUAL_PERMILLE }
        val parts = amounts.map { it.coerceAtLeast(0L) }
        val sum = parts.sum().coerceAtLeast(1L)
        return parts.map { (it * 1000L / sum).toInt().coerceAtLeast(FLOOR_PERMILLE) }
    }
}
