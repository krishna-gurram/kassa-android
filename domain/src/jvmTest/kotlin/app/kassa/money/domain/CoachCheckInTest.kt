package app.kassa.money.domain

import app.kassa.money.domain.WeeklyCheckIn.Kind
import app.kassa.money.domain.WeeklyCheckIn.Reference
import app.kassa.money.domain.WeeklyCheckIn.WatchKind
import app.kassa.money.domain.WeeklyCheckIn.WinKind
import kotlinx.datetime.DayOfWeek
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The weekly check-in. Today is Thursday 12 November 2026, so a Monday week started on the 9th and the four earlier weeks started on
 * 2 November, 26, 19 and 12 October. Every earlier week holds groceries of 50.00 on its Monday and dining of 100.00 on its Friday;
 * this week so far holds groceries of 30.00 (Monday) and dining of 120.00 (Wednesday).
 */
class CoachCheckInTest {

    private fun fixture(): CoachFx {
        val fx = CoachFx()
        // Earlier October: groceries 200.00, so groceries is not on pace to pass it; dining 300.00 (the Fridays 16, 23, 30).
        fx.spend("2026-10-01", 20_000, 10)
        for (monday in listOf("2026-10-12", "2026-10-19", "2026-10-26", "2026-11-02")) fx.spend(monday, 5_000, 10)
        for (friday in listOf("2026-10-16", "2026-10-23", "2026-10-30", "2026-11-06")) fx.spend(friday, 10_000, 11)
        fx.spend("2026-11-09", 3_000, 10)
        fx.spend("2026-11-11", 12_000, 11)
        return fx
    }

    @Test
    fun theWeekAgainstAUsualWeek() {
        val fx = fixture()
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(Kind.READY, r.kind)
        assertEquals(fx.day("2026-11-09"), r.weekStartDay)
        assertEquals(15_000L, r.spent)
        assertEquals(4, r.weeksCompared)
        assertTrue(r.hasUsual)
        assertEquals(15_000L, r.usualWeek)
        // Monday to Thursday of an earlier week holds the groceries only.
        assertEquals(5_000L, r.usualByNow)
    }

    @Test
    fun oneWinOneWatchOutAndOneDecision() {
        val fx = fixture()
        val r = WeeklyCheckIn.compute(fx.input())
        // Dining is on pace: 22,000 so far x 30 / 12 = 55,000 against 30,000 last month.
        val watch = assertNotNull(r.watch)
        assertEquals(WatchKind.CATEGORY_PACE, watch.kind)
        assertEquals(11L, watch.categoryId)
        assertEquals(22_000L, watch.amount)
        assertEquals(55_000L, watch.projection)
        assertEquals(Reference.LAST_MONTH, watch.referenceKind)
        assertEquals(30_000L, watch.reference)
        assertEquals(25_000L, watch.overBy)
        assertEquals(12, watch.dayOfPeriod)
        assertEquals(30, watch.daysInPeriod)
        assertEquals(-1, watch.usedPercent)
        // Groceries: 30.00 this week against a usual 50.00.
        val win = assertNotNull(r.win)
        assertEquals(WinKind.UNDER_USUAL, win.kind)
        assertEquals(10L, win.categoryId)
        assertEquals(3_000L, win.spent)
        assertEquals(5_000L, win.reference)
        assertEquals(2_000L, win.savedBy)
        // Dining took 300.00 in October (the one full month) and has no budget: suggest that as a MONTHLY budget. Nothing is written by asking.
        val decision = assertNotNull(r.decision)
        assertEquals(11L, decision.categoryId)
        assertEquals(30_000L, decision.monthlyBudget)
        assertEquals(1, decision.monthsUsed)
        assertEquals(22_000L, decision.spentThisMonth)
        assertNull(decision.currentBudget)
    }

    private fun diningLedger(): CoachFx {
        val fx = CoachFx()
        fx.spend("2026-08-01", 1_000, 10)
        fx.spend("2026-08-15", 12_345, 11)
        fx.spend("2026-09-15", 20_000, 11)
        fx.spend("2026-10-15", 30_000, 11)
        fx.spend("2026-11-05", 50_000, 11)
        return fx
    }

    @Test
    fun theBudgetIsTheAverageOfThePreviousThreeFullMonthsRoundedDownToAWholeUnit() {
        val d = assertNotNull(WeeklyCheckIn.compute(diningLedger().input()).decision)
        // (123.45 + 200.00 + 300.00) / 3 = 207.816...: 20,781 cents -> 20,700.
        assertEquals(20_700L, d.monthlyBudget)
        assertEquals(3, d.monthsUsed)
        assertNull(d.currentBudget)
    }

    @Test
    fun oneFullMonthOfHistoryIsEnough() {
        val fx = CoachFx()
        fx.spend("2026-10-01", 1_000, 10)
        fx.spend("2026-10-20", 45_050, 11)
        fx.spend("2026-11-05", 50_000, 11)
        val d = assertNotNull(WeeklyCheckIn.compute(fx.input()).decision)
        assertEquals(45_000L, d.monthlyBudget)
        assertEquals(1, d.monthsUsed)
    }

    @Test
    fun noFullMonthOfHistoryIsNoDecision() {
        // The first entry is on 3 October, so October is not a whole month of the person's life.
        val fx = CoachFx()
        fx.spend("2026-10-03", 1_000, 10)
        fx.spend("2026-10-20", 45_050, 11)
        fx.spend("2026-11-05", 50_000, 11)
        val r = WeeklyCheckIn.compute(fx.input())
        assertNotNull(r.watch)
        assertNull(r.decision)
    }

    @Test
    fun aBudgetBelowTheAverageThatIsPassedGetsADecision() {
        val fx = diningLedger()
        // Budget 400.00 is passed by 500.00 spent so far; the average month is 207.00, which is below the budget: no decision.
        fx.budgets.add(Budget(11, 0, 40_000))
        assertNull(WeeklyCheckIn.compute(fx.input()).decision)
        // A budget of 100.00 is below the average and already passed: suggest the average.
        val low = diningLedger()
        low.budgets.add(Budget(11, 0, 10_000))
        val d = assertNotNull(WeeklyCheckIn.compute(low.input()).decision)
        assertEquals(20_700L, d.monthlyBudget)
        assertEquals(10_000L, d.currentBudget)
        assertEquals(50_000L, d.spentThisMonth)
    }

    @Test
    fun aBudgetAtOrAboveTheAverageGetsNoDecision() {
        val fx = diningLedger()
        fx.budgets.add(Budget(11, 0, 20_700))
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(11L, r.watch?.categoryId)
        assertNull(r.decision)
    }

    @Test
    fun aBudgetBelowTheAverageButNotPassedGetsNoDecision() {
        // Average month 433.00; budget 200.00; 50.00 so far is on pace for 125.00 (under the budget) but past last month's 100.00.
        val fx = CoachFx()
        fx.spend("2026-08-01", 1_000, 10)
        fx.spend("2026-08-15", 60_000, 11)
        fx.spend("2026-09-15", 60_000, 11)
        fx.spend("2026-10-15", 10_000, 11)
        fx.spend("2026-11-05", 5_000, 11)
        fx.budgets.add(Budget(11, 0, 20_000))
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(Reference.LAST_MONTH, r.watch?.referenceKind)
        assertNull(r.decision)
    }

    @Test
    fun aWinCanBeUnderTheBudgetPace() {
        val fx = fixture()
        // Groceries budget 600.00: by day 12 of 30 the pace is 240.00; 80.00 is spent, 160.00 under it.
        fx.budgets.add(Budget(10, 0, 60_000))
        val r = WeeklyCheckIn.compute(fx.input())
        val win = assertNotNull(r.win)
        assertEquals(WinKind.UNDER_BUDGET, win.kind)
        assertEquals(10L, win.categoryId)
        assertEquals(8_000L, win.spent)
        assertEquals(24_000L, win.reference)
        assertEquals(16_000L, win.savedBy)
    }

    @Test
    fun aCategoryTheMonthFlagsIsNeverTheWin() {
        val fx = CoachFx()
        fx.spend("2026-10-03", 5_000, 10)
        for (monday in listOf("2026-10-12", "2026-10-19", "2026-10-26", "2026-11-02")) fx.spend(monday, 50_000, 10)
        fx.spend("2026-11-09", 3_000, 10)
        // Under its usual week (30.00 against 500.00) but 230.00 ... 530.00 so far projects past last month's 1,550.00: flagged, so no win.
        fx.spend("2026-11-05", 300_000, 10)
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(10L, r.watch?.categoryId)
        assertNull(r.win)
    }

    @Test
    fun aRisingSubscriptionIsTheWatchOutWhenNothingIsOnPace() {
        val fx = CoachFx()
        fx.spend("2026-08-05", 1_599, 14, "Streaming")
        fx.spend("2026-09-05", 1_599, 14, "Streaming")
        fx.spend("2026-10-05", 1_599, 14, "Streaming")
        fx.spend("2026-11-05", 1_999, 14, "Streaming")
        // Last month the category took 200.00 more, so November's pace is not past it.
        fx.spend("2026-10-20", 20_000, 14)
        val r = WeeklyCheckIn.compute(fx.input())
        val w = assertNotNull(r.watch)
        assertEquals(WatchKind.SUBSCRIPTION_RISE, w.kind)
        assertEquals("Streaming", w.name)
        assertEquals(1_999L, w.amount)
        assertEquals(1_599L, w.reference)
        assertEquals(400L, w.overBy)
        assertNull(r.decision)
    }

    @Test
    fun nothingToWatchIsNothing() {
        val fx = CoachFx()
        fx.spend("2026-10-01", 10_000, 10)
        fx.spend("2026-11-10", 1_000, 10)
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(Kind.READY, r.kind)
        assertNull(r.watch)
        assertNull(r.decision)
    }

    @Test
    fun aBrandNewLedgerHasNotEnoughData() {
        val fx = CoachFx()
        fx.spend("2026-11-06", 10_000, 10)
        // 6 to 12 November is 7 days.
        assertEquals(Kind.NOT_ENOUGH_DATA, WeeklyCheckIn.compute(fx.input()).kind)
        assertEquals(Kind.NOT_ENOUGH_DATA, WeeklyCheckIn.compute(CoachFx().input()).kind)
    }

    @Test
    fun fourteenDaysIsEnoughAndThirteenIsNot() {
        val ok = CoachFx()
        ok.spend("2026-10-30", 10_000, 10)
        val r = WeeklyCheckIn.compute(ok.input())
        assertEquals(Kind.READY, r.kind)
        // The week of 2 November is the one whole week before this one that starts after the first entry; it was quiet.
        assertEquals(1, r.weeksCompared)
        assertEquals(0L, r.usualWeek)
        val short = CoachFx()
        short.spend("2026-10-31", 10_000, 10)
        assertEquals(Kind.NOT_ENOUGH_DATA, WeeklyCheckIn.compute(short.input()).kind)
    }

    @Test
    fun aQuietUsualWeekGivesNoUsualWin() {
        val fx = CoachFx()
        fx.spend("2026-10-30", 10_000, 10)
        fx.spend("2026-11-10", 1_000, 10)
        val r = WeeklyCheckIn.compute(fx.input())
        assertNull(r.win)
        assertEquals(1_000L, r.spent)
    }

    @Test
    fun theWeekStartsOnThePhonesFirstWeekday() {
        val fx = fixture()
        fx.firstDay = DayOfWeek.SUNDAY
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(fx.day("2026-11-08"), r.weekStartDay)
        assertEquals(15_000L, r.spent)
        val sat = fixture()
        sat.firstDay = DayOfWeek.THURSDAY
        // A week that starts today holds today only.
        assertEquals(sat.day("2026-11-12"), WeeklyCheckIn.compute(sat.input()).weekStartDay)
        assertEquals(0L, WeeklyCheckIn.compute(sat.input()).spent)
    }

    @Test
    fun aWeekThatCrossesTheMonthBoundary() {
        // Monday 30 November to Wednesday 2 December: the week reaches back into November while the budget month is December.
        val fx = CoachFx("2026-12-02")
        fx.spend("2026-11-03", 10_000, 10)
        fx.spend("2026-11-30", 7_000, 10)
        fx.spend("2026-12-01", 3_000, 10)
        fx.spend("2026-12-03", 99_000, 10)
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(fx.day("2026-11-30"), r.weekStartDay)
        assertEquals(10_000L, r.spent)
    }

    @Test
    fun knownPaymentsAreNotPartOfTheWeek() {
        val fx = fixture()
        // Rent repeats monthly; this week's is not day to day spending.
        fx.monthly(listOf("2026-08", "2026-09", "2026-10", "2026-11"), 10, 150_000, 12, "Rent")
        val r = WeeklyCheckIn.compute(fx.input())
        assertEquals(15_000L, r.spent)
        assertEquals(15_000L, r.usualWeek)
    }

    @Test
    fun incomeTransfersAndCorrectionsAreNotSpent() {
        val fx = fixture()
        fx.earn("2026-11-10", 400_000)
        fx.other(TxKind.TRANSFER, "2026-11-10", 400_000, to = 2)
        fx.other(TxKind.ADJUST_OUT, "2026-11-10", 400_000)
        assertEquals(15_000L, WeeklyCheckIn.compute(fx.input()).spent)
    }

    @Test
    fun theSameInputGivesTheSameAnswer() {
        val fx = fixture()
        assertEquals(WeeklyCheckIn.compute(fx.input()), WeeklyCheckIn.compute(fx.input()))
    }

    @Test
    fun aBudgetedCategoryOverItsBudgetIsTheWatchOutWithItsUsedShare() {
        val fx = fixture()
        // Dining 220.00 against 200.00 for the month: over, 110% used (shown as 100).
        fx.budgets.add(Budget(11, 0, 20_000))
        val w = assertNotNull(WeeklyCheckIn.compute(fx.input()).watch)
        assertEquals(Reference.BUDGET, w.referenceKind)
        assertEquals(20_000L, w.reference)
        assertEquals(100, w.usedPercent)
        assertEquals(35_000L, w.overBy)
    }

    @Test
    fun aBudgetOnPaceShowsHowMuchIsUsed() {
        val fx = fixture()
        // Dining budget 250.00: 220.00 so far is 88% used, and on pace for 550.00.
        fx.budgets.add(Budget(11, 0, 25_000))
        val w = assertNotNull(WeeklyCheckIn.compute(fx.input()).watch)
        assertEquals(88, w.usedPercent)
        assertEquals(Reference.BUDGET, w.referenceKind)
    }
}
