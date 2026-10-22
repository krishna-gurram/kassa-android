package dev.krishnagurram.kassa.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wording modeled on common US card alerts. Real alerts vary, so the parser favors partial results. */
class AlertParserTest {

    @Test
    fun chaseStyle() {
        val p = AlertParser.parse("Transaction alert", "You made a \$186.42 transaction with COSTCO WHSE #0123 on your Amazon Prime Visa ending in 4821.")!!
        assertEquals(18_642, p.amount)
        assertEquals("Costco Whse", p.merchant)
        assertEquals("4821", p.last4)
    }

    @Test
    fun amexStyle() {
        val p = AlertParser.parse("Amex", "A charge of \$63.20 at TRADER JOE'S was made on your Platinum Card ending 1005.")!!
        assertEquals(6_320, p.amount)
        assertEquals("Trader Joe's", p.merchant)
        assertEquals("1005", p.last4)
    }

    @Test
    fun citiStyle() {
        val p = AlertParser.parse(null, "A \$1,240.00 transaction was made at BEST BUY 00012 on card ending in 7781")!!
        assertEquals(124_000, p.amount)
        assertEquals("Best Buy", p.merchant)
    }

    @Test
    fun refundIsCredit() {
        val p = AlertParser.parse(null, "A refund of \$34.99 from AMAZON.COM was credited to your card ending in 4821")!!
        assertTrue(p.isCredit)
        assertEquals(3_499, p.amount)
    }

    @Test
    fun balanceRemindersAreIgnored() {
        assertNull(AlertParser.parse("Payment due", "Your statement balance of \$412.10 is due on Nov 4. Minimum payment \$40.00."))
        assertNull(AlertParser.parse("Hi", "Your package has shipped"))
    }

    @Test
    fun amountWithoutMerchantStillParses() {
        val p = AlertParser.parse("Card used", "Purchase of \$12.00 approved")!!
        assertEquals(1_200, p.amount)
    }
}
