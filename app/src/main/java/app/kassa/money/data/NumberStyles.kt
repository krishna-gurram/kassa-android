package app.kassa.money.data

import app.kassa.money.domain.NumberStyle
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

private class StyleCache(val locale: Locale, val style: NumberStyle)

@Volatile
private var cached: StyleCache? = null

/**
 * How a phone set to [locale] writes numbers: its decimal mark, grouping mark, digits and minus sign (from java.text),
 * and lakh grouping for India, Bangladesh and Nepal, which java.text can't do. The domain rules take this instead of a
 * Locale so they carry no platform classes. The last answer is kept, since every amount asks.
 */
fun numberStyleOf(locale: Locale): NumberStyle {
    cached?.let { if (it.locale == locale) return it.style }
    val symbols = DecimalFormatSymbols.getInstance(locale)
    // What the language puts before a negative number (a plain minus, a true minus, or a direction mark with it).
    val negative = NumberFormat.getNumberInstance(locale).format(-1L)
    val at = negative.indexOf(symbols.zeroDigit + 1)
    val minus = if (at > 0) negative.substring(0, at) else symbols.minusSign.toString()
    val style = NumberStyle(symbols.decimalSeparator, symbols.groupingSeparator, symbols.zeroDigit, NumberStyle.usesLakh(locale.country), minus)
    cached = StyleCache(locale, style)
    return style
}
