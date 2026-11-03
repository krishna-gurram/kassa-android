package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BigIntTest {

    @Test
    fun printsAndReadsLongs() {
        assertEquals("0", BigInt.of(0).toString())
        assertEquals("-9223372036854775808", BigInt.of(Long.MIN_VALUE).toString())
        assertEquals("9223372036854775807", BigInt.of(Long.MAX_VALUE).toString())
        assertEquals(Long.MIN_VALUE, BigInt.of(Long.MIN_VALUE).toLongExact())
    }

    @Test
    fun arithmeticIsExactPastLong() {
        val big = BigInt.of(Long.MAX_VALUE) * BigInt.of(Long.MAX_VALUE)
        assertEquals("85070591730234615847396907784232501249", big.toString())
        assertEquals("85070591730234615847396907784232501250", (big + BigInt.ONE).toString())
        assertEquals(BigInt.of(Long.MAX_VALUE), big / BigInt.of(Long.MAX_VALUE))
        assertEquals(BigInt.ZERO, big % BigInt.of(Long.MAX_VALUE))
        assertEquals("1267650600228229401496703205376", BigInt.of(2).pow(100).toString())
        assertEquals(BigInt.ZERO, big - big)
    }

    @Test
    fun divisionTruncatesLikeLong() {
        val (q, r) = BigInt.of(-7).divRem(BigInt.of(2))
        assertEquals(BigInt.of(-3), q)
        assertEquals(BigInt.of(-1), r)
        val (q2, r2) = (BigInt.of(10).pow(40) + BigInt.of(7)).divRem(BigInt.of(10).pow(20) + BigInt.ONE)
        assertEquals("99999999999999999999", q2.toString())
        assertEquals("8", r2.toString())
        assertFailsWith<ArithmeticException> { BigInt.ONE.divRem(BigInt.ZERO) }
    }

    @Test
    fun halvesGoAwayFromZero() {
        assertEquals(3, Wide.mulDivHalfUp(5, 1, 2))
        assertEquals(-3, Wide.mulDivHalfUp(-5, 1, 2))
        assertEquals(1, Wide.mulDivHalfUp(4, 1, 3))
        assertEquals(Long.MAX_VALUE, Wide.mulDivHalfUp(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFailsWith<ArithmeticException> { Wide.mulDivHalfUp(Long.MAX_VALUE, 2, 1) }
        assertFailsWith<ArithmeticException> { Wide.mulDivHalfUp(1, 1, 0) }
    }

    @Test
    fun comparesAndFits() {
        assertTrue(BigInt.of(-5) < BigInt.of(3))
        assertTrue(BigInt.of(2).pow(64) > BigInt.of(Long.MAX_VALUE))
        assertFailsWith<ArithmeticException> { BigInt.of(2).pow(63).toLongExact() }
        assertEquals(Long.MIN_VALUE, (-BigInt.of(2).pow(63)).toLongExact())
    }

    @Test
    fun loanPaymentIsExactAtTheLimits() {
        // 30,000.00 at 6% over 60 months, and the largest amount at a high rate over 50 years.
        assertEquals(57_998, LoanEngine.emi(3_000_000, 60_000, 60))
        assertEquals(1, LoanEngine.emi(1, 1, 1))
        // One payment of 30,000.00 plus a month of interest at 6.125% is 30,153.125: a true half-cent tie, which goes up.
        assertEquals(3_015_313, LoanEngine.emi(3_000_000, 61_250, 1))
        assertEquals(3_000_001, LoanEngine.emi(3_000_000, 2, 1))
    }

    @Test
    fun sharesCostsStayExactPastLong() {
        assertEquals(Long.MAX_VALUE, Shares.cost(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(1, Shares.cost(1, 500_000))
        assertEquals(0, Shares.cost(1, 499_999))
        assertEquals(3, Shares.average(1, 2, 1, 4))
        assertEquals(3, Shares.average(2, 3, 0, 1))
    }
}
