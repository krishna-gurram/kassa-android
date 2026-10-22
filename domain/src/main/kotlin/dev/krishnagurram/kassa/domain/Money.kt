package dev.krishnagurram.kassa.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object Money {

    /** Parses "1234.5", "1,234.50" or "$12" into cents. Returns null for anything that is not a number. */
    fun parseCents(text: String): Long? {
        val cleaned = text.trim().replace(",", "").removePrefix("$").trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** $1,234.56 or −$12.00. Uses a real minus sign so negatives line up with tabular figures. */
    fun format(cents: Long, wholeDollars: Boolean = false, showPlus: Boolean = false, locale: Locale = Locale.US): String {
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = if (wholeDollars) 0 else 2
            maximumFractionDigits = if (wholeDollars) 0 else 2
        }
        val abs = BigDecimal.valueOf(Math.abs(cents)).movePointLeft(2)
        val sign = when {
            cents < 0 -> "−"
            showPlus && cents > 0 -> "+"
            else -> ""
        }
        return "$sign$${nf.format(abs)}"
    }
}
