package app.kassa.money.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KassaCoreTest {

    @Test
    fun formatsWithGroupingAndTwoDecimals() {
        KassaCore.installDefaults()
        assertEquals("$1,234.56", KassaCore.formatMoney(123_456, "$"))
        assertEquals("£0.05", KassaCore.formatMoney(5, "£"))
    }

    @Test
    fun negativeUsesTheTrueMinus() {
        KassaCore.installDefaults()
        assertEquals("−$12.00", KassaCore.formatMoney(-1_200, "$"))
    }

    @Test
    fun installingTwiceChangesNothing() {
        KassaCore.installDefaults()
        KassaCore.installDefaults()
        assertEquals("$1,234.56", KassaCore.formatMoney(123_456, "$"))
    }

    @Test
    fun hasAVersion() {
        assertTrue(KassaCore.version().isNotBlank())
    }
}
