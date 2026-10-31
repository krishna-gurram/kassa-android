package app.kassa.money.domain

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoanPaymentFormTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    @Before
    fun usd() {
        Money.style = Currencies.byCode("USD")
    }

    private val nov1 = day(2026, 11, 1)

    private fun terms(type: InterestType = InterestType.REDUCING, escrow: Long = 0, earlyCharge: Long = 0) = LoanTerms(
        accountId = 1, type = type, rate = 60_000, payment = if (type == InterestType.FLAT) 65_000 else 58_000, nextPaymentDay = nov1, dueDay = 1, escrow = escrow, earlyCharge = earlyCharge,
        originalAmount = if (type == InterestType.FLAT) 3_000_000 else null,
    )

    private fun ctx(t: LoanTerms = terms(), balance: Long = 2_148_000, payments: List<LoanPayment> = emptyList()) =
        LoanPaymentContext(t, emptyList(), payments, balance)

    private fun start(fromId: Long? = 5) = LoanPaymentForm.starting(nov1, fromId)

    @Test
    fun theSuggestedSplitIsFilledInAsTheAmountIsTyped() {
        val c = ctx()
        val f = start().withAmount("580", c)
        assertEquals("472.6", f.principal)
        assertEquals("107.4", f.interest)
        assertEquals("Estimated from \$21,480.00 at 6% for one month.", f.hint(c))
        assertFalse(f.isEdited(c))
        assertEquals(2_100_740, f.balanceAfter(c))
        assertEquals(58_000, f.scheduled(c))
        assertTrue(f.problems(c).isEmpty())
    }

    @Test
    fun editingInterestMakesPrincipalFollowAndSaysSo() {
        val c = ctx()
        val f = start().withAmount("580", c).withInterest("110", c)
        assertEquals("110", f.interest)
        assertEquals("470", f.principal)
        assertTrue(f.isEdited(c))
        assertEquals("Edited by you. Kassa's estimate was \$107.40 interest.", f.hint(c))
        val parts = f.parts(c)
        assertEquals(LoanPaymentParts(58_000, 47_000, 11_000, 0, 0, edited = true, estimate = 10_740), parts)
        assertEquals(parts.amount, parts.principal + parts.interest + parts.escrow + parts.charge)
    }

    @Test
    fun editingPrincipalMakesInterestFollow() {
        val c = ctx()
        val f = start().withAmount("580", c).withPrincipal("500", c)
        assertEquals("80", f.interest)
        assertEquals("500", f.principal)
        assertEquals(58_000, f.parts(c).let { it.principal + it.interest })
    }

    @Test
    fun anEditedPartStaysWhenTheAmountChangesAndTheOtherFollows() {
        val c = ctx()
        val f = start().withAmount("580", c).withPrincipal("500", c).withAmount("1000", c)
        assertEquals("500", f.principal)
        assertEquals("500", f.interest)
        // Never edited, the whole split is estimated again: 1,000.00 less 107.40 of interest.
        val g = start().withAmount("580", c).withAmount("1000", c)
        assertEquals("892.6", g.principal)
        assertEquals("107.4", g.interest)
    }

    @Test
    fun aPartCannotBeMoreThanThereIsToSplit() {
        val c = ctx()
        val f = start().withAmount("580", c).withInterest("999", c)
        assertEquals("580", f.interest)
        assertEquals("0", f.principal)
        val g = start().withAmount("580", c).withPrincipal("900", c)
        assertEquals("580", g.principal)
        assertEquals("0", g.interest)
        // Shrinking the amount below an edited part trims that part too.
        val h = start().withAmount("580", c).withInterest("300", c).withAmount("200", c)
        assertEquals("200", h.interest)
        assertEquals("0", h.principal)
    }

    @Test
    fun typingTheEstimateBackIsNotAnEdit() {
        val c = ctx()
        val f = start().withAmount("580", c).withInterest("107.40", c)
        assertFalse(f.isEdited(c))
        assertEquals("Estimated from \$21,480.00 at 6% for one month.", f.hint(c))
        assertFalse(f.parts(c).edited)
    }

    @Test
    fun extraAboveTheScheduledPaymentGoesToPrincipal() {
        val c = ctx()
        val f = start().withAmount("1000", c)
        assertEquals("892.6", f.principal)
        assertEquals(42_000, f.suggestion(c).extra)
        assertEquals(2_148_000 - 89_260, f.balanceAfter(c))
    }

    @Test
    fun aDailyLoanSaysHowManyDaysTheInterestIsFor() {
        val t = terms(InterestType.DAILY)
        val c = ctx(t)
        val f = start().withAmount("580", c)
        assertEquals("Estimated from \$21,480.00 at 6% for 31 days.", f.hint(c))
        assertEquals("109.46", f.interest)
        // Paying on the 2nd of October, a day after the last payment, is one day.
        val early = start().withDay(day(2026, 10, 2), c).withAmount("580", c)
        assertEquals("Estimated from \$21,480.00 at 6% for 1 day.", early.hint(c))
        assertEquals("3.53", early.interest)
    }

    @Test
    fun theLastPaymentDayComesFromThePreviousPaymentNotTheStart() {
        val paid = listOf(LoanPayment(1, 1, day(2026, 10, 20), 58_000, 1, 2, null, 0, 0, false, 0, true))
        val c = ctx(terms(InterestType.DAILY), payments = paid)
        val f = start().withAmount("580", c)
        // 20 October to 1 November is 12 days.
        assertEquals("Estimated from \$21,480.00 at 6% for 12 days.", f.hint(c))
    }

    @Test
    fun aFlatLoanHintNamesTheAmountTheInterestIsOn() {
        val c = ctx(terms(InterestType.FLAT), balance = 1_800_000)
        val f = start().withAmount("650", c)
        assertEquals("Estimated from \$30,000.00 at 6% flat for one month.", f.hint(c))
        assertEquals("150", f.interest)
        assertEquals("500", f.principal)
    }

    @Test
    fun escrowComesOffTheTopAndTheEditedPartsShareWhatIsLeft() {
        val c = ctx(terms(escrow = 20_000))
        val f = start().withAmount("780", c)
        assertEquals("472.6", f.principal)
        assertEquals("107.4", f.interest)
        assertEquals(20_000, f.parts(c).escrow)
        assertEquals(78_000, f.scheduled(c))
        // Only 580.00 is left to split, so 600.00 of interest is capped there.
        val g = f.withInterest("600", c)
        assertEquals("580", g.interest)
        assertEquals("0", g.principal)
        assertEquals(78_000, g.parts(c).let { it.principal + it.interest + it.escrow + it.charge })
    }

    @Test
    fun theEarlyRepaymentChargeIsShownAndTakenFromTheExtra() {
        val c = ctx(terms(earlyCharge = 10_000))
        val f = start().withAmount("1000", c)
        assertEquals(420, f.suggestion(c).charge)
        assertEquals("888.4", f.principal)
        val p = f.parts(c)
        assertEquals(100_000, p.principal + p.interest + p.escrow + p.charge)
        // Keeping to the scheduled payment costs nothing.
        assertEquals(0, start().withAmount("580", c).suggestion(c).charge)
    }

    @Test
    fun nothingGuessesTheAccountItIsPaidFrom() {
        val c = ctx()
        val f = start(fromId = null).withAmount("580", c)
        assertEquals("Pick the account it's paid from.", f.problems(c)[LoanPaymentField.FROM])
        assertNull(f.fromId)
    }

    @Test
    fun anAmountThatIsMoreThanTheLoanNeedsIsRefusedWithTheSurplus() {
        val c = ctx(balance = 10_000)
        val f = start().withAmount("200", c)
        assertEquals("That's \$99.50 more than the loan needs.", f.problems(c)[LoanPaymentField.AMOUNT])
        val blank = start()
        assertEquals("Enter an amount.", blank.problems(c)[LoanPaymentField.AMOUNT])
    }

    @Test
    fun aSavedPaymentOpensWithItsPartsAndRemembersKassasEstimate() {
        val saved = LoanPayment(4, 1, nov1, 58_000, 10, 11, null, 0, 0, edited = true, estimate = 10_740, scheduled = true)
        val c = LoanPaymentContext(terms(), emptyList(), listOf(saved), balance = 2_148_000 + 47_000, editing = saved)
        val f = LoanPaymentForm.of(saved, principal = 47_000, interest = 11_000, fromId = 5, categoryId = null)
        assertEquals("580", f.amount)
        assertEquals("110", f.interest)
        assertEquals("Edited by you. Kassa's estimate was \$107.40 interest.", f.hint(c))
        assertTrue(f.parts(c).edited)
        assertEquals(10_740, f.parts(c).estimate)
    }

    @Test
    fun profitWordingFollowsTheTerms() {
        val t = terms().copy(profit = true)
        val c = ctx(t)
        val f = start().withAmount("580", c).withInterest("110", c)
        assertEquals("Edited by you. Kassa's estimate was \$107.40 profit.", f.hint(c))
    }
}
