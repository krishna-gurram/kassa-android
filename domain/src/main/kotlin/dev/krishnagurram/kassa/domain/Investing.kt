package dev.krishnagurram.kassa.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** A stock or fund in an investment account, valued at the last price the user entered. */
data class Holding(
    val id: Long = 0,
    val accountId: Long,
    val ticker: String,
    /** Shares in millionths, so fractional shares stay exact. */
    val microShares: Long,
    val price: Long,
    val priceMillis: Long,
) {
    val value: Long get() = Shares.cost(microShares, price)
}

/** Share counts are kept in millionths ([Holding.microShares]) so fractional shares stay exact. */
object Shares {
    /** At most this many whole shares in one holding or trade. */
    const val WHOLE_DIGITS = 9
    const val DECIMALS = 6

    /** "1.5" becomes 1,500,000. Null unless it's a number above zero. */
    fun parse(text: String): Long? = try {
        BigDecimal(text.trim()).movePointRight(DECIMALS).setScale(0, RoundingMode.HALF_UP).longValueExact().takeIf { it > 0 }
    } catch (e: NumberFormatException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** 1,500,000 as "1.5" for showing or for a field. */
    fun format(microShares: Long): String = BigDecimal.valueOf(microShares).movePointLeft(DECIMALS).stripTrailingZeros().toPlainString()

    /** What [microShares] cost at [price] each, rounded to the nearest minor unit. Capped at [Long.MAX_VALUE] so a typo can't wrap around. */
    fun cost(microShares: Long, price: Long): Long =
        BigDecimal.valueOf(microShares).multiply(BigDecimal.valueOf(price)).movePointLeft(DECIMALS).setScale(0, RoundingMode.HALF_UP)
            .min(BigDecimal.valueOf(Long.MAX_VALUE)).toLong()

    /** For a shares field's onValueChange: digits and one point, [WHOLE_DIGITS] whole digits and [DECIMALS] decimals. */
    fun typed(raw: String): String {
        val kept = raw.filter { it.isDigit() || it == '.' }
        val dot = kept.indexOf('.')
        val whole = (if (dot < 0) kept else kept.substring(0, dot)).take(WHOLE_DIGITS)
        return if (dot < 0) whole else whole + "." + kept.substring(dot + 1).filter(Char::isDigit).take(DECIMALS)
    }

    fun problem(microShares: Long?): String? = if (microShares == null) "Enter how many shares." else null
}

/** Money moved into an investment account that you haven't yet said was spent on shares or kept as cash. */
data class InvestmentTransfer(
    val id: Long = 0,
    /** The transfer entry that moved the money. */
    val transactionId: Long,
    val accountId: Long,
    val amount: Long,
    val epochMillis: Long,
)
