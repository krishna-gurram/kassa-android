package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals

class CurrencyTest {
    @Test
    fun formatsInTheChosenCurrency() {
        val gbp = Currencies.byCode("GBP")
        assertEquals("£1,234.50", Money.format(123_450, style = gbp))
        assertEquals("−£12", Money.format(-1_200, wholeDollars = true, style = gbp))
        assertEquals("AED 9.99", Money.format(999, style = Currencies.byCode("AED")))
        assertEquals("$5.00", Money.format(500, style = Currencies.byCode("nope")))
    }

    @Test
    fun parsesAnySymbolOrCode() {
        assertEquals(1_250, Money.parseCents("AED 12.50"))
        assertEquals(1_200, Money.parseCents("£12"))
        assertEquals(10_000_000, Money.parseCents("₹1,00,000"))
        assertEquals(null, Money.parseCents("abc"))
    }

    @Test
    fun inputHasNoSymbolOrGrouping() {
        assertEquals("1234.5", Money.toInput(123_450))
        assertEquals("12", Money.toInput(1_200))
        assertEquals("0.05", Money.toInput(5))
    }
}
