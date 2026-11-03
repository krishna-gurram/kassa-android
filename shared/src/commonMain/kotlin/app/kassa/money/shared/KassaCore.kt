package app.kassa.money.shared

import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Money
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

    /** [cents] written with [symbol] in front, for example 123456 and "$" give "$1,234.56". Amounts are never converted. */
    fun formatMoney(cents: Long, symbol: String): String =
        Money.format(cents, style = CurrencyStyle(code = "", name = "", symbol = symbol, smallLimit = 0))

    fun version(): String = VERSION
}
