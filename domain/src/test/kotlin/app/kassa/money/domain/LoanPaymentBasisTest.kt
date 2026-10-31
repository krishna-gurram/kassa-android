package app.kassa.money.domain

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The change-terms form's payment, typed as principal and interest or as a total with the loan's escrow in it. */
class LoanPaymentBasisTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    @Before
    fun usd() {
        Money.style = Currencies.byCode("USD")
    }

    private val nov1 = day(2026, 11, 1)
    private val jan1 = day(2027, 1, 1)

    private fun context(escrow: Long, type: InterestType = InterestType.REDUCING) = LoanChangeContext(
        LoanTerms(accountId = 1, type = type, rate = 60_000, payment = 58_000, nextPaymentDay = nov1, dueDay = 1, escrow = escrow),
        emptyList(), emptyList(), 2_148_000,
    )

    private fun form(payment: String, basis: PaymentBasis, kind: ChangeKind = ChangeKind.PAYMENT) =
        LoanChangeForm(kind = kind, day = jan1, payment = payment, termMonths = "48", rate = "6", paymentBasis = basis)

    @Test
    fun principalAndInterestSavesWhatWasTyped() {
        val c = context(escrow = 60_000)
        val f = form("700", PaymentBasis.PRINCIPAL_AND_INTEREST)
        assertTrue(f.problems(c).isEmpty())
        assertEquals(70_000, f.change(c).payment)
        assertNull(f.paymentSplit(c))
    }

    @Test
    fun aTotalIncludingEscrowSavesTheDifference() {
        val c = context(escrow = 60_000)
        val f = form("2500", PaymentBasis.INCLUDES_ESCROW)
        assertTrue(f.problems(c).isEmpty())
        assertEquals(190_000, f.change(c).payment)
        assertEquals(PaymentSplit(190_000, 60_000), f.paymentSplit(c))
        assertNotNull(f.preview(c))
        assertEquals(190_000, f.preview(c)?.payment)
    }

    @Test
    fun aRefinanceAndATypeChangeTakeTheTotalToo() {
        val c = context(escrow = 60_000)
        assertEquals(190_000, form("2500", PaymentBasis.INCLUDES_ESCROW, ChangeKind.REFINANCE).change(c).payment)
        val type = form("2500", PaymentBasis.INCLUDES_ESCROW, ChangeKind.TYPE).copy(type = InterestType.FLAT)
        assertEquals(190_000, type.change(c.copy(terms = c.terms.copy(originalAmount = 3_000_000))).payment)
    }

    @Test
    fun aLoanWithNoEscrowIgnoresTheChoice() {
        val c = context(escrow = 0)
        val f = form("700", PaymentBasis.INCLUDES_ESCROW)
        assertTrue(f.problems(c).isEmpty())
        assertEquals(70_000, f.change(c).payment)
        assertNull(f.paymentSplit(c))
    }

    @Test
    fun escrowAtOrAboveTheTotalIsAnError() {
        val c = context(escrow = 60_000)
        for (total in listOf("600", "500")) {
            val problems = form(total, PaymentBasis.INCLUDES_ESCROW).problems(c)
            assertEquals("The total payment has to be more than the escrow.", problems[ChangeField.PAYMENT])
            assertNull(form(total, PaymentBasis.INCLUDES_ESCROW).preview(c))
        }
        assertFalse(ChangeField.PAYMENT in form("600.01", PaymentBasis.INCLUDES_ESCROW).problems(c))
        // The same figure is fine as principal and interest.
        assertTrue(form("500", PaymentBasis.PRINCIPAL_AND_INTEREST).problems(c).isEmpty())
    }

    @Test
    fun interestOnlyMayLeaveNothingAfterTheEscrow() {
        val c = context(escrow = 60_000, type = InterestType.INTEREST_ONLY)
        assertTrue(form("600", PaymentBasis.INCLUDES_ESCROW).problems(c).isEmpty())
        assertEquals(0L, form("600", PaymentBasis.INCLUDES_ESCROW).change(c).payment)
        assertTrue(ChangeField.PAYMENT in form("599", PaymentBasis.INCLUDES_ESCROW).problems(c))
    }

    @Test
    fun aRateChangeIgnoresThePaymentChoice() {
        val c = context(escrow = 60_000)
        val f = LoanChangeForm(day = jan1, rate = "7.25", paymentBasis = PaymentBasis.INCLUDES_ESCROW)
        assertTrue(f.problems(c).isEmpty())
    }

    @Test
    fun switchingBackAndForthKeepsWhatWasTyped() {
        val c = context(escrow = 60_000)
        val a = form("2500", PaymentBasis.PRINCIPAL_AND_INTEREST)
        val b = a.copy(paymentBasis = PaymentBasis.INCLUDES_ESCROW)
        assertEquals("2500", b.payment)
        assertEquals(250_000, a.change(c).payment)
        assertEquals(190_000, b.change(c).payment)
        assertEquals(a, b.copy(paymentBasis = PaymentBasis.PRINCIPAL_AND_INTEREST))
    }
}
