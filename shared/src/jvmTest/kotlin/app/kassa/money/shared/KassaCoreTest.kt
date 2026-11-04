package app.kassa.money.shared

import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberStyle
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
    fun phoneLocaleDecidesLakhGroupingFromItsRegion() {
        try {
            KassaCore.useLocale("en_IN", ".", ",", "0", "-")
            assertEquals("₹12,34,567.50", Money.format(123_456_750, style = rupee))
            KassaCore.useLocale("hi-IN", ".", ",", "0", "-")
            assertEquals("₹12,34,567.50", Money.format(123_456_750, style = rupee))
            KassaCore.useLocale("bn_BD@numbers=latn", ".", ",", "0", "-")
            assertEquals("₹12,34,567.50", Money.format(123_456_750, style = rupee))
            KassaCore.useLocale("en_US", ".", ",", "0", "-")
            assertEquals("₹1,234,567.50", Money.format(123_456_750, style = rupee))
            KassaCore.useLocale("de_DE", ",", ".", "0", "-")
            assertEquals("₹1.234.567,50", Money.format(123_456_750, style = rupee))
        } finally {
            KassaCore.installDefaults()
        }
    }

    @Test
    fun localeIdentifiersGiveTheirRegion() {
        assertEquals("IN", NumberStyle.regionOf("en-IN"))
        assertEquals("IN", NumberStyle.regionOf("hi-Latn-IN"))
        assertEquals("NP", NumberStyle.regionOf("ne_NP.UTF-8"))
        assertEquals("", NumberStyle.regionOf("en"))
        assertEquals("", NumberStyle.regionOf("zh-Hans"))
        assertTrue(NumberStyle.usesLakh("in"))
        assertTrue(!NumberStyle.usesLakh("US"))
    }

    @Test
    fun emptyMarksKeepTheUsOnes() {
        assertEquals(NumberStyle.US, NumberStyle.forLocale("en_US", "", "", "", ""))
    }

    @Test
    fun hasAVersion() {
        assertTrue(KassaCore.version().isNotBlank())
    }

    private val rupee = CurrencyStyle(code = "", name = "", symbol = "₹", smallLimit = 0)
}
