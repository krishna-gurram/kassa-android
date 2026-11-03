package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-checked loans. Amounts are cents, so 30,000.00 is 3_000_000, and rates are 1/10000 of a percent, so 6% is 60_000.
 * The expected figures were worked out on paper and checked with an independent script, not copied from the code.
 */
class LoanEngineTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    private val nov1 = day(2026, 11, 1)
    private val oct1 = day(2026, 10, 1)

    private fun terms(
        type: InterestType = InterestType.REDUCING,
        rate: Long = 60_000,
        payment: Long = 58_000,
        original: Long? = null,
        escrow: Long = 0,
        earlyCharge: Long = 0,
        next: Int = nov1,
    ) = LoanTerms(
        accountId = 1, type = type, rate = rate, payment = payment, originalAmount = original, nextPaymentDay = next, dueDay = 1,
        escrow = escrow, earlyCharge = earlyCharge,
    )

    private fun cursor(balance: Long, next: Int = nov1, last: Int = oct1) = LoanCursor(balance, next, last)

    // ---------- reducing balance ----------

    @Test
    fun aPaymentFor30000At6PercentOver60MonthsIs579Point98() {
        assertEquals(57_998, LoanEngine.emi(3_000_000, 60_000, 60))
        assertEquals(57_998, LoanEngine.suggestedPayment(InterestType.REDUCING, 3_000_000, 60_000, 60))
    }

    @Test
    fun theFirstMonthOf30000At6PercentChargesExactly150() {
        assertEquals(15_000, LoanEngine.monthlyInterest(3_000_000, 60_000))
        val rows = LoanEngine.project(terms(payment = 57_998), emptyList(), cursor(3_000_000))
        assertEquals(LoanRow(nov1, 42_998, 15_000, 0, 2_957_002), rows.first())
    }

    @Test
    fun theWholeLoanIs60PaymentsAndTheLastOneAbsorbsTheRoundingCents() {
        val rows = LoanEngine.project(terms(payment = 57_998), emptyList(), cursor(3_000_000))
        assertEquals(60, rows.size)
        assertEquals(0, rows.last().balance)
        // 60 x 579.98 leaves 0.29 of drift, which joins the last payment instead of making a 61st for 29 cents.
        assertEquals(57_738, rows.last().principal)
        assertEquals(289, rows.last().interest)
        assertEquals(3_000_000, rows.sumOf { it.principal })
        assertEquals(479_909, rows.sumOf { it.interest })
        assertEquals(day(2031, 10, 1), rows.last().day)
    }

    @Test
    fun aBalanceOf21480At6PercentOwes107Point40() {
        assertEquals(10_740, LoanEngine.monthlyInterest(2_148_000, 60_000))
        val s = LoanEngine.suggest(58_000, nov1, 2_148_000, oct1, terms(), emptyList())
        assertEquals(47_260, s.principal)
        assertEquals(10_740, s.interest)
        assertEquals(0, s.extra)
        assertEquals(0, s.leftover)
        assertNull(s.days)
    }

    @Test
    fun interestRoundsHalfUpToTheCent() {
        // 1.00 at 6% for a month is exactly half a cent, which rounds up; 0.99 is under half.
        assertEquals(1, LoanEngine.monthlyInterest(100, 60_000))
        assertEquals(0, LoanEngine.monthlyInterest(99, 60_000))
    }

    @Test
    fun aRateWithFourDecimalsIsExact() {
        assertEquals(61_250, InputRules.parseRate("6.125"))
        assertEquals("6.125", LoanRate.toInput(61_250))
        assertEquals("6", LoanRate.toInput(60_000))
        // 21,480.00 at 6.125% for a month is 109.6375, which is 10,963.75 cents and rounds up to 109.64.
        assertEquals(10_964, LoanEngine.monthlyInterest(2_148_000, 61_250))
    }

    @Test
    fun aLoanOf21480WithA580PaymentHas42PaymentsLeft() {
        val o = LoanEngine.outlook(terms(), emptyList(), cursor(2_148_000))
        assertEquals(42, o.paymentsLeft)
        assertEquals(233_373, o.interestLeft)
        assertEquals(day(2030, 4, 1), o.payoffDay)
        assertTrue(o.coversInterest)
    }

    @Test
    fun anExtraPaymentShortensTheLoanAndSavesInterest() {
        val t = terms()
        // The 1 November payment as scheduled leaves 41 more; paying 1,000.00 instead sends 420.00 to principal and leaves 40.
        val normal = LoanEngine.suggest(58_000, nov1, 2_148_000, oct1, t, emptyList())
        val extra = LoanEngine.suggest(100_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(42_000, extra.extra)
        assertEquals(10_740, extra.interest)
        assertEquals(89_260, extra.principal)
        val dec1 = day(2026, 12, 1)
        val afterNormal = LoanEngine.outlook(t, emptyList(), LoanCursor(2_148_000 - normal.principal, dec1, nov1))
        val afterExtra = LoanEngine.outlook(t, emptyList(), LoanCursor(2_148_000 - extra.principal, dec1, nov1))
        assertEquals(41, afterNormal.paymentsLeft)
        assertEquals(222_633, afterNormal.interestLeft)
        assertEquals(40, afterExtra.paymentsLeft)
        assertEquals(213_346, afterExtra.interestLeft)
    }

    // ---------- daily simple interest ----------

    @Test
    fun dailyInterestFollowsTheDaysSinceTheLastPayment() {
        val t = terms(type = InterestType.DAILY)
        val thirtyOne = LoanEngine.suggest(58_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(31, thirtyOne.days)
        assertEquals(10_946, thirtyOne.interest)
        assertEquals(47_054, thirtyOne.principal)
        val feb1 = day(2027, 2, 1)
        val twentyEight = LoanEngine.suggest(58_000, day(2027, 3, 1), 2_148_000, feb1, t, emptyList())
        assertEquals(28, twentyEight.days)
        assertEquals(9_887, twentyEight.interest)
        // A leap February has 29 days.
        val leap = LoanEngine.suggest(58_000, day(2028, 3, 1), 2_148_000, day(2028, 2, 1), t, emptyList())
        assertEquals(29, leap.days)
        assertEquals(10_240, leap.interest)
    }

    @Test
    fun payingEarlyOnADailyLoanLowersTheInterest() {
        val t = terms(type = InterestType.DAILY)
        val onTime = LoanEngine.suggest(58_000, nov1, 2_148_000, oct1, t, emptyList())
        val tenDaysIn = LoanEngine.suggest(58_000, oct1 + 10, 2_148_000, oct1, t, emptyList())
        assertEquals(10, tenDaysIn.days)
        assertEquals(3_531, tenDaysIn.interest)
        assertTrue(tenDaysIn.interest < onTime.interest)
        assertTrue(tenDaysIn.principal > onTime.principal)
    }

    @Test
    fun aDailyForecastCountsTheRealDaysBetweenDueDates() {
        val rows = LoanEngine.project(terms(type = InterestType.DAILY), emptyList(), cursor(2_148_000), limit = 3)
        // 1 Oct to 1 Nov is 31 days, 1 Nov to 1 Dec is 30, 1 Dec to 1 Jan is 31.
        assertEquals(listOf(10_946L, 10_361L, 10_463L), rows.map { it.interest })
        assertEquals(listOf(47_054L, 47_639L, 47_537L), rows.map { it.principal })
        assertEquals(2_005_770, rows.last().balance)
    }

    @Test
    fun dailyInterestCannotOverflowAtTheLargestAmounts() {
        assertEquals(833_333_333, LoanEngine.monthlyInterest(InputRules.AMOUNT_MAX, InputRules.RATE_MAX))
        assertEquals(999_999_999_900, LoanEngine.dailyInterest(InputRules.AMOUNT_MAX, InputRules.RATE_MAX, 36_500))
    }

    // ---------- flat rate ----------

    @Test
    fun flatSixPercentOver60MonthsOn30000CostsExactly9000() {
        assertEquals(900_000, LoanEngine.flatTotalInterest(3_000_000, 60_000, 60))
        assertEquals(65_000, LoanEngine.flatPayment(3_000_000, 60_000, 60))
        val t = terms(type = InterestType.FLAT, payment = 65_000, original = 3_000_000)
        val rows = LoanEngine.project(t, emptyList(), cursor(3_000_000))
        assertEquals(60, rows.size)
        assertTrue(rows.all { it.interest == 15_000L && it.principal == 50_000L })
        assertEquals(900_000, rows.sumOf { it.interest })
        assertEquals(0, rows.last().balance)
    }

    @Test
    fun flatSixPercentIsAboutTenPointEightFivePercentOnTheBalance() {
        assertEquals(108_479, LoanEngine.equivalentReducingRate(3_000_000, 65_000, 60))
        Money.style = Currencies.byCode("USD")
        assertEquals("10.8479%", LoanRate.display(108_479))
        assertEquals("6%", LoanRate.display(60_000))
    }

    @Test
    fun aFlatPaymentSplitsEvenlyWhateverTheBalance() {
        val t = terms(type = InterestType.FLAT, payment = 65_000, original = 3_000_000)
        val s = LoanEngine.suggest(65_000, nov1, 1_800_000, oct1, t, emptyList())
        assertEquals(15_000, s.interest)
        assertEquals(50_000, s.principal)
    }

    @Test
    fun aFlatLoanUnderWayWorksOutItsOriginalAmountFromWhatIsLeft() {
        // 36 payments left of 650.00 on 18,000.00: 500.00 of principal each, so 150.00 of interest, so 30,000.00 at 6%.
        assertEquals(3_000_000, LoanEngine.flatBaseFrom(1_800_000, 65_000, 60_000, 36))
        assertNull(LoanEngine.flatBaseFrom(1_800_000, 50_000, 60_000, 36))
        assertEquals(1_800_000, LoanEngine.flatBaseFrom(1_800_000, 50_000, 0, 36))
    }

    // ---------- interest only ----------

    @Test
    fun interestOnlyPaysTheInterestAndTheBalanceStays() {
        assertEquals(15_000, LoanEngine.suggestedPayment(InterestType.INTEREST_ONLY, 3_000_000, 60_000, 60))
        val t = terms(type = InterestType.INTEREST_ONLY, payment = 15_000)
        val rows = LoanEngine.project(t, emptyList(), cursor(3_000_000), limit = 12)
        assertEquals(12, rows.size)
        assertTrue(rows.all { it.principal == 0L && it.interest == 15_000L && it.balance == 3_000_000L })
        val o = LoanEngine.outlook(t, emptyList(), cursor(3_000_000))
        assertNull(o.paymentsLeft)
        assertTrue(o.coversInterest)
        // Anything above the interest is extra and goes to principal.
        val s = LoanEngine.suggest(65_000, nov1, 3_000_000, oct1, t, emptyList())
        assertEquals(15_000, s.interest)
        assertEquals(50_000, s.principal)
        assertEquals(50_000, s.extra)
    }

    @Test
    fun interestOnlySwitchesToRepaymentOnADate() {
        val t = terms(type = InterestType.INTEREST_ONLY, payment = 15_000)
        val switch = LoanTermChange(accountId = 1, effectiveDay = LoanDates.addMonths(nov1, 12), type = InterestType.REDUCING, payment = 57_998)
        val rows = LoanEngine.project(t, listOf(switch), cursor(3_000_000))
        assertTrue(rows.take(12).all { it.principal == 0L })
        assertEquals(42_998, rows[12].principal)
        val o = LoanEngine.outlook(t, listOf(switch), cursor(3_000_000))
        assertEquals(72, o.paymentsLeft)
        assertEquals(659_909, o.interestLeft)
    }

    // ---------- rate changes ----------

    @Test
    fun whenTheLenderKeepsThePaymentTheLoanRunsLonger() {
        val r = LoanEngine.rateChange(terms(), emptyList(), cursor(2_030_000), nov1, 72_500, LenderKept.PAYMENT)
        assertEquals(58_000, r.payment)
        assertNull(r.change.payment)
        assertEquals(39, r.paymentsLeftBefore)
        assertEquals(40, r.outlook.paymentsLeft)
        assertEquals(257_596, r.outlook.interestLeft)
    }

    @Test
    fun whenTheLenderKeepsTheTermThePaymentRises() {
        val r = LoanEngine.rateChange(terms(), emptyList(), cursor(2_030_000), nov1, 72_500, LenderKept.TERM)
        assertEquals(58_581, r.payment)
        assertEquals(58_581, r.change.payment)
        assertEquals(39, r.outlook.paymentsLeft)
        assertEquals(254_638, r.outlook.interestLeft)
    }

    @Test
    fun paymentsBeforeAChangeKeepTheirOldRateAndLaterOnesUseTheNew() {
        val t = terms()
        val change = LoanTermChange(id = 1, accountId = 1, effectiveDay = day(2027, 1, 1), rate = 72_500)
        assertEquals(60_000, t.effective(listOf(change), day(2026, 12, 31)).rate)
        assertEquals(72_500, t.effective(listOf(change), day(2027, 1, 1)).rate)
        assertEquals(58_000, t.effective(listOf(change), day(2027, 6, 1)).payment)
        val before = LoanEngine.suggest(58_000, day(2026, 12, 1), 2_030_000, day(2026, 11, 1), t, listOf(change))
        val after = LoanEngine.suggest(58_000, day(2027, 1, 1), 2_030_000, day(2026, 12, 1), t, listOf(change))
        assertEquals(10_150, before.interest)
        assertEquals(12_265, after.interest)
    }

    @Test
    fun changesApplyInDateOrderAndOtherAccountsAreIgnored() {
        val t = terms()
        val changes = listOf(
            LoanTermChange(id = 2, accountId = 1, effectiveDay = day(2027, 6, 1), rate = 50_000, payment = 60_000),
            LoanTermChange(id = 1, accountId = 1, effectiveDay = day(2027, 1, 1), rate = 72_500),
            LoanTermChange(id = 3, accountId = 9, effectiveDay = day(2027, 1, 1), rate = 1),
        )
        assertEquals(EffectiveTerms(InterestType.REDUCING, 72_500, 58_000), t.effective(changes, day(2027, 3, 1)))
        assertEquals(EffectiveTerms(InterestType.REDUCING, 50_000, 60_000), t.effective(changes, day(2027, 6, 1)))
    }

    @Test
    fun aChangeThatGivesANewTermWorksOutThePaymentWhenItStarts() {
        val t = terms()
        val change = LoanTermChange(accountId = 1, effectiveDay = nov1, rate = 72_500, termMonths = 39)
        val o = LoanEngine.outlook(t, listOf(change), cursor(2_030_000))
        assertEquals(39, o.paymentsLeft)
        assertEquals(254_638, o.interestLeft)
    }

    // ---------- checks, escrow, charges, limits ----------

    @Test
    fun aPaymentThatDoesNotCoverTheInterestIsFlaggedAndNeverPaysOff() {
        val t = terms(payment = 5_000)
        assertFalse(LoanEngine.coversInterest(t.type, t.rate, 2_148_000, t.payment, 0))
        assertTrue(LoanEngine.coversInterest(t.type, t.rate, 2_148_000, 58_000, 0))
        val o = LoanEngine.outlook(t, emptyList(), cursor(2_148_000))
        assertFalse(o.coversInterest)
        assertNull(o.paymentsLeft)
        assertNull(o.payoffDay)
        // Paying 50.00 against 107.40 of interest puts it all on interest and nothing on principal.
        val s = LoanEngine.suggest(5_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(5_000, s.interest)
        assertEquals(0, s.principal)
    }

    @Test
    fun principalIsNeverMoreThanWhatIsOwedAndTheSurplusIsShown() {
        val s = LoanEngine.suggest(20_000, nov1, 10_000, oct1, terms(), emptyList())
        assertEquals(50, s.interest)
        assertEquals(10_000, s.principal)
        assertEquals(9_950, s.leftover)
        assertEquals(20_000, s.principal + s.interest + s.escrow + s.charge + s.leftover)
    }

    @Test
    fun theLastPaymentClearsTheBalance() {
        val t = terms()
        assertEquals(30_150, LoanEngine.amountDue(t, emptyList(), 30_000, oct1, nov1))
        assertEquals(0, LoanEngine.amountDue(t, emptyList(), 0, oct1, nov1))
        val s = LoanEngine.suggest(30_150, nov1, 30_000, oct1, t, emptyList())
        assertEquals(30_000, s.principal)
        assertEquals(0, s.leftover)
    }

    @Test
    fun escrowRidesOnTopOfEachPaymentAndIsNeitherInterestNorPrincipal() {
        val t = terms(escrow = 20_000)
        assertEquals(78_000, t.scheduledTotal)
        val s = LoanEngine.suggest(78_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(20_000, s.escrow)
        assertEquals(10_740, s.interest)
        assertEquals(47_260, s.principal)
        assertEquals(0, s.extra)
        assertEquals(78_000, LoanEngine.amountDue(t, emptyList(), 2_148_000, oct1, nov1))
        val row = LoanEngine.project(t, emptyList(), cursor(2_148_000)).first()
        assertEquals(78_000, row.payment)
        assertEquals(20_000, row.escrow)
    }

    @Test
    fun anEarlyRepaymentChargeIsTakenFromTheExtraOnly() {
        // 1% charge, 1,000.00 paid against a 580.00 payment: 420.00 extra, so 4.20 charge.
        val t = terms(earlyCharge = 10_000)
        val s = LoanEngine.suggest(100_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(42_000, s.extra)
        assertEquals(420, s.charge)
        assertEquals(10_740, s.interest)
        assertEquals(88_840, s.principal)
        val plain = LoanEngine.suggest(58_000, nov1, 2_148_000, oct1, t, emptyList())
        assertEquals(0, plain.charge)
    }

    @Test
    fun zeroPercentLoansSplitTheTermEvenly() {
        assertEquals(50_000, LoanEngine.emi(3_000_000, 0, 60))
        assertEquals(0, LoanEngine.monthlyInterest(3_000_000, 0))
    }

    @Test
    fun theLastDayIsThePreviousPaymentThenTheStartThenAMonthBack() {
        val t = terms()
        assertEquals(oct1, LoanEngine.lastDay(t, emptyList()))
        assertEquals(day(2026, 9, 15), LoanEngine.lastDay(t.copy(startDay = day(2026, 9, 15)), emptyList()))
        val paid = listOf(
            LoanPayment(1, 1, day(2026, 9, 1), 58_000, 1, 2, null, 0, 0, false, 0, true),
            LoanPayment(2, 1, day(2026, 10, 2), 58_000, 3, 4, null, 0, 0, false, 0, true),
        )
        assertEquals(day(2026, 10, 2), LoanEngine.lastDay(t, paid))
        assertEquals(day(2026, 9, 1), LoanEngine.lastDay(t, paid, before = day(2026, 10, 1)))
        assertEquals(day(2026, 9, 1), LoanEngine.lastDay(t, paid, except = 2))
    }

    @Test
    fun theTermsAreCheckedBeforeSaving() {
        assertNull(terms().problem())
        assertNotNull(terms(rate = 2_000_000).problem())
        assertNotNull(terms(type = InterestType.FLAT).problem())
        assertNotNull(terms().copy(dueDay = 32).problem())
        assertNotNull(terms().copy(termMonths = 601).problem())
        assertNull(terms(type = InterestType.INTEREST_ONLY, payment = 0).problem())
    }
}
