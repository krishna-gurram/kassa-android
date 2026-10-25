package app.kassa.money.domain

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
    /** What one share cost on average, in minor units; null until the user enters it or buys through Kassa. */
    val avgCost: Long? = null,
) {
    val value: Long get() = Shares.cost(microShares, price)

    /** What the shares cost in total, when the average cost is known. */
    val cost: Long? get() = avgCost?.let { Shares.cost(microShares, it) }

    /** Unrealized profit (negative for a loss), when the average cost is known. */
    val profit: Long? get() = cost?.let { value - it }

    /** The holding after buying [microShares] more at [price] each: shares grow and the average cost blends in. */
    fun afterBuy(microShares: Long, price: Long, at: Long): Holding = copy(
        microShares = this.microShares + microShares,
        price = price,
        priceMillis = at,
        // An unknown average stays unknown: blending a guess in would show a wrong profit.
        avgCost = avgCost?.let { Shares.average(this.microShares, it, microShares, price) },
    )

    /** The holding after selling [microShares] at [price]: fewer shares, same average cost. */
    fun afterSale(microShares: Long, price: Long, at: Long): Holding {
        require(microShares <= this.microShares) { "You hold ${Shares.format(this.microShares)} shares of $ticker." }
        return copy(microShares = this.microShares - microShares, price = price, priceMillis = at)
    }

    /**
     * The holding as if a buy of [microShares] at [price] never happened, for editing or deleting that buy.
     * The average cost is taken back out too, when it's known and the rest still cost something.
     */
    fun withoutBuy(microShares: Long, price: Long): Holding {
        val left = (this.microShares - microShares).coerceAtLeast(0)
        val avg = avgCost?.let { a ->
            if (left == 0L) a
            else {
                val restCost = BigDecimal.valueOf(this.microShares).multiply(BigDecimal.valueOf(a)) -
                    BigDecimal.valueOf(microShares).multiply(BigDecimal.valueOf(price))
                if (restCost.signum() > 0) restCost.divide(BigDecimal.valueOf(left), 0, RoundingMode.HALF_UP).toLong() else a
            }
        }
        return copy(microShares = left, avgCost = avg)
    }

    /** The holding as if a sale of [microShares] never happened: the shares come back at the same average cost. */
    fun withoutSale(microShares: Long): Holding = copy(microShares = this.microShares + microShares)
}

/** The shares and price behind one buy or sale entry, so the trade can be edited or undone later. */
data class Trade(
    val id: Long = 0,
    /** The BUY or SELL entry that moved the cash. */
    val transactionId: Long,
    val ticker: String,
    val microShares: Long,
    val price: Long,
)

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

    /** The average cost per share after adding [addShares] at [addPrice] to [shares] that averaged [avg]. */
    fun average(shares: Long, avg: Long, addShares: Long, addPrice: Long): Long {
        val total = BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(avg)) + BigDecimal.valueOf(addShares).multiply(BigDecimal.valueOf(addPrice))
        return total.divide(BigDecimal.valueOf(shares + addShares), 0, RoundingMode.HALF_UP).toLong()
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
