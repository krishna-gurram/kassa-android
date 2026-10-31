package app.kassa.money.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/**
 * One currency a phone can show. Amounts are stored as whole minor units (cents, pence, fils, paise) and are never
 * converted; the currency only decides the symbol and a few defaults. The grouping and decimal mark come from the
 * phone's language, not the currency (see [NumberText]). Every currency here has two decimal places.
 */
data class CurrencyStyle(
    val code: String,
    val name: String,
    /** What goes before the number, including a trailing space for letter codes ("AED 12.00"). */
    val symbol: String,
    /** "Small purchases" are under this, in minor units. */
    val smallLimit: Long,
)

object Currencies {
    /**
     * Only currencies with two minor-unit digits: the whole app (fields, limits, importers, loan rounding) works in
     * hundredths. Whole-unit or three-digit ones (JPY, KRW, KWD, BHD, OMR) are left out until all of that handles them,
     * and so is IDR, whose 99,999,999.99 limit would stop at about 6,000 US dollars.
     */
    val all = listOf(
        CurrencyStyle("USD", "US dollar", "$", 1_500),
        CurrencyStyle("GBP", "British pound", "£", 1_000),
        CurrencyStyle("EUR", "Euro", "€", 1_000),
        CurrencyStyle("AED", "UAE dirham", "AED ", 5_000),
        CurrencyStyle("INR", "Indian rupee", "₹", 50_000),
        CurrencyStyle("CAD", "Canadian dollar", "C$", 2_000),
        CurrencyStyle("AUD", "Australian dollar", "A$", 2_000),
        CurrencyStyle("SGD", "Singapore dollar", "S$", 2_000),
        CurrencyStyle("SAR", "Saudi riyal", "SAR ", 5_000),
        CurrencyStyle("QAR", "Qatari riyal", "QAR ", 5_000),
        CurrencyStyle("CHF", "Swiss franc", "CHF ", 1_500),
        CurrencyStyle("CNY", "Chinese yuan", "CN¥", 10_000),
        CurrencyStyle("HKD", "Hong Kong dollar", "HK$", 12_000),
        CurrencyStyle("NZD", "New Zealand dollar", "NZ$", 2_500),
        CurrencyStyle("SEK", "Swedish krona", "SEK ", 15_000),
        CurrencyStyle("NOK", "Norwegian krone", "NOK ", 15_000),
        CurrencyStyle("DKK", "Danish krone", "DKK ", 10_000),
        CurrencyStyle("PLN", "Polish zloty", "PLN ", 6_000),
        CurrencyStyle("CZK", "Czech koruna", "CZK ", 35_000),
        CurrencyStyle("HUF", "Hungarian forint", "HUF ", 550_000),
        CurrencyStyle("TRY", "Turkish lira", "₺", 60_000),
        CurrencyStyle("THB", "Thai baht", "฿", 50_000),
        CurrencyStyle("MYR", "Malaysian ringgit", "RM", 7_000),
        CurrencyStyle("PHP", "Philippine peso", "₱", 85_000),
        CurrencyStyle("PKR", "Pakistani rupee", "PKR ", 400_000),
        CurrencyStyle("BDT", "Bangladeshi taka", "৳", 180_000),
        CurrencyStyle("LKR", "Sri Lankan rupee", "LKR ", 450_000),
        CurrencyStyle("NPR", "Nepalese rupee", "NPR ", 200_000),
        CurrencyStyle("BRL", "Brazilian real", "R$", 8_000),
        CurrencyStyle("MXN", "Mexican peso", "MX$", 30_000),
        CurrencyStyle("ZAR", "South African rand", "R", 25_000),
        CurrencyStyle("EGP", "Egyptian pound", "EGP ", 70_000),
        CurrencyStyle("NGN", "Nigerian naira", "₦", 2_000_000),
        CurrencyStyle("KES", "Kenyan shilling", "KES ", 200_000),
        CurrencyStyle("ILS", "Israeli shekel", "₪", 5_500),
    )

    /** What an unknown code falls back to. */
    val fallback: CurrencyStyle get() = all.first()

    fun byCode(code: String?): CurrencyStyle = all.firstOrNull { it.code == code } ?: fallback

    /** The list in the phone's alphabetical order of names, for pickers. */
    fun sorted(): List<CurrencyStyle> = all.sortedWith(Names.order { it.name })

    /** The currency of [locale]'s country (en-IN gives INR), or null when the phone's region has none Kassa supports. */
    fun forLocale(locale: Locale = Locale.getDefault()): CurrencyStyle? {
        if (locale.country.isEmpty()) return null
        val code = try {
            Currency.getInstance(locale)?.currencyCode
        } catch (e: IllegalArgumentException) {
            null
        }
        return all.firstOrNull { it.code == code }
    }
}

object Money {

    /** The phone's currency. Set once at start and whenever it changes in Settings. */
    @Volatile
    var style: CurrencyStyle = Currencies.all.first()

    val symbol: String get() = style.symbol.trim()

    /** What every hidden amount shows instead of its figure. */
    const val MASK = "••••"

    /**
     * Asked by [format] each time: true while the person has chosen Hide balances. The app sets it to read a Compose
     * state, so every screen that formats money redraws when the switch flips. Plain Kotlin here on purpose.
     */
    @Volatile
    var hideAmounts: () -> Boolean = { false }

    /** True while amounts are hidden; screens use it to drop signs and state colors that would give a figure away. */
    val isHidden: Boolean get() = hideAmounts()

    @Volatile
    private var pinned: Locale? = null

    /** The phone's language for numbers, read on every call so a language change applies at once. */
    val locale: Locale get() = pinned ?: Locale.getDefault()

    /** Runs [block] with numbers written for [locale]; for tests that check one language's style. */
    fun <T> withLocale(locale: Locale, block: () -> T): T {
        val before = pinned
        pinned = locale
        try {
            return block()
        } finally {
            pinned = before
        }
    }

    /**
     * Reads an amount someone typed or pasted ("1,234.50", "1.234,50", "£12", "AED 12", "−5") into minor units, in
     * the phone's number style (see [NumberText.parts]). Null for anything that is not a number. Numbers read from a
     * file use [parsePlain].
     */
    fun parseCents(text: String): Long? {
        val parts = NumberText.parts(text.trim()) ?: return null
        if (!parts.hasDigits) return null
        return try {
            parts.toBigDecimal().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Reads an amount from another app's file ("1234.5"): the point is always the decimal mark, whatever the phone's language. */
    fun parsePlain(text: String): Long? {
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

    /** 1234.5 as a plain number for an input field: no symbol, no grouping, no trailing zeros, the phone's decimal mark. */
    fun toInput(cents: Long): String = NumberText.toField(BigDecimal.valueOf(cents).movePointLeft(2))

    /**
     * £1,234.56 or −£12.00 in the phone's currency and number style. Uses a real minus sign so negatives line up with tabular figures.
     * Returns [MASK] while amounts are hidden, with no sign and no currency. Text for editable fields uses [toInput].
     */
    fun format(cents: Long, wholeDollars: Boolean = false, showPlus: Boolean = false, style: CurrencyStyle = this.style): String {
        return if (isHidden) MASK else sample(cents, wholeDollars, showPlus, style)
    }

    /**
     * Like [format] but never masked. Only for an example that isn't anyone's money, such as the currency list showing
     * what 1,234.50 looks like in each currency. Every real amount goes through [format].
     */
    fun sample(cents: Long, wholeDollars: Boolean = false, showPlus: Boolean = false, style: CurrencyStyle = this.style): String {
        val abs = BigDecimal.valueOf(cents).abs().movePointLeft(2)
        val number = NumberText.decimal(abs, if (wholeDollars) 0 else 2)
        val sign = when {
            cents < 0 -> "−"
            showPlus && cents > 0 -> "+"
            else -> ""
        }
        return sign + style.symbol + number
    }
}
