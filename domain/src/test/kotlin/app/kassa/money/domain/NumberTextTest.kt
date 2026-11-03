package app.kassa.money.domain

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Number reading and writing with no platform classes: exact on Longs and digit strings, same results as the old BigDecimal code. */
class NumberTextTest {
    private fun style(tag: String) = testStyle(Locale.forLanguageTag(tag))

    @Test
    fun decimalGroupsRoundsHalfUpAndTrims() {
        val us = NumberStyle.US
        assertEquals("1,234.50", NumberText.decimal(123_450, 2, 2, us))
        assertEquals("0.05", NumberText.decimal(5, 2, 2, us))
        assertEquals("1,235", NumberText.decimal(123_450, 2, 0, us))
        assertEquals("1,234", NumberText.decimal(123_449, 2, 0, us))
        assertEquals("6.125", NumberText.decimal(61_250, 4, 4, us, trim = true))
        assertEquals("6", NumberText.decimal(60_000, 4, 4, us, trim = true))
        assertEquals("1,000", NumberText.decimal(99_999, 2, 0, us))
        assertEquals("92,233,720,368,547,758.07", NumberText.decimal(Long.MAX_VALUE, 2, 2, us))
        assertEquals("-92,233,720,368,547,758.08", NumberText.decimal(Long.MIN_VALUE, 2, 2, us))
        assertEquals("1.234,50", NumberText.decimal(123_450, 2, 2, style("de-DE")))
        assertEquals("12,34,567.50", NumberText.decimal(123_456_750, 2, 2, style("en-IN")))
        assertEquals("-12,345", NumberText.decimal(-1_234_500, 2, 0, style("en-IN")))
    }

    @Test
    fun fieldsKeepNoGroupingAndNoTrailingZeros() {
        assertEquals("1234.5", NumberText.toField(123_450, 2, NumberStyle.US))
        assertEquals("1234,5", NumberText.toField(123_450, 2, style("de-DE")))
        assertEquals("0.05", NumberText.toField(5, 2, NumberStyle.US))
        assertEquals("0", NumberText.toField(0, 2, NumberStyle.US))
        assertEquals("12", NumberText.toField(1_200, 2, NumberStyle.US))
        assertEquals("-0.5", NumberText.toField(-50, 2, NumberStyle.US))
        assertEquals("1.5", NumberText.toField(1_500_000, 6, NumberStyle.US))
    }

    @Test
    fun groupDropsLeadingZerosExceptInTheIndianWay() {
        assertEquals("1,234,567", NumberText.group("1234567", NumberStyle.US))
        assertEquals("12", NumberText.group("0012", NumberStyle.US))
        assertEquals("12,34,567", NumberText.group("1234567", style("en-IN")))
        assertEquals("abc", NumberText.group("abc", NumberStyle.US))
    }

    @Test
    fun arabicDigitsAreWrittenWithTheStylesZero() {
        val text = NumberText.decimal(123_450, 2, 2, style("ar-EG"))
        assertEquals(123_450L, Money.withStyle(style("ar-EG")) { Money.parseCents(text) })
    }

    @Test
    fun scaledIsExactAndRoundsHalfUp() {
        fun cents(s: String) = Money.withStyle(NumberStyle.US) { Money.parseCents(s) }
        assertEquals(101L, cents("1.005"))
        assertEquals(100L, cents("1.004"))
        assertEquals(-101L, cents("-1.005"))
        assertEquals(Long.MAX_VALUE, cents("92233720368547758.07"))
        assertNull(cents("92233720368547758.08"))
        assertEquals(Long.MIN_VALUE, cents("-92233720368547758.08"))
        assertNull(cents("-92233720368547758.09"))
        assertEquals(0L, cents("0000000000000000000000000.001"))
        assertNull(cents("99999999999999999999999"))
    }

    @Test
    fun plainFilesReadLikeBigDecimalDid() {
        assertEquals(123_450L, Money.parsePlain("1234.5"))
        assertEquals(-50L, Money.parsePlain("-.5"))
        assertEquals(500L, Money.parsePlain("5."))
        assertNull(Money.parsePlain("-"))
        assertNull(Money.parsePlain("."))
        assertNull(Money.parsePlain("-."))
        assertNull(Money.parsePlain("1.2.3"))
        assertNull(Money.parsePlain("5-"))
        assertNull(Money.parsePlain("--5"))
        assertEquals(350L, Money.parsePlain("٣.٥"))
        assertNull(Money.parsePlain("92233720368547758.08"))
    }

    @Test
    fun ratesAndSharesUseTheSameHelpers() {
        assertEquals(61_250L, InputRules.parseRate("6.125"))
        assertEquals(1_000_000L, InputRules.parseRate("100"))
        assertNull(InputRules.parseRate("1.2.3"))
        assertEquals(1_500_000L, Shares.parse("1.5"))
        assertNull(Shares.parse("0"))
        assertEquals("1.5", Shares.format(1_500_000))
        assertEquals("6.125%", LoanRate.display(61_250))
    }

    @Test
    fun theFirstEmojiKeepsItsJoinedParts() {
        assertEquals("👨‍👩‍👧", InputRules.typedEmoji("👨‍👩‍👧x"))
        assertEquals("🇺🇸", InputRules.typedEmoji("🇺🇸🇬🇧"))
        assertEquals("👍🏽", InputRules.typedEmoji(" 👍🏽a"))
        assertEquals("1️⃣", InputRules.typedEmoji("1️⃣2"))
        assertEquals("é", InputRules.typedEmoji("éx"))
        assertEquals("😀󠄀", InputRules.typedEmoji("😀󠄀😀"))
        assertEquals("", InputRules.typedEmoji("  "))
    }
}
