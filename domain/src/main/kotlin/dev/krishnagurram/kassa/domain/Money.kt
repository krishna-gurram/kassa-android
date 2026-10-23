package dev.krishnagurram.kassa.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * The one currency a phone shows. Amounts are stored as whole minor units (cents, pence, fils, paise) and are
 * never converted; the currency only decides the symbol, the grouping (₹1,00,000 for rupees) and a few defaults.
 * Every currency here has two decimal places.
 */
data class CurrencyStyle(
    val code: String,
    val name: String,
    /** What goes before the number, including a trailing space for letter codes ("AED 12.00"). */
    val symbol: String,
    val locale: Locale,
    /** "Small purchases" are under this, in minor units. */
    val smallLimit: Long,
)

object Currencies {
    val all = listOf(
        CurrencyStyle("USD", "US dollar", "$", Locale.US, 1_500),
        CurrencyStyle("GBP", "British pound", "£", Locale.UK, 1_000),
        CurrencyStyle("EUR", "Euro", "€", Locale("en", "IE"), 1_000),
        CurrencyStyle("AED", "UAE dirham", "AED ", Locale("en", "AE"), 5_000),
        CurrencyStyle("INR", "Indian rupee", "₹", Locale("en", "IN"), 50_000),
        CurrencyStyle("CAD", "Canadian dollar", "C$", Locale.CANADA, 2_000),
        CurrencyStyle("AUD", "Australian dollar", "A$", Locale("en", "AU"), 2_000),
        CurrencyStyle("SGD", "Singapore dollar", "S$", Locale("en", "SG"), 2_000),
        CurrencyStyle("SAR", "Saudi riyal", "SAR ", Locale("en", "SA"), 5_000),
        CurrencyStyle("QAR", "Qatari riyal", "QAR ", Locale("en", "QA"), 5_000),
    )

    fun byCode(code: String?): CurrencyStyle = all.firstOrNull { it.code == code } ?: all.first()
}

object Money {

    /** The phone's currency. Set once at start and whenever it changes in Settings. */
    @Volatile
    var style: CurrencyStyle = Currencies.all.first()

    val symbol: String get() = style.symbol.trim()

    /** Parses "1234.5", "1,234.50", "£12" or "AED 12" into minor units. Returns null for anything that is not a number. */
    fun parseCents(text: String): Long? {
        val cleaned = text.trim().filter { it.isDigit() || it == '.' || it == '-' }
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** 1234.5 as a plain number for an input field: no symbol, no grouping, no trailing ".00". */
    fun toInput(cents: Long): String = BigDecimal.valueOf(cents).movePointLeft(2).stripTrailingZeros().let {
        if (it.scale() < 0) it.setScale(0).toPlainString() else it.toPlainString()
    }

    /** £1,234.56 or −£12.00 in the phone's currency. Uses a real minus sign so negatives line up with tabular figures. */
    fun format(cents: Long, wholeDollars: Boolean = false, showPlus: Boolean = false, style: CurrencyStyle = this.style): String {
        val nf = NumberFormat.getNumberInstance(style.locale).apply {
            minimumFractionDigits = if (wholeDollars) 0 else 2
            maximumFractionDigits = if (wholeDollars) 0 else 2
        }
        val abs = BigDecimal.valueOf(Math.abs(cents)).movePointLeft(2)
        val sign = when {
            cents < 0 -> "−"
            showPlus && cents > 0 -> "+"
            else -> ""
        }
        return sign + style.symbol + nf.format(abs)
    }
}
