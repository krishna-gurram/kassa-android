package app.kassa.money.domain

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** The number style a phone set to [locale] would have: the same mapping the app uses (`numberStyleOf`), for tests that check one language. */
fun testStyle(locale: Locale): NumberStyle {
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val negative = NumberFormat.getNumberInstance(locale).format(-1L)
    val at = negative.indexOf(symbols.zeroDigit + 1)
    val minus = if (at > 0) negative.substring(0, at) else symbols.minusSign.toString()
    return NumberStyle(symbols.decimalSeparator, symbols.groupingSeparator, symbols.zeroDigit, locale.country in setOf("IN", "BD", "NP"), minus)
}
