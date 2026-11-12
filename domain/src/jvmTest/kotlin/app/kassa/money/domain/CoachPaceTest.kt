package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spending pace. Today is Thursday 12 November 2026: day 12 of 30 in November, October had 31 days. */
class CoachPaceTest {

    private fun row(r: SpendingPace.Result, id: Long) = r.rows.first { it.categoryId == id }

    /** Groceries 400.00 so far (October: 300.00 by the 12th, 900.00 in all) and Dining 200.00 (October 800.00). */
    private fun fixture(): CoachFx {
        val fx = CoachFx()
        fx.spend("2026-10-03", 30_000, 10)
        fx.spend("2026-10-20", 60_000, 10)
        fx.spend("2026-10-05", 40_000, 11)
        fx.spend("2026-10-25", 40_000, 13)
        fx.spend("2026-11-04", 40_000, 10)
        fx.spend("2026-11-06", 20_000, 13)
        return fx
    }

    @Test
    fun projectsTheMonthAndComparesWithTheSameDayLastMonth() {
        val r = SpendingPace.compute(fixture().input())
        assertEquals(12, r.dayOfPeriod)
        assertEquals(30, r.daysInPeriod)
        assertFalse(r.early)
        val g = row(r, 10)
        assertEquals(40_000L, g.spent)
        assertEquals(30_000L, g.lastSoFar)
        assertEquals(90_000L, g.lastTotal)
        // 40,000 / 12 x 30 = 100,000, above last month's 90,000.
        assertEquals(100_000L, g.projection)
        assertTrue(g.aboveLast)
        assertEquals(10_000L, g.overBy)
        assertNull(g.budget)
    }

    @Test
    fun aSubcategoryRollsUpToItsTopLevelCategory() {
        val r = SpendingPace.compute(fixture().input())
        val d = row(r, 11)
        assertEquals(20_000L, d.spent)
        assertEquals(80_000L, d.lastTotal)
        // October Dining by the 12th: only the 5 October 400.00 (the Restaurants entry was on the 25th).
        assertEquals(40_000L, d.lastSoFar)
        assertEquals(50_000L, d.projection)
        assertFalse(d.aboveLast)
        assertFalse(d.flagged)
        assertEquals(setOf(10L, 11L), r.rows.map { it.categoryId }.toSet())
    }

    @Test
    fun flaggedRowsComeFirstAndTheFurthestPastLeads() {
        val r = SpendingPace.compute(fixture().input())
        assertEquals(listOf(10L, 11L), r.rows.map { it.categoryId })
        assertEquals(listOf(10L), r.flagged.map { it.categoryId })
    }

    @Test
    fun aBudgetIsMeasuredAgainstTheSpendingAndTheProjection() {
        val fx = fixture()
        fx.budgets.add(Budget(10, 0, 95_000))
        // A one-month override wins: November's Dining budget is 150.00, already past it at 200.00.
        fx.budgets.add(Budget(11, 0, 500_000))
        fx.budgets.add(Budget(11, 202611, 15_000))
        val r = SpendingPace.compute(fx.input())
        val g = row(r, 10)
        assertEquals(95_000L, g.budget)
        assertFalse(g.overBudget)
        assertTrue(g.paceOverBudget)
        // Projection 100,000 is past the budget by 5,000 and past last month by 10,000: the further gap.
        assertEquals(10_000L, g.overBy)
        val d = row(r, 11)
        assertEquals(15_000L, d.budget)
        assertTrue(d.overBudget)
        assertFalse(d.paceOverBudget)
        assertTrue(d.flagged)
        // 50,000 projected against 15,000 budget and 80,000 last month: 35,000 past the budget.
        assertEquals(35_000L, d.overBy)
        assertEquals(listOf(11L, 10L), r.rows.map { it.categoryId })
    }

    @Test
    fun aZeroBudgetIsNoBudget() {
        val fx = fixture()
        fx.budgets.add(Budget(10, 0, 0))
        assertNull(row(SpendingPace.compute(fx.input()), 10).budget)
    }

    @Test
    fun earlyInTheMonthOnlyAnOverBudgetCategoryIsFlagged() {
        val fx = CoachFx("2026-11-04")
        fx.spend("2026-10-10", 10_000, 10)
        fx.spend("2026-11-01", 90_000, 10)
        fx.spend("2026-11-02", 5_000, 11)
        fx.budgets.add(Budget(11, 0, 4_000))
        val r = SpendingPace.compute(fx.input())
        assertTrue(r.early)
        assertEquals(4, r.dayOfPeriod)
        // Groceries projects to 675,000 from one big bill on day 1, but it is too early to say so.
        val g = row(r, 10)
        assertFalse(g.aboveLast)
        assertFalse(g.flagged)
        assertTrue(row(r, 11).overBudget)
        assertEquals(listOf(11L), r.flagged.map { it.categoryId })
    }

    @Test
    fun firstFlagAppearsOnTheSeventhDay() {
        val fx = CoachFx("2026-11-07")
        fx.spend("2026-10-10", 10_000, 10)
        fx.spend("2026-11-01", 90_000, 10)
        val r = SpendingPace.compute(fx.input())
        assertFalse(r.early)
        assertTrue(row(r, 10).aboveLast)
    }

    @Test
    fun theProjectionRoundsHalfUp() {
        val fx = CoachFx()
        fx.spend("2026-11-03", 5, 10)
        // 5 x 30 / 12 = 12.5 -> 13.
        assertEquals(13L, row(SpendingPace.compute(fx.input()), 10).projection)
        val fy = CoachFx()
        fy.spend("2026-11-03", 7, 10)
        // 7 x 30 / 12 = 17.5 -> 18.
        assertEquals(18L, row(SpendingPace.compute(fy.input()), 10).projection)
        val fz = CoachFx()
        fz.spend("2026-11-03", 4, 10)
        // 4 x 30 / 12 = 10 exactly.
        assertEquals(10L, row(SpendingPace.compute(fz.input()), 10).projection)
    }

    @Test
    fun aLeapFebruaryHasTwentyNineDays() {
        val fx = CoachFx("2028-02-10")
        fx.spend("2028-01-05", 20_000, 10)
        fx.spend("2028-02-02", 10_000, 10)
        val r = SpendingPace.compute(fx.input())
        assertEquals(29, r.daysInPeriod)
        assertEquals(10, r.dayOfPeriod)
        // 10,000 / 10 x 29.
        assertEquals(29_000L, row(r, 10).projection)
        // January had 31 days; the same 10 days of it hold the 5 January entry.
        assertEquals(20_000L, row(r, 10).lastSoFar)
        assertTrue(row(r, 10).aboveLast)
    }

    @Test
    fun anOrdinaryFebruaryHasTwentyEightDays() {
        val fx = CoachFx("2027-02-14")
        fx.spend("2027-02-02", 14_000, 10)
        val r = SpendingPace.compute(fx.input())
        assertEquals(28, r.daysInPeriod)
        assertEquals(28_000L, row(r, 10).projection)
    }

    @Test
    fun aShorterLastMonthIsComparedWholeLateInTheMonth() {
        // 31 March: day 31 of 31; February 2026 had 28 days, so "the same day" is all of it.
        val fx = CoachFx("2026-03-31")
        fx.spend("2026-02-10", 10_000, 10)
        fx.spend("2026-02-28", 5_000, 10)
        fx.spend("2026-03-31", 50_000, 10)
        val r = SpendingPace.compute(fx.input())
        assertEquals(31, r.dayOfPeriod)
        assertEquals(15_000L, row(r, 10).lastSoFar)
        assertEquals(15_000L, row(r, 10).lastTotal)
        assertEquals(50_000L, row(r, 10).projection)
    }

    @Test
    fun aBudgetMonthThatStartsOnTheFifteenth() {
        val fx = CoachFx("2026-11-25")
        fx.monthStart = 15
        // The budget month 15 Nov to 14 Dec has 30 days; today is its 11th. Last one: 15 Oct to 14 Nov, 31 days.
        fx.spend("2026-10-20", 10_000, 10)
        fx.spend("2026-11-14", 99_000, 10)
        fx.spend("2026-11-16", 11_000, 10)
        val r = SpendingPace.compute(fx.input())
        assertEquals(11, r.dayOfPeriod)
        assertEquals(30, r.daysInPeriod)
        val g = row(r, 10)
        // The 14 November entry belongs to the budget month before.
        assertEquals(11_000L, g.spent)
        assertEquals(109_000L, g.lastTotal)
        // The same 11 days of last month: 15 to 25 October.
        assertEquals(10_000L, g.lastSoFar)
        // 11,000 x 30 / 11.
        assertEquals(30_000L, g.projection)
    }

    @Test
    fun budgetsAreReadForTheBudgetMonthsOwnName() {
        val fx = CoachFx("2026-12-02")
        fx.monthStart = 15
        // 2 December is in the budget month that starts 15 November, named November.
        fx.spend("2026-11-20", 10_000, 10)
        fx.budgets.add(Budget(10, 202611, 7_000))
        fx.budgets.add(Budget(10, 202612, 1))
        assertEquals(7_000L, row(SpendingPace.compute(fx.input()), 10).budget)
    }

    @Test
    fun uncategorisedIncomeTransfersAndOtherKindsAreLeftOut() {
        val fx = CoachFx()
        fx.spend("2026-11-03", 10_000, null)
        fx.earn("2026-11-03", 90_000)
        fx.other(TxKind.TRANSFER, "2026-11-03", 20_000, to = 2)
        fx.other(TxKind.ADJUST_OUT, "2026-11-03", 30_000)
        fx.other(TxKind.PERSON_OUT, "2026-11-03", 40_000, person = 5)
        // An expense in an income category is not a spending category either.
        fx.spend("2026-11-03", 50_000, 20)
        assertTrue(SpendingPace.compute(fx.input()).rows.isEmpty())
    }

    @Test
    fun aSplitExpenseCountsOnlyYourShare() {
        val fx = CoachFx()
        fx.spend("2026-11-03", 80_000, 10, share = 20_000)
        assertEquals(20_000L, row(SpendingPace.compute(fx.input()), 10).spent)
    }

    @Test
    fun entriesAfterTodayAreNotSpentYet() {
        val fx = CoachFx()
        fx.spend("2026-11-13", 80_000, 10)
        assertTrue(SpendingPace.compute(fx.input()).rows.isEmpty())
    }

    @Test
    fun aRentPaidOnTheFirstIsNotARateOfSpending() {
        val fx = CoachFx()
        fx.monthly(listOf("2026-08", "2026-09", "2026-10", "2026-11"), 1, 150_000, 12, "Rent")
        val r = SpendingPace.compute(fx.input())
        val rent = row(r, 12)
        assertEquals(150_000L, rent.spent)
        // Not 150,000 / 12 x 30: the rent already went out once this month.
        assertEquals(150_000L, rent.projection)
        assertFalse(rent.aboveLast)
        assertTrue(r.flagged.isEmpty())
    }

    @Test
    fun knownPaymentsAreAddedAsTheyAreAndTheRestIsProjected() {
        val fx = CoachFx()
        fx.monthly(listOf("2026-08", "2026-09", "2026-10", "2026-11"), 1, 150_000, 12, "Rent")
        // The same category also has day to day spending: 120.00 so far -> 120.00 / 12 x 30 = 300.00 on top of the 1,500.00 rent.
        fx.spend("2026-11-06", 12_000, 12, "Parking")
        assertEquals(150_000L + 30_000L, row(SpendingPace.compute(fx.input()), 12).projection)
    }

    @Test
    fun anEmptyLedgerHasNoRows() {
        val r = SpendingPace.compute(CoachFx().input())
        assertTrue(r.rows.isEmpty())
        assertEquals(12, r.dayOfPeriod)
    }

    @Test
    fun aCountedLoanPrincipalIsPaceSpending() {
        val fx = CoachFx()
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        val principal = fx.other(TxKind.TRANSFER, "2026-11-05", 25_000, account = 1, to = 4)
        fx.loanTerms[4] = LoanTerms(
            accountId = 4, type = InterestType.REDUCING, rate = 0, payment = 25_000, nextPaymentDay = fx.day("2026-12-05"), dueDay = 5,
            countAllAsSpending = true, principalCategoryId = 12,
        )
        fx.loanPayments.add(LoanPayment(1, 4, fx.day("2026-11-05"), 25_000, principal, null, null, 0, 0, false, 0, true))
        assertEquals(25_000L, row(SpendingPace.compute(fx.input()), 12).spent)
        // Turned off, the transfer is not spending.
        fx.loanTerms[4] = fx.loanTerms.getValue(4).copy(countAllAsSpending = false)
        assertTrue(SpendingPace.compute(fx.input()).rows.isEmpty())
    }

    @Test
    fun rowsTieBreakByCategoryId() {
        val fx = CoachFx()
        fx.spend("2026-11-03", 10_000, 11)
        fx.spend("2026-11-03", 10_000, 10)
        assertEquals(listOf(10L, 11L), SpendingPace.compute(fx.input()).rows.map { it.categoryId })
    }
}
