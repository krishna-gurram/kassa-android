package app.kassa.money.domain

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class AmountPadTest {

    private fun typeAll(vararg keys: String): String = keys.fold("") { text, key -> AmountPad.press(text, key) }

    @Test
    fun digitsPointAndDelete() {
        assertEquals("12.5", typeAll("1", "2", ".", "5"))
        assertEquals("0.", typeAll("."))
        assertEquals("12.5", typeAll("1", "2", ".", "5", ".", "."))
        assertEquals("1", typeAll("1", "2", "del"))
        assertEquals("", typeAll("1", "del", "del"))
        assertEquals("5", typeAll("0", "5"))
    }

    @Test
    fun atMostEightWholeDigitsAndTwoDecimals() {
        assertEquals("12345678", typeAll("1", "2", "3", "4", "5", "6", "7", "8", "9"))
        assertEquals("12.34", typeAll("1", "2", ".", "3", "4", "5"))
        assertEquals("12345678.99", typeAll("1", "2", "3", "4", "5", "6", "7", "8", ".", "9", "9"))
    }

    @Test
    fun aKeyThatIsNotOnThePadChangesNothing() {
        assertEquals("12", AmountPad.press("12", "x"))
        assertEquals("12", AmountPad.press("12", "55"))
    }

    @Test
    fun displayFollowsThePhonesNumberStyle() {
        Money.withStyle(testStyle(Locale.US)) {
            assertEquals("0", AmountPad.display(""))
            assertEquals("12,345.6", AmountPad.display("12345.6"))
            assertEquals("0.", AmountPad.display("0."))
        }
        Money.withStyle(testStyle(Locale.GERMANY)) {
            assertEquals("12.345,6", AmountPad.display("12345.6"))
            assertEquals("12.345,", AmountPad.display("12345."))
        }
        Money.withStyle(testStyle(Locale.forLanguageTag("en-IN"))) {
            assertEquals("12,34,567.5", AmountPad.display("1234567.5"))
        }
    }

    @Test
    fun theTypedPointIsReadBackInAnyLanguage() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("fr-FR"), Locale.forLanguageTag("en-IN"))) {
            Money.withStyle(testStyle(locale)) {
                assertEquals(1250L, AmountPad.cents("12.5"), locale.toString())
                assertEquals(1205L, AmountPad.cents("12.05"), locale.toString())
                assertEquals(123_456_789L, AmountPad.cents("1234567.89"), locale.toString())
                assertEquals(1_200L, AmountPad.cents("12"), locale.toString())
                assertEquals(0L, AmountPad.cents(""), locale.toString())
            }
        }
    }

    @Test
    fun centsBecomePadInputWithAPoint() {
        assertEquals("12.50", AmountPad.typedOf(1250))
        assertEquals("12", AmountPad.typedOf(1200))
        assertEquals("0.05", AmountPad.typedOf(5))
        assertEquals("12.05", AmountPad.typedOf(1205))
        assertEquals("99999999.99", AmountPad.typedOf(InputRules.AMOUNT_MAX))
        for (cents in listOf(1L, 99L, 100L, 1250L, 7_000_001L)) assertEquals(cents, AmountPad.cents(AmountPad.typedOf(cents)))
    }
}
