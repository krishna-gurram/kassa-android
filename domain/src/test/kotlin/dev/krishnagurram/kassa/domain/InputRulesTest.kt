package dev.krishnagurram.kassa.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InputRulesTest {

    @Test
    fun typingDropsLineBreaksAndStopsAtTheLimit() {
        assertEquals("Chase  checking", InputRules.typed("Chase\n\tchecking", 40))
        assertEquals("a".repeat(40), InputRules.typed("a".repeat(55), 40))
    }

    @Test
    fun namesAreStoredTidy() {
        assertEquals("Chase Sapphire", InputRules.clean("  Chase   Sapphire \u0000"))
    }

    @Test
    fun namesMustBePresentShortAndUnique() {
        assertNotNull(InputRules.nameProblem("   ", "account"))
        assertNotNull(InputRules.nameProblem("a".repeat(41), "account"))
        assertNull(InputRules.nameProblem("a".repeat(40), "account"))
        assertNotNull(InputRules.nameProblem("bofa alaska", "account", taken = { it.equals("BofA Alaska", ignoreCase = true) }))
        assertNotNull(InputRules.nameProblem("a".repeat(31), "category", max = InputRules.CATEGORY_NAME_MAX))
    }

    @Test
    fun amountsAreAboveZeroAndBounded() {
        assertNotNull(InputRules.amountProblem(null))
        assertNotNull(InputRules.amountProblem(0))
        assertNull(InputRules.amountProblem(0, zeroAllowed = true))
        assertNotNull(InputRules.amountProblem(-5))
        assertNull(InputRules.amountProblem(InputRules.AMOUNT_MAX))
        assertNotNull(InputRules.amountProblem(InputRules.AMOUNT_MAX + 1))
        assertNull(InputRules.balanceProblem(-1_400_000))
        assertNotNull(InputRules.balanceProblem(-InputRules.AMOUNT_MAX - 1))
    }

    @Test
    fun lastFourIsFourDigitsOrNothing() {
        assertNull(InputRules.last4Problem(""))
        assertNull(InputRules.last4Problem("1004"))
        assertNotNull(InputRules.last4Problem("104"))
        assertNotNull(InputRules.last4Problem("10a4"))
    }

    @Test
    fun daysTickersAndPasswords() {
        assertNull(InputRules.dayProblem(31, "statement day"))
        assertNotNull(InputRules.dayProblem(0, "statement day"))
        assertNotNull(InputRules.dayProblem(null, "statement day"))
        assertNull(InputRules.tickerProblem("BRK.B"))
        assertNotNull(InputRules.tickerProblem("VERYLONGTICKER"))
        assertNotNull(InputRules.passwordProblem("short", "short"))
        assertNotNull(InputRules.passwordProblem("longenough", "longenougH"))
        assertNull(InputRules.passwordProblem("longenough", "longenough"))
    }

    @Test
    fun amountFieldsKeepDigitsAndOnePoint() {
        assertEquals("1250.50", InputRules.typedAmount("1,250.50"))
        assertEquals("12.34", InputRules.typedAmount("12.345"))
        assertEquals("12.3", InputRules.typedAmount("12.3."))
        assertEquals("12345678", InputRules.typedAmount("1234567890"))
        assertEquals("", InputRules.typedAmount("abc-"))
    }

    @Test
    fun balanceFieldsKeepALeadingMinus() {
        assertEquals("-40.5", InputRules.typedBalance("-40.5"))
        assertEquals("-40", InputRules.typedBalance("−40"))
        assertEquals("405", InputRules.typedBalance("4-05"))
        assertEquals("1004", InputRules.typedDigits("10a04x5", 4))
    }

    @Test
    fun emojiFieldsKeepOneEmoji() {
        assertEquals("🍜", InputRules.typedEmoji(" 🍜🍕"))
        assertEquals("👨‍👩‍👧", InputRules.typedEmoji("👨‍👩‍👧x"))
        assertEquals("", InputRules.typedEmoji("  "))
    }
}
