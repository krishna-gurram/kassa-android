package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrencyRegionTest {

    @Test
    fun aListedRegionCurrencyIsTheStart() {
        assertEquals("USD", Currencies.forRegion("US", "USD")?.code)
        assertEquals("INR", Currencies.forRegion("IN", "INR")?.code)
        assertEquals("EUR", Currencies.forRegion("DE", "eur")?.code)
    }

    @Test
    fun noRegionOrAnUnlistedCurrencyMeansNothingIsPreselected() {
        assertNull(Currencies.forRegion(null, "USD"), "no region")
        assertNull(Currencies.forRegion("", "USD"), "empty region")
        assertNull(Currencies.forRegion("JP", "JPY"), "not listed")
        assertNull(Currencies.forRegion("US", null))
    }
}
