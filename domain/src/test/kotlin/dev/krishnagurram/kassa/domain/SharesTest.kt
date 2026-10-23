package dev.krishnagurram.kassa.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharesTest {

    @Test
    fun sharesParseToMillionths() {
        assertEquals(1_500_000, Shares.parse("1.5"))
        assertEquals(1, Shares.parse("0.000001"))
        assertNull(Shares.parse("0"))
        assertNull(Shares.parse("abc"))
        assertEquals("1.5", Shares.format(1_500_000))
    }

    @Test
    fun costIsExactWithoutFloatingPoint() {
        // 3.333333 shares at 300.00 each is 999.9999, which rounds to 1,000.00.
        assertEquals(100_000, Shares.cost(3_333_333, 30_000))
        assertEquals(1, Shares.cost(1, 500_000))
        assertEquals(Holding(accountId = 1, ticker = "VTI", microShares = 2_000_000, price = 25_050, priceMillis = 0).value, 50_100)
    }

    @Test
    fun typingKeepsSixDecimals() {
        assertEquals("1.123456", Shares.typed("1.1234567"))
        assertEquals("123456789", Shares.typed("1234567890"))
    }
}
