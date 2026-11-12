package app.kassa.money.domain

import app.kassa.money.domain.SafeToSpend.Basis
import app.kassa.money.domain.SafeToSpend.Kind
import app.kassa.money.domain.SafeToSpend.PartKind
import app.kassa.money.domain.SafeToSpend.UntilKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Safe to spend. Expected figures are worked out by hand from the fixture, not with the rules: Aug to Oct 2026 hold 92 days and three
 * groceries of 100.00, so a usual day is 30,000 / 92 = 326.09 -> 326 cents; the salary repeats on the 28th, so from 12 November the money
 * has to last 16 days (to 28 November), which is 16 x 326 = 5,216.
 */
class CoachSafeToSpendTest {
    private val money = mapOf(1L to 500_000L, 2L to 300_000L)

    private fun lived(): CoachFx = CoachFx.lived().also { fx ->
        fx.bills.add(Recurring(id = 5, name = "Internet", categoryId = 14, accountId = 1, dayOfMonth = 20, fixedAmount = 5_000))
    }

    @Test
    fun moneyLeftUntilTheNextPayday() {
        val fx = lived()
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(Kind.SPENDABLE, r.kind)
        assertEquals(UntilKind.NEXT_INCOME, r.untilKind)
        assertEquals(fx.day("2026-11-28"), r.untilDay)
        assertEquals(16, r.daysLeft)
        assertEquals(800_000L, r.liquid)
        assertEquals(326L, r.usualDaily)
        assertEquals(Basis.HISTORY, r.basis)
        assertEquals(5_000L, r.amountOf(PartKind.BILLS))
        assertEquals(5_216L, r.amountOf(PartKind.USUAL_SPENDING))
        assertEquals(800_000L - 5_000L - 5_216L, r.amount)
        assertEquals(0L, r.shortBy)
        // 10,216 of 800,000 is 1.28%.
        assertEquals(1, r.usedPercent)
        val bill = assertNotNull(r.part(PartKind.BILLS))
        assertEquals("Internet", bill.name)
        assertEquals(5L, bill.refId)
        assertEquals(1, bill.count)
        // Groceries is the only day to day category, so the UI can say "usual groceries".
        assertEquals(10L, r.part(PartKind.USUAL_SPENDING)?.refId)
        assertEquals("Groceries", r.part(PartKind.USUAL_SPENDING)?.name)
    }

    @Test
    fun rentThatRepeatsIsNotUsualSpendingAndIncomeIsNotSpending() {
        // Rent 1,500 x 4 and the salary would be 4 x 150,000 and 3 x 400,000 if they leaked in; usual stays at groceries alone.
        val r = SafeToSpend.compute(lived().input(money))
        assertEquals(326L, r.usualDaily)
    }

    @Test
    fun withoutARepeatingIncomeTheMonthEndsTheWindow() {
        val fx = CoachFx()
        fx.monthly(CoachFx.AUG_TO_OCT, 10, 10_000, 10, "")
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(UntilKind.MONTH_END, r.untilKind)
        assertEquals(fx.day("2026-12-01"), r.untilDay)
        assertEquals(19, r.daysLeft)
        // The first entry is 10 August, so August counts from the 10th: 22 + 30 + 31 = 83 days, 30,000 / 83 = 361.45 -> 361.
        assertEquals(361L, r.usualDaily)
        assertEquals(19 * 361L, r.amountOf(PartKind.USUAL_SPENDING))
    }

    @Test
    fun onTheLastDayOfTheMonthOneDayIsLeft() {
        val fx = CoachFx("2026-11-30")
        fx.monthly(CoachFx.AUG_TO_OCT, 10, 10_000, 10, "")
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(1, r.daysLeft)
        assertEquals(fx.day("2026-12-01"), r.untilDay)
    }

    @Test
    fun aPaydayTodayMeansTheNextOne() {
        // The salary is expected today (28 Nov) but not entered yet: the window runs to the one after, 28 December.
        val fx = CoachFx("2026-11-28")
        fx.monthly(CoachFx.AUG_TO_OCT, 28, 400_000, 20, "Paycheck", income = true)
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(UntilKind.NEXT_INCOME, r.untilKind)
        assertEquals(fx.day("2026-12-28"), r.untilDay)
    }

    @Test
    fun aYearlyBonusIsNotAPayday() {
        val fx = CoachFx()
        fx.earn("2024-12-20", 500_000, "Bonus")
        fx.earn("2025-12-20", 500_000, "Bonus")
        fx.spend("2026-10-05", 1_000, 10)
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(UntilKind.MONTH_END, r.untilKind)
    }

    @Test
    fun billsExpectedChargesCardsAndLoansAreSubtractedBeforePayday() {
        val fx = lived()
        // A fixed monthly insurance of 80.00 on the 20th that is not a bill: expected on 20 November, before the payday.
        fx.monthly(CoachFx.AUG_TO_OCT, 20, 8_000, 12, "Insurance")
        // A card: statement on the 5th, due the 20th, 210.00 charged before the last statement date of 5 November.
        fx.account(3, "Travel card", AccountType.CREDIT_CARD, statementDay = 5, dueDay = 20)
        fx.spend("2026-10-20", 21_000, 11, "", account = 3)
        // A loan: payment 300.00 due 20 November, no interest.
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        fx.loanTerms[4] = LoanTerms(
            accountId = 4, type = InterestType.REDUCING, rate = 0, payment = 30_000, nextPaymentDay = fx.day("2026-11-20"), dueDay = 20,
        )
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(8_000L, r.amountOf(PartKind.RECURRING))
        assertEquals("Insurance", r.part(PartKind.RECURRING)?.name)
        assertEquals(21_000L, r.amountOf(PartKind.CARDS))
        assertEquals(3L, r.part(PartKind.CARDS)?.refId)
        assertEquals(30_000L, r.amountOf(PartKind.LOANS))
        assertEquals(4L, r.part(PartKind.LOANS)?.refId)
        assertEquals(5_000L, r.amountOf(PartKind.BILLS))
        // The insurance charges are known payments, so usual spending is groceries and the card's dining only.
        val usualTotal = 30_000L + 21_000L
        assertEquals(CoachMath.divHalfUp(usualTotal, 92), r.usualDaily)
    }

    @Test
    fun aPaymentDueAfterThePaydayIsNotSubtracted() {
        val fx = lived()
        fx.bills.clear()
        fx.bills.add(Recurring(id = 6, name = "Gym", categoryId = 14, accountId = 1, dayOfMonth = 28, fixedAmount = 4_000))
        val r = SafeToSpend.compute(fx.input(money))
        // Due on the payday itself: the money arrives that day, so it is not subtracted.
        assertNull(r.part(PartKind.BILLS))
    }

    @Test
    fun anOverdueBillStillComesOut() {
        val fx = lived()
        fx.bills.clear()
        fx.bills.add(Recurring(id = 7, name = "Water", categoryId = 14, accountId = 1, dayOfMonth = 3, fixedAmount = 2_500))
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(2_500L, r.amountOf(PartKind.BILLS))
    }

    @Test
    fun aPlanThatDoesNotFitSaysHowFarShortAndNeverShowsANegative() {
        val r = SafeToSpend.compute(lived().input(mapOf(1L to 3_000L, 2L to 0L)))
        assertEquals(Kind.SHORTFALL, r.kind)
        assertEquals(0L, r.amount)
        assertEquals(10_216L - 3_000L, r.shortBy)
        assertEquals(100, r.usedPercent)
    }

    @Test
    fun anOverdrawnAccountCountsAsLessMoney() {
        val r = SafeToSpend.compute(lived().input(mapOf(1L to -2_000L, 2L to 1_000L)))
        assertEquals(-1_000L, r.liquid)
        assertEquals(Kind.SHORTFALL, r.kind)
        assertEquals(1_000L + 10_216L, r.shortBy)
        assertEquals(100, r.usedPercent)
    }

    @Test
    fun onlyLiquidOpenAccountsAreMoney() {
        val fx = lived()
        fx.account(5, "Term deposit", AccountType.FIXED_DEPOSIT, opening = 9_000_000)
        fx.account(6, "Brokerage", AccountType.INVESTMENT, opening = 9_000_000)
        fx.account(7, "House", AccountType.OTHER_ASSET, opening = 9_000_000)
        fx.account(8, "Wallet", AccountType.PREPAID, opening = 1_000)
        fx.account(9, "Old", AccountType.CHECKING, opening = 7_000)
        fx.accounts[fx.accounts.size - 1] = fx.accounts.last().copy(closedDay = 1)
        fx.account(10, "Gone", AccountType.CASH, opening = 6_000)
        fx.accounts[fx.accounts.size - 1] = fx.accounts.last().copy(archived = true)
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(800_000L + 1_000L, r.liquid)
    }

    @Test
    fun noLiquidAccountSaysSo() {
        val fx = CoachFx()
        fx.accounts.clear()
        fx.account(6, "Brokerage", AccountType.INVESTMENT, opening = 100_000)
        val r = SafeToSpend.compute(fx.input())
        assertEquals(Kind.NO_ACCOUNTS, r.kind)
        assertEquals(0L, r.amount)
        assertEquals(0L, r.shortBy)
        assertTrue(r.parts.isEmpty())
    }

    @Test
    fun anEmptyLedgerIsAllMoneyAndNoUsualSpending() {
        val fx = CoachFx()
        fx.opening(1, 100_000)
        val r = SafeToSpend.compute(fx.input())
        assertEquals(Kind.SPENDABLE, r.kind)
        assertEquals(100_000L, r.amount)
        assertEquals(Basis.NONE, r.basis)
        assertEquals(0L, r.usualDaily)
        assertEquals(0, r.usedPercent)
        assertTrue(r.parts.isEmpty())
        assertEquals(UntilKind.MONTH_END, r.untilKind)
    }

    @Test
    fun aNewLedgerUsesThisMonthSoFarOnceItHasAWeek() {
        val fx = CoachFx()
        fx.spend("2026-11-03", 11_000, 10)
        fx.spend("2026-11-05", 22_000, 11)
        val r = SafeToSpend.compute(fx.input(money))
        // 3 to 11 November is 9 days: 33,000 / 9 = 3,666.67 -> 3,667.
        assertEquals(Basis.THIS_MONTH, r.basis)
        assertEquals(3_667L, r.usualDaily)
        // Dining took the most, so "usual dining" is what the UI can name.
        assertEquals(11L, r.part(PartKind.USUAL_SPENDING)?.refId)
    }

    @Test
    fun lessThanAWeekOfLifeIsNoBasis() {
        val fx = CoachFx()
        fx.spend("2026-11-08", 11_000, 10)
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(Basis.NONE, r.basis)
        assertEquals(0L, r.usualDaily)
        assertNull(r.part(PartKind.USUAL_SPENDING))
    }

    @Test
    fun aLedgerThatBeganPartwayThroughAMonthCountsFromItsFirstEntry() {
        val fx = CoachFx()
        // Began 21 October: 21 to 31 October is 11 days, below the 14 that make a usual day, so this month is used instead.
        fx.spend("2026-10-21", 11_000, 10)
        fx.spend("2026-11-02", 9_000, 10)
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(Basis.THIS_MONTH, r.basis)
        // 1 to 11 November is 11 days: 9,000 / 11 = 818.18 -> 818.
        assertEquals(818L, r.usualDaily)
        // Began 8 October: 24 days of October count.
        val fy = CoachFx()
        fy.spend("2026-10-08", 24_000, 10)
        val r2 = SafeToSpend.compute(fy.input(money))
        assertEquals(Basis.HISTORY, r2.basis)
        assertEquals(1_000L, r2.usualDaily)
    }

    @Test
    fun correctionsTransfersAndPersonPaymentsAreNotSpending() {
        val fx = CoachFx.lived()
        fx.other(TxKind.ADJUST_OUT, "2026-09-15", 50_000)
        fx.other(TxKind.TRANSFER, "2026-09-16", 40_000, account = 1, to = 2)
        fx.other(TxKind.PERSON_OUT, "2026-09-17", 30_000, person = 77)
        fx.other(TxKind.BUY, "2026-09-18", 20_000)
        assertEquals(326L, SafeToSpend.compute(fx.input(money)).usualDaily)
    }

    @Test
    fun aSplitExpenseCountsOnlyYourShare() {
        val fx = CoachFx()
        fx.spend("2026-10-10", 90_000, 10, share = 30_000)
        fx.spend("2026-09-10", 30_000, 10)
        // 60,000 over 61 days (Sep + Oct; no August entries and the first entry is 10 September, so September counts from the 10th: 21 + 31 = 52 days).
        val r = SafeToSpend.compute(fx.input(money))
        assertEquals(CoachMath.divHalfUp(60_000, 52), r.usualDaily)
    }

    @Test
    fun aCountedLoanPrincipalIsNotUsualSpendingBecauseTheLoanPaymentIsSubtracted() {
        val fx = CoachFx.lived()
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        val principal = fx.other(TxKind.TRANSFER, "2026-10-20", 25_000, account = 1, to = 4)
        val interest = fx.spend("2026-10-20", 5_000, 14, account = 1)
        fx.loanTerms[4] = LoanTerms(
            accountId = 4, type = InterestType.REDUCING, rate = 0, payment = 30_000, nextPaymentDay = fx.day("2026-12-20"), dueDay = 20,
            countAllAsSpending = true, principalCategoryId = 10,
        )
        fx.loanPayments.add(LoanPayment(1, 4, fx.day("2026-10-20"), 30_000, principal, interest, null, 0, 0, false, 0, true))
        // Spending counts the principal (see EmergencyFund), usual daily spending does not: both entries belong to the payment.
        assertEquals(326L, SafeToSpend.compute(fx.input(money)).usualDaily)
    }

    @Test
    fun theSameInputGivesTheSameAnswer() {
        val fx = lived()
        assertEquals(SafeToSpend.compute(fx.input(money)), SafeToSpend.compute(fx.input(money)))
    }

    @Test
    fun aShorterFebruaryBudgetMonthEndsOnTime() {
        val fx = CoachFx("2028-02-27")
        fx.spend("2027-11-10", 10_000, 10)
        fx.spend("2027-12-10", 10_000, 10)
        fx.spend("2028-01-10", 10_000, 10)
        val r = SafeToSpend.compute(fx.input(money))
        // 29 days in February 2028: the month ends on the 29th, so the window runs to 1 March, 3 days from the 27th.
        assertEquals(fx.day("2028-03-01"), r.untilDay)
        assertEquals(3, r.daysLeft)
    }

    @Test
    fun aMonthThatStartsOnTheFifteenth() {
        val fx = CoachFx("2026-11-20")
        fx.monthStart = 15
        val r = SafeToSpend.compute(fx.input(money))
        // The budget month 15 Nov to 14 Dec: the window runs to 15 December.
        assertEquals(fx.day("2026-12-15"), r.untilDay)
    }
}
