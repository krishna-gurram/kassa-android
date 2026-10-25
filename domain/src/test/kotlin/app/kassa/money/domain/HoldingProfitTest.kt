package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HoldingProfitTest {
    private val vti = Holding(accountId = 1, ticker = "VTI", microShares = 10_000_000, price = 20_000, priceMillis = 0, avgCost = 20_000)

    @Test
    fun buyingMoreBlendsTheAverageCost() {
        // 10 at 200.00, then 5 at 240.00: 15 shares averaging 213.33.
        val after = vti.afterBuy(5_000_000, 24_000, at = 1)
        assertEquals(15_000_000, after.microShares)
        assertEquals(21_333, after.avgCost)
        assertEquals(24_000, after.price)
    }

    @Test
    fun profitIsValueMinusCost() {
        val h = vti.copy(microShares = 15_000_000, avgCost = 21_333, price = 25_000)
        assertEquals(375_000, h.value)
        assertEquals(319_995, h.cost) // 15 x 213.33
        assertEquals(55_005, h.profit)
    }

    @Test
    fun anUnknownAverageStaysUnknown() {
        val h = vti.copy(avgCost = null)
        assertNull(h.profit)
        assertNull(h.afterBuy(1_000_000, 30_000, at = 1).avgCost)
    }

    @Test
    fun undoingABuyRestoresTheEarlierHolding() {
        val after = vti.afterBuy(5_000_000, 24_000, at = 1)
        val undone = after.withoutBuy(5_000_000, 24_000)
        assertEquals(10_000_000, undone.microShares)
        assertEquals(20_000, undone.avgCost)
    }

    @Test
    fun aSaleKeepsTheAverageAndUndoingItBringsSharesBack() {
        val sold = vti.afterSale(4_000_000, 26_000, at = 1)
        assertEquals(6_000_000, sold.microShares)
        assertEquals(20_000, sold.avgCost)
        assertEquals(10_000_000, sold.withoutSale(4_000_000).microShares)
    }

    @Test
    fun youCantSellMoreThanYouHold() {
        assertFailsWith<IllegalArgumentException> { vti.afterSale(11_000_000, 26_000, at = 1) }
    }
}
