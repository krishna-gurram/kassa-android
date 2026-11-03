package app.kassa.money.shared

import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.NumberStyle

/** What the iOS app calls (Swift sees `KassaCore.shared`). Plain Kotlin; no platform classes. */
object KassaCore {

    private const val VERSION = "0.9.0"

    /**
     * Gives the domain rules a number style to use. Until the iOS side reads the phone's language this is the US style
     * (1,234.50). Safe to call any number of times.
     */
    fun installDefaults() {
        Money.numberStyleProvider = { NumberStyle.US }
    }

    /**
     * Gives the rules the phone's number style: the marks its language uses and whether it groups the Indian way. Swift reads
     * them from `Locale` and calls this at start and when the language changes; an empty text keeps the US mark.
     */
    fun useNumberStyle(decimal: String, grouping: String, zeroDigit: String, lakh: Boolean) {
        val style = NumberStyle(
            decimal = decimal.firstOrNull() ?: '.',
            grouping = grouping.firstOrNull() ?: ',',
            zeroDigit = zeroDigit.firstOrNull() ?: '0',
            lakh = lakh,
        )
        Money.numberStyleProvider = { style }
    }

    /** Gives every A to Z list the phone's own collation (Swift implements [TextCollator] with `Locale`). */
    fun useCollator(collator: TextCollator) {
        Names.comparatorProvider = { Comparator { a, b -> collator.compare(a, b) } }
    }

    /** [cents] written with [symbol] in front, for example 123456 and "$" give "$1,234.56". Amounts are never converted. */
    fun formatMoney(cents: Long, symbol: String): String =
        Money.format(cents, style = CurrencyStyle(code = "", name = "", symbol = symbol, smallLimit = 0))

    fun version(): String = VERSION
}

/** The phone's way of putting names in order, implemented in Swift: negative before, zero equal, positive after. */
interface TextCollator {
    fun compare(a: String, b: String): Int
}
