package app.kassa.money.domain

import org.junit.Test
import java.util.Currency
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NumberStyleTest {
    private fun tag(t: String) = Locale.forLanguageTag(t)

    @Test
    fun groupingAndMarksFollowThePhoneNotTheCurrency() {
        val inr = Currencies.byCode("INR")
        val usd = Currencies.byCode("USD")
        Money.withStyle(testStyle(tag("en-US"))) {
            assertEquals("$1,234,567.50", Money.format(123_456_750, style = usd))
            assertEquals("₹1,234,567.50", Money.format(123_456_750, style = inr))
        }
        Money.withStyle(testStyle(tag("en-IN"))) {
            assertEquals("₹12,34,567.50", Money.format(123_456_750, style = inr))
            assertEquals("₹1,00,000.00", Money.format(10_000_000, style = inr))
            assertEquals("₹999.00", Money.format(99_900, style = inr))
            assertEquals("₹1,000.00", Money.format(100_000, style = inr))
            assertEquals("−₹12,345", Money.format(-1_234_500, wholeDollars = true, style = inr))
            assertEquals("$12,34,567.50", Money.format(123_456_750, style = usd))
        }
        Money.withStyle(testStyle(tag("de-DE"))) {
            assertEquals("€1.234,50", Money.format(123_450, style = Currencies.byCode("EUR")))
            assertEquals("+€0,05", Money.format(5, showPlus = true, style = Currencies.byCode("EUR")))
        }
        Money.withStyle(testStyle(tag("fr-FR"))) {
            val text = Money.format(123_456_750, style = Currencies.byCode("EUR"))
            assertTrue(text.startsWith("€1") && text.endsWith(",50"), text)
            // The group mark is a space, never a point or a comma.
            val grouped = text.removePrefix("€").dropLast(3)
            assertTrue(grouped.filterNot { it.isDigit() }.all { it.isWhitespace() }, text)
        }
        Money.withStyle(testStyle(tag("en-GB"))) {
            assertEquals("$12.00", Money.format(1_200, style = usd))
        }
    }

    @Test
    fun arabicDigitsFormatAndReadBackSanely() {
        Money.withStyle(testStyle(tag("ar-EG"))) {
            val text = Money.format(123_450, style = Currencies.byCode("EGP"))
            assertTrue(text.startsWith("EGP "), text)
            assertEquals(123_450L, Money.parseCents(text))
        }
        Money.withStyle(testStyle(tag("ar-AE"))) {
            val text = Money.format(123_450, style = Currencies.byCode("AED"))
            assertEquals(123_450L, Money.parseCents(text))
        }
    }

    @Test
    fun whatIsShownCanBeReadBackInEveryLanguage() {
        listOf("en-US", "en-IN", "de-DE", "fr-FR", "es-ES", "pt-BR", "ar-EG", "hi-IN", "bn-BD", "de-CH", "sv-SE", "pl-PL").forEach { t ->
            Money.withStyle(testStyle(tag(t))) {
                Currencies.all.forEach { c ->
                    listOf(5L, 123_450L, 123_456_789L, 99_999_999_99L).forEach { cents ->
                        assertEquals(cents, Money.parseCents(Money.sample(cents, style = c)), "$t ${c.code} $cents ${Money.sample(cents, style = c)}")
                        assertEquals(-cents, Money.parseCents(Money.sample(-cents, style = c)), "$t ${c.code} -$cents")
                    }
                }
            }
        }
    }

    @Test
    fun fieldsShowAndTakeThePhonesDecimalMark() {
        Money.withStyle(testStyle(tag("de-DE"))) {
            assertEquals("1234,5", Money.toInput(123_450))
            assertEquals("12", Money.toInput(1_200))
            assertEquals("0,05", Money.toInput(5))
            assertEquals(123_450L, Money.parseCents("1234,5"))
            assertEquals(123_450L, Money.parseCents("1.234,50"))
            assertEquals(123_400L, Money.parseCents("1.234"))
            assertEquals(1_250L, Money.parseCents("12.5"))
            assertEquals("12,34", InputRules.typedAmount("12,345"))
            assertEquals("12345", InputRules.typedAmount("12.345"))
            assertEquals("1250,50", InputRules.typedAmount("1,250.50"))
            assertEquals("12,", InputRules.typedAmount("12."))
            assertEquals("-40,5", InputRules.typedBalance("-40.5"))
            assertEquals(61_250L, InputRules.parseRate("6,125"))
            assertEquals(61_250L, InputRules.parseRate("6.125"))
            assertEquals("6,125", LoanRate.toInput(61_250))
            assertEquals("6,125%", LoanRate.display(61_250))
            assertEquals("1,5", Shares.format(1_500_000))
            assertEquals(1_500_000L, Shares.parse("1,5"))
        }
        Money.withStyle(testStyle(tag("en-US"))) {
            assertEquals(123_400L, Money.parseCents("1,234"))
            assertEquals(150L, Money.parseCents("1,5"))
            assertEquals(10_000_000L, Money.parseCents("1,00,000"))
            assertEquals(123_450L, Money.parseCents("1 234.50"))
            assertEquals(-500L, Money.parseCents("−5"))
            assertEquals(-500L, Money.parseCents("-$5.00"))
            assertNull(Money.parseCents("1.2.3"))
            assertNull(Money.parseCents("."))
            assertNull(Money.parseCents("-"))
            assertEquals(1_250L, Money.parseCents("12.5"))
        }
        Money.withStyle(testStyle(tag("fr-FR"))) {
            assertEquals(123_450L, Money.parseCents("1 234,50"))
            assertEquals(123_450L, Money.parseCents("1 234,50"))
            assertEquals(150L, Money.parseCents("1.5"))
            assertEquals("12,5", InputRules.typedAmount("12.5"))
        }
    }

    @Test
    fun filesAlwaysUseThePointWhateverThePhoneSpeaks() {
        Money.withStyle(testStyle(tag("de-DE"))) {
            assertEquals(123_400L, Money.parsePlain("1234.00"))
            assertEquals(123_450L, Money.parsePlain("1234.5"))
            assertEquals(-5L, Money.parsePlain("-0.05"))
        }
    }

    @Test
    fun everyCurrencyHasTwoMinorDigitsAndIsListedOnce() {
        Currencies.all.forEach { c ->
            assertEquals(2, Currency.getInstance(c.code).defaultFractionDigits, c.code)
            assertTrue(c.symbol.isNotBlank() && c.name.isNotBlank() && c.smallLimit > 0, c.code)
        }
        assertEquals(Currencies.all.size, Currencies.all.map { it.code }.toSet().size)
        listOf("JPY", "KRW", "VND", "KWD", "BHD", "OMR", "IDR").forEach { assertNull(Currencies.all.firstOrNull { c -> c.code == it }, it) }
        assertEquals(Currencies.all.size, Currencies.sorted().size)
    }

    @Test
    fun theDefaultCurrencyComesFromThePhonesRegion() {
        // The app turns the phone's region into an ISO code (Settings); the domain only looks the code up.
        fun forLocale(locale: Locale): CurrencyStyle? {
            if (locale.country.isEmpty()) return null
            return Currencies.forCode(try { Currency.getInstance(locale)?.currencyCode } catch (e: IllegalArgumentException) { null })
        }
        assertEquals("USD", forLocale(tag("en-US"))?.code)
        assertEquals("INR", forLocale(tag("en-IN"))?.code)
        assertEquals("GBP", forLocale(tag("en-GB"))?.code)
        assertEquals("AED", forLocale(tag("ar-AE"))?.code)
        assertEquals("EUR", forLocale(tag("de-DE"))?.code)
        assertEquals("BRL", forLocale(tag("pt-BR"))?.code)
        // No region, or a currency Kassa doesn't support yet: the first-run screen asks.
        assertNull(forLocale(tag("en")))
        assertNull(forLocale(tag("ja-JP")))
        assertNotNull(forLocale(tag("en-AU")))
        assertEquals("USD", Currencies.forCode("usd")?.code)
        assertNull(Currencies.forCode("JPY"))
        assertNull(Currencies.forCode(null))
    }
}
