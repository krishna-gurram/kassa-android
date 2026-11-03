package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformRulesTest {

    @Test
    fun namesReadTheProviderOnEveryCall() {
        val original = Names.comparatorProvider
        try {
            val list = listOf("b", "A", "c")
            assertEquals(listOf("A", "b", "c"), list.sortedWith(Names.order { it }))
            Names.comparatorProvider = { Comparator { a, b -> b.compareTo(a) } }
            assertEquals(listOf("c", "b", "A"), list.sortedWith(Names.order { it }))
        } finally {
            Names.comparatorProvider = original
        }
    }

    @Test
    fun namesAreTrimmedBeforeComparing() {
        assertEquals(listOf(" apple", "Banana "), listOf("Banana ", " apple").sortedWith(Names.order { it }))
    }

    @Test
    fun defaultMonthWordsAreEnglishWithSept() {
        val words = Repeats.monthWords()
        assertTrue("march" in words && "mar" in words && "sept" in words && "december" in words)
    }

    @Test
    fun halfwaySharesRoundUpLikeBefore() {
        assertEquals(3, roundHalfUp(2.5))
        assertEquals(-2, roundHalfUp(-2.5))
        assertEquals(-3, roundHalfUp(-2.6))
    }
}
