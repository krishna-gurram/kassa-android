package app.kassa.money.domain

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyHideTest {

    @After
    fun showAgain() {
        Money.hideAmounts = { false }
    }

    @Test
    fun amountsShowByDefault() {
        assertFalse(Money.isHidden)
        assertEquals("$1,234.56", Money.format(123_456))
    }

    @Test
    fun formatReturnsTheMaskWhileHidden() {
        Money.hideAmounts = { true }
        assertTrue(Money.isHidden)
        assertEquals("••••", Money.format(123_456))
        assertEquals(Money.MASK, Money.format(0))
    }

    @Test
    fun theMaskCarriesNoSignNoPlusAndNoCurrency() {
        Money.hideAmounts = { true }
        assertEquals(Money.MASK, Money.format(-1_200))
        assertEquals(Money.MASK, Money.format(500, wholeDollars = true, showPlus = true))
        assertEquals(Money.MASK, Money.format(99, style = Currencies.byCode("INR")))
    }

    @Test
    fun theProviderIsAskedOnEveryCallSoAFlipTakesEffectAtOnce() {
        var hidden = false
        Money.hideAmounts = { hidden }
        assertEquals("$5.00", Money.format(500))
        hidden = true
        assertEquals(Money.MASK, Money.format(500))
        hidden = false
        assertEquals("$5.00", Money.format(500))
    }

    @Test
    fun textForEditableFieldsIsNeverMasked() {
        Money.hideAmounts = { true }
        assertEquals("1234.5", Money.toInput(123_450))
        assertEquals("12", Money.toInput(1_200))
        assertEquals(1_200L, Money.parseCents("12"))
    }

    @Test
    fun anExampleThatIsNotAnyonesMoneyStaysVisible() {
        Money.hideAmounts = { true }
        val example = Money.sample(12_345_000, style = Currencies.byCode("INR"))
        assertTrue(example.startsWith("₹") && example != Money.MASK)
        assertEquals("£1,234.50", Money.sample(123_450, style = Currencies.byCode("GBP")))
    }

    @Test
    fun aBalanceCorrectionShowsNoArrowWhileHidden() {
        assertEquals("↓ $24.80", BalanceCorrection.describe(-2_480))
        Money.hideAmounts = { true }
        assertEquals(Money.MASK, BalanceCorrection.describe(-2_480))
    }

    @Test
    fun theSymbolStaysForAFieldPrefix() {
        Money.hideAmounts = { true }
        assertEquals("$", Money.symbol)
    }
}
