package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Terms that change by date. The expected rows were worked out on paper and checked with an independent script:
 * 21,480.00 owed at 6% with a payment of 580.00, rate going to 7.25% on 1 January 2027.
 */
class LoanChangesTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    private val nov1 = day(2026, 11, 1)
    private val dec1 = day(2026, 12, 1)
    private val jan1 = day(2027, 1, 1)
    private val jul1 = day(2027, 7, 1)

    private fun terms(
        type: InterestType = InterestType.REDUCING, escrow: Long = 0, original: Long? = null, start: Int? = null,
    ) = LoanTerms(
        accountId = 1, type = type, rate = 60_000, payment = 58_000, originalAmount = original, startDay = start,
        nextPaymentDay = nov1, dueDay = 1, escrow = escrow,
    )

    private val rateUp = LoanTermChange(id = 1, accountId = 1, effectiveDay = jan1, rate = 72_500)
    private val cursor = LoanCursor(2_148_000, nov1, day(2026, 10, 1))
    private fun context(changes: List<LoanTermChange> = emptyList(), terms: LoanTerms = terms()) =
        LoanChangeContext(terms, changes, emptyList(), 2_148_000)

    // ---------- the terms in effect on a date ----------

    @Test
    fun eachChangeAppliesFromItsOwnDateAndNotBefore() {
        val pay = LoanTermChange(id = 2, accountId = 1, effectiveDay = jul1, payment = 60_000)
        val changes = listOf(rateUp, pay)
        val t = terms()
        assertEquals(EffectiveTerms(InterestType.REDUCING, 60_000, 58_000), t.effective(changes, jan1 - 1))
        assertEquals(EffectiveTerms(InterestType.REDUCING, 72_500, 58_000), t.effective(changes, jan1))
        assertEquals(EffectiveTerms(InterestType.REDUCING, 72_500, 58_000), t.effective(changes, jul1 - 1))
        assertEquals(EffectiveTerms(InterestType.REDUCING, 72_500, 60_000), t.effective(changes, jul1))
    }

    @Test
    fun twoChangesOnTheSameDayApplyInTheOrderTheyWereSaved() {
        val first = LoanTermChange(id = 1, accountId = 1, effectiveDay = jan1, rate = 70_000)
        val second = LoanTermChange(id = 2, accountId = 1, effectiveDay = jan1, rate = 80_000)
        assertEquals(80_000, terms().effective(listOf(second, first), jan1).rate)
    }

    @Test
    fun anotherLoansChangesAreIgnored() {
        val other = LoanTermChange(id = 1, accountId = 2, effectiveDay = jan1, rate = 99_000)
        assertEquals(60_000, terms().effective(listOf(other), jan1).rate)
    }

    // ---------- the schedule ----------

    @Test
    fun theScheduleKeepsTheOldSplitBeforeTheChangeAndUsesTheNewRateFromIt() {
        val rows = LoanEngine.project(terms(), listOf(rateUp), cursor)
        assertEquals(LoanRow(nov1, 47_260, 10_740, 0, 2_100_740), rows[0])
        assertEquals(LoanRow(dec1, 47_496, 10_504, 0, 2_053_244), rows[1])
        assertEquals(LoanRow(jan1, 45_595, 12_405, 0, 2_007_649), rows[2])
        assertEquals(LoanRow(day(2027, 2, 1), 45_870, 12_130, 0, 1_961_779), rows[3])
        assertEquals(LoanRow(day(2027, 3, 1), 46_148, 11_852, 0, 1_915_631), rows[4])
        // The first two rows are exactly what the loan gives with no change at all.
        assertEquals(LoanEngine.project(terms(), emptyList(), cursor).take(2), rows.take(2))
    }

    @Test
    fun interestRemainingAndTheLastPaymentFollowTheChange() {
        val without = LoanEngine.outlook(terms(), emptyList(), cursor)
        assertEquals(233_373, without.interestLeft)
        assertEquals(42, without.paymentsLeft)
        val with = LoanEngine.outlook(terms(), listOf(rateUp), cursor)
        assertEquals(285_174, with.interestLeft)
        assertEquals(42, with.paymentsLeft)
        assertEquals(day(2030, 4, 1), with.payoffDay)
        assertTrue(with.coversInterest)
    }

    @Test
    fun aChangeBookedAheadChangesNothingUntilItsDate() {
        val far = LoanTermChange(id = 1, accountId = 1, effectiveDay = day(2028, 6, 1), rate = 90_000)
        val rows = LoanEngine.project(terms(), listOf(far), cursor)
        val plain = LoanEngine.project(terms(), emptyList(), cursor)
        assertEquals(plain.takeWhile { it.day < day(2028, 6, 1) }, rows.takeWhile { it.day < day(2028, 6, 1) })
        assertTrue(rows.sumOf { it.interest } > plain.sumOf { it.interest })
    }

    @Test
    fun theBalanceOnADateIsWhatTheForecastLeavesJustBeforeIt() {
        assertEquals(2_148_000, LoanEngine.balanceOn(terms(), emptyList(), cursor, nov1))
        assertEquals(2_100_740, LoanEngine.balanceOn(terms(), emptyList(), cursor, dec1))
        assertEquals(2_053_244, LoanEngine.balanceOn(terms(), emptyList(), cursor, jan1))
        assertEquals(0, LoanEngine.balanceOn(terms(), emptyList(), cursor, day(2040, 1, 1)))
    }

    // ---------- the suggested split ----------

    @Test
    fun theSuggestedSplitUsesTheRateInEffectOnThePaymentDate() {
        val before = LoanEngine.suggest(58_000, dec1, 2_100_740, nov1, terms(), listOf(rateUp))
        assertEquals(10_504, before.interest)
        assertEquals(47_496, before.principal)
        val after = LoanEngine.suggest(58_000, jan1, 2_053_244, dec1, terms(), listOf(rateUp))
        assertEquals(12_405, after.interest)
        assertEquals(45_595, after.principal)
    }

    @Test
    fun theAmountDueUsesThePaymentInEffectAndAddsEscrow() {
        val pay = LoanTermChange(id = 2, accountId = 1, effectiveDay = jul1, payment = 60_000)
        val t = terms(escrow = 25_000)
        assertEquals(83_000L, LoanEngine.amountDue(t, listOf(pay), 1_500_000, jul1 - 31, jul1 - 1))
        assertEquals(85_000L, LoanEngine.amountDue(t, listOf(pay), 1_500_000, jul1 - 31, jul1))
    }

    @Test
    fun aPaymentRaisedByTheLenderIsScheduledFromThatDate() {
        val rows = LoanEngine.project(terms(), listOf(LoanTermChange(id = 1, accountId = 1, effectiveDay = jan1, payment = 62_000)), cursor)
        assertEquals(58_000, rows[1].principal + rows[1].interest)
        assertEquals(62_000, rows[2].principal + rows[2].interest)
    }

    // ---------- the change-terms form ----------

    @Test
    fun aRateChangeThatKeepsThePaymentMovesNothingButTheInterest() {
        val form = LoanChangeForm(day = jan1, rate = "7.25", kept = LenderKept.PAYMENT)
        val c = context()
        assertTrue(form.problems(c).isEmpty())
        val change = form.change(c)
        assertEquals(72_500, change.rate)
        assertNull(change.payment)
        val p = assertNotNull(form.preview(c))
        assertEquals(58_000, p.payment)
        assertEquals(58_000, p.paymentBefore)
        assertEquals(day(2030, 4, 1), p.payoffDay)
        assertEquals(285_174, p.interestLeft)
        assertEquals(233_373, p.interestBefore)
        assertNull(p.warning)
    }

    @Test
    fun aRateChangeThatKeepsTheTermChangesThePaymentAndSavesIt() {
        val form = LoanChangeForm(day = jan1, rate = "7.25", kept = LenderKept.TERM)
        val c = context()
        assertEquals(57_937, form.change(c).payment)
        val p = assertNotNull(form.preview(c))
        assertEquals(57_937, p.payment)
        assertEquals(58_000, p.paymentBefore)
        // The same payoff month as before the change.
        assertEquals(day(2030, 4, 1), p.payoffDay)
        assertEquals(42, p.paymentsLeft)
        assertEquals(285_495, p.interestLeft)
    }

    @Test
    fun aRateThatTheOldPaymentCannotCoverWarns() {
        val c = context(terms = terms().copy(payment = 11_000))
        val p = assertNotNull(LoanChangeForm(day = jan1, rate = "99", kept = LenderKept.PAYMENT).preview(c))
        assertNotNull(p.warning)
        assertNull(p.payoffDay)
    }

    @Test
    fun aPaymentChangeNeedsAnAmountAndShowsTheNewPayoff() {
        val c = context()
        assertTrue(ChangeField.PAYMENT in LoanChangeForm(kind = ChangeKind.PAYMENT, day = jan1).problems(c))
        val form = LoanChangeForm(kind = ChangeKind.PAYMENT, day = jan1, payment = "700")
        val p = assertNotNull(form.preview(c))
        assertEquals(70_000, p.payment)
        assertTrue(p.payoffDay!! < day(2030, 4, 1))
        assertTrue(p.interestLeft!! < p.interestBefore!!)
    }

    @Test
    fun aChangeCannotStartBeforeTheLoanDid() {
        val c = context(terms = terms(start = day(2025, 4, 1)))
        val problems = LoanChangeForm(day = day(2025, 3, 31), rate = "7").problems(c)
        assertTrue(ChangeField.DATE in problems)
        assertFalse(ChangeField.DATE in LoanChangeForm(day = day(2025, 4, 1), rate = "7").problems(c))
    }

    @Test
    fun aRateChangeNeedsARate() {
        assertTrue(ChangeField.RATE in LoanChangeForm(day = jan1).problems(context()))
        assertTrue(ChangeField.RATE in LoanChangeForm(day = jan1, rate = "101").problems(context()))
        assertNull(LoanChangeForm(day = jan1).preview(context()))
    }

    @Test
    fun interestOnlyTurnsIntoRepaymentWithATermAndKassaWorksOutThePayment() {
        val c = context(terms = terms(type = InterestType.INTEREST_ONLY))
        val form = LoanChangeForm(kind = ChangeKind.TYPE, day = jan1, type = InterestType.REDUCING, termMonths = "60")
        assertTrue(form.problems(c).isEmpty())
        val change = form.change(c)
        assertEquals(InterestType.REDUCING, change.type)
        assertEquals(41_527, change.payment)
        assertEquals(60, change.termMonths)
        // Paid off 60 payments after the switch, interest only before it.
        val p = assertNotNull(form.preview(c))
        assertEquals(41_527, p.payment)
        assertEquals(day(2031, 12, 1), p.payoffDay)
    }

    @Test
    fun aTypeChangeNeedsAPaymentOrATermUnlessItGoesToInterestOnly() {
        val c = context()
        assertTrue(ChangeField.PAYMENT in LoanChangeForm(kind = ChangeKind.TYPE, day = jan1, type = InterestType.DAILY).problems(c))
        assertTrue(ChangeField.TYPE in LoanChangeForm(kind = ChangeKind.TYPE, day = jan1).problems(c))
        assertTrue(ChangeField.TYPE in LoanChangeForm(kind = ChangeKind.TYPE, day = jan1, type = InterestType.REDUCING, payment = "500").problems(c))
        assertTrue(LoanChangeForm(kind = ChangeKind.TYPE, day = jan1, type = InterestType.INTEREST_ONLY).problems(c).isEmpty())
        // Flat needs the original amount, which the change can't give.
        assertTrue(ChangeField.TYPE in LoanChangeForm(kind = ChangeKind.TYPE, day = jan1, type = InterestType.FLAT, payment = "500").problems(c))
    }

    @Test
    fun aRefinanceTakesANewRateAndTermAndWorksOutThePaymentFromTheBalanceThen() {
        val c = context()
        val form = LoanChangeForm(kind = ChangeKind.REFINANCE, day = jan1, rate = "5.5", termMonths = "36")
        assertTrue(form.problems(c).isEmpty())
        val change = form.change(c)
        assertEquals(55_000, change.rate)
        assertEquals(36, change.termMonths)
        assertEquals(62_000, change.payment)
        // A typed payment wins.
        assertEquals(63_000, form.copy(payment = "630").change(c).payment)
        assertTrue(ChangeField.TERM in LoanChangeForm(kind = ChangeKind.REFINANCE, day = jan1, rate = "5.5").problems(c))
    }

    // ---------- the change itself ----------

    @Test
    fun aChangeMustChangeSomethingAndNotStartBeforeTheLoan() {
        val t = terms(start = day(2025, 4, 1))
        assertEquals("Nothing has changed.", LoanTermChange(accountId = 1, effectiveDay = jan1).problem(t))
        assertNull(LoanTermChange(accountId = 1, effectiveDay = jan1, rate = 70_000).problem(t))
        assertNotNull(LoanTermChange(accountId = 1, effectiveDay = day(2025, 1, 1), rate = 70_000).problem(t))
        assertNotNull(LoanTermChange(accountId = 1, effectiveDay = jan1, rate = 2_000_000).problem(t))
        assertNotNull(LoanTermChange(accountId = 1, effectiveDay = jan1, payment = 0).problem(t))
        assertNull(LoanTermChange(accountId = 1, effectiveDay = jan1, payment = 0).problem(terms(type = InterestType.INTEREST_ONLY)))
        assertNotNull(LoanTermChange(accountId = 1, effectiveDay = jan1, termMonths = 601).problem(t))
    }

    @Test
    fun aChangeReadsAsWhatChanged() {
        assertEquals("Rate 7.25%", LoanTermChange(accountId = 1, effectiveDay = jan1, rate = 72_500).summary())
        assertEquals("Profit rate 7.25%", LoanTermChange(accountId = 1, effectiveDay = jan1, rate = 72_500).summary(profit = true))
        assertEquals(
            "Rate 5.5%, Payment ${Money.format(62_000)}, Term 36 months, Interest type: Daily on balance",
            LoanTermChange(accountId = 1, effectiveDay = jan1, rate = 55_000, payment = 62_000, termMonths = 36, type = InterestType.DAILY).summary(),
        )
    }

    // ---------- the loan page ----------

    @Test
    fun theLoanPageShowsProgressOnlyWhenTheOriginalAmountIsKnown() {
        val withOriginal = LoanSummary.of(terms(original = 3_000_000), emptyList(), emptyList(), 2_148_000, 121_436)
        assertEquals(28, withOriginal.percentRepaid)
        assertEquals(0.284f, withOriginal.fractionRepaid!!, 0.0001f)
        val without = LoanSummary.of(terms(), emptyList(), emptyList(), 2_148_000, 0)
        assertNull(without.percentRepaid)
        assertNull(without.fractionRepaid)
    }

    @Test
    fun theLoanPageFiguresComeFromTheEngineWithTheChangesApplied() {
        val s = LoanSummary.of(terms(), listOf(rateUp), emptyList(), 2_148_000, 121_436)
        assertEquals(2_148_000, s.owed)
        assertEquals(58_000, s.payment)
        assertEquals(121_436, s.interestPaid)
        assertEquals(285_174, s.interestLeft)
        assertEquals(day(2030, 4, 1), s.payoffDay)
        assertEquals(42, s.rows.size)
        assertEquals(12_405, s.rows[2].interest)
    }

    @Test
    fun progressStaysWithinTheBarWhenMoreIsOwedThanTheOriginalOrNothingIsOwed() {
        assertEquals(0, LoanSummary.of(terms(original = 1_000_000), emptyList(), emptyList(), 2_148_000, 0).percentRepaid)
        assertEquals(100, LoanSummary.of(terms(original = 1_000_000), emptyList(), emptyList(), 0, 0).percentRepaid)
        assertEquals(0, LoanSummary.of(terms(), emptyList(), emptyList(), 0, 0).rows.size)
    }

    // ---------- activity tags ----------

    @Test
    fun everyEntryOfAPaymentCarriesATagAndOpensThePayment() {
        val p = LoanPayment(
            id = 1, accountId = 1, day = nov1, amount = 100, principalEntryId = 10, interestEntryId = 11, otherEntryId = 12,
            escrow = 5, charge = 0, edited = false, estimate = 0, scheduled = true,
        )
        assertEquals(LoanPart.PRINCIPAL, p.partOf(10))
        assertEquals(LoanPart.INTEREST, p.partOf(11))
        assertEquals(LoanPart.OTHER, p.partOf(12))
        assertNull(p.partOf(13))
        assertEquals("principal", p.tag(LoanPart.PRINCIPAL, "Interest"))
        assertEquals("interest", p.tag(LoanPart.INTEREST, "Interest"))
        assertEquals("profit", p.tag(LoanPart.INTEREST, "Profit"))
        assertEquals("escrow", p.tag(LoanPart.OTHER, "Interest"))
        assertEquals("charge", p.copy(escrow = 0, charge = 5).tag(LoanPart.OTHER, "Interest"))
        assertEquals("escrow, charge", p.copy(charge = 5).tag(LoanPart.OTHER, "Interest"))
    }

    @Test
    fun aPaymentWithNoInterestEntryStillFindsItsOtherEntries() {
        val p = LoanPayment(
            id = 1, accountId = 1, day = nov1, amount = 100, principalEntryId = 10, interestEntryId = null, otherEntryId = null,
            escrow = 0, charge = 0, edited = false, estimate = 0, scheduled = false,
        )
        assertEquals(LoanPart.PRINCIPAL, p.partOf(10))
        assertNull(p.partOf(11))
    }

    @Test
    fun loanNotesAreNotSuggestedAsBills() {
        val keys = LoanNotes.repeatKeys("Car loan")
        assertTrue(Repeats.key(LoanNotes.interest("Car loan", "Interest")) in keys)
        assertTrue(Repeats.key(LoanNotes.interest("Car loan", "Profit")) in keys)
        assertTrue(Repeats.key(LoanNotes.other("Car loan", 5, 0)) in keys)
        assertTrue(Repeats.key(LoanNotes.other("Car loan", 0, 5)) in keys)
        assertFalse(Repeats.key("Groceries") in keys)
    }

    // ---------- edit details ----------

    @Test
    fun editDetailsKeepsWhatIsNotDatedAndChecksEachFigure() {
        val t = terms(original = 3_000_000).copy(escrow = 25_000, startDay = day(2025, 4, 1))
        val form = LoanDetailsForm.of("Car loan", t)
        assertEquals("250", form.escrow)
        assertTrue(form.problems(t, 2_148_000).isEmpty())
        assertEquals(t, form.applyTo(t))
        val bad = form.copy(name = " ", dueDay = "32", escrow = "x", original = "100")
        val problems = bad.problems(t, 2_148_000)
        assertTrue(LoanField.NAME in problems)
        assertTrue(LoanField.DUE_DAY in problems)
        assertTrue(LoanField.ESCROW in problems)
        assertTrue(LoanField.ORIGINAL in problems)
    }

    @Test
    fun aNewDueDayMovesTheNextPaymentToThatDayOfTheSameMonth() {
        val t = terms()
        val moved = LoanDetailsForm.of("Car loan", t).copy(dueDay = "15").applyTo(t)
        assertEquals(15, moved.dueDay)
        assertEquals(day(2026, 11, 15), moved.nextPaymentDay)
        // The original amount and start can be cleared again; a flat loan can't lose its original amount.
        val cleared = LoanDetailsForm.of("Car loan", t.copy(originalAmount = 5_000_000)).copy(original = "").applyTo(t.copy(originalAmount = 5_000_000))
        assertNull(cleared.originalAmount)
        val flat = terms(type = InterestType.FLAT, original = 3_000_000)
        assertTrue(LoanField.ORIGINAL in LoanDetailsForm.of("Car loan", flat).copy(original = "").problems(flat, 2_148_000))
    }
}
