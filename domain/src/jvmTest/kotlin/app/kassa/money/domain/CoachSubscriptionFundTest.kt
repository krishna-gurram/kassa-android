package app.kassa.money.domain

import app.kassa.money.domain.EmergencyFund.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoachSubscriptionTest {
    private val months = listOf("2026-08", "2026-09", "2026-10")

    private fun streaming(fx: CoachFx) {
        fx.spend("2026-08-05", 1_599, 14, "Streaming")
        fx.spend("2026-09-05", 1_599, 14, "Streaming")
        fx.spend("2026-10-05", 1_599, 14, "Streaming")
        fx.spend("2026-11-05", 1_999, 14, "Streaming")
    }

    @Test
    fun listsTheChargesThatRoseAndTheNewMonthlyTotal() {
        val fx = CoachFx()
        streaming(fx)
        fx.spend("2026-08-07", 299, 14, "Cloud")
        fx.spend("2026-09-07", 299, 14, "Cloud")
        fx.spend("2026-10-07", 299, 14, "Cloud")
        fx.spend("2026-11-07", 399, 14, "Cloud")
        fx.monthly(months + "2026-11", 9, 999, 14, "Music")
        val r = SubscriptionChange.compute(fx.input())
        assertEquals(listOf("Streaming", "Cloud"), r.rows.map { it.name })
        assertEquals(400L, r.rows[0].change)
        assertEquals(1_599L, r.rows[0].previous)
        assertEquals(1_999L, r.rows[0].latest)
        assertEquals(fx.day("2026-11-05"), r.rows[0].lastDay)
        assertEquals(14L, r.rows[0].categoryId)
        assertEquals(1_999L + 399L + 999L, r.monthlyTotal)
        assertEquals(500L, r.increase)
        assertEquals(3, r.count)
    }

    @Test
    fun aPriceThatFellIsNotListedButIsInTheTotal() {
        val fx = CoachFx()
        fx.spend("2026-08-05", 1_999, 14, "Streaming")
        fx.spend("2026-09-05", 1_999, 14, "Streaming")
        fx.spend("2026-10-05", 1_999, 14, "Streaming")
        fx.spend("2026-11-05", 1_599, 14, "Streaming")
        val r = SubscriptionChange.compute(fx.input())
        assertTrue(r.rows.isEmpty())
        assertEquals(1_599L, r.monthlyTotal)
        assertEquals(0L, r.increase)
        assertEquals(1, r.count)
    }

    @Test
    fun aChargeThatVariesIsNotASubscription() {
        val fx = CoachFx()
        fx.spend("2026-08-05", 5_000, 14, "Electric")
        fx.spend("2026-09-05", 9_000, 14, "Electric")
        fx.spend("2026-10-05", 6_000, 14, "Electric")
        fx.spend("2026-11-05", 12_000, 14, "Electric")
        val r = SubscriptionChange.compute(fx.input())
        assertTrue(r.rows.isEmpty())
        assertEquals(0, r.count)
    }

    @Test
    fun aChargeThatStoppedIsNotCounted() {
        val fx = CoachFx()
        fx.spend("2026-03-05", 1_000, 14, "Old")
        fx.spend("2026-04-05", 1_000, 14, "Old")
        fx.spend("2026-05-05", 1_000, 14, "Old")
        fx.spend("2026-06-05", 2_000, 14, "Old")
        val r = SubscriptionChange.compute(fx.input())
        assertEquals(0, r.count)
    }

    @Test
    fun weeklyAndYearlyChargesAreNotMonthly() {
        val fx = CoachFx()
        for (d in listOf("2026-10-15", "2026-10-22", "2026-10-29", "2026-11-05")) fx.spend(d, 1_000, 14, "Paper")
        fx.spend("2024-11-05", 5_000, 14, "Annual")
        fx.spend("2025-11-05", 5_000, 14, "Annual")
        fx.spend("2026-11-05", 9_000, 14, "Annual")
        val r = SubscriptionChange.compute(fx.input())
        assertEquals(0, r.count)
    }

    @Test
    fun aLoansOwnPaymentsAreNotSubscriptions() {
        val fx = CoachFx()
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        val note = LoanNotes.interest("Car loan", "interest")
        fx.spend("2026-08-20", 5_000, 14, note)
        fx.spend("2026-09-20", 5_000, 14, note)
        fx.spend("2026-10-20", 5_000, 14, note)
        fx.spend("2026-11-05", 7_000, 14, note)
        val r = SubscriptionChange.compute(fx.input())
        assertEquals(0, r.count)
    }

    @Test
    fun incomeThatRepeatsIsNotASubscription() {
        val fx = CoachFx.lived()
        assertEquals(1, SubscriptionChange.compute(fx.input()).count)
        assertEquals(150_000L, SubscriptionChange.compute(fx.input()).monthlyTotal)
    }

    @Test
    fun nothingRepeatingIsAnEmptyResult() {
        val r = SubscriptionChange.compute(CoachFx().input())
        assertTrue(r.rows.isEmpty())
        assertEquals(0L, r.monthlyTotal)
    }

    @Test
    fun tiesBreakByKey() {
        val fx = CoachFx()
        for (name in listOf("Zed", "Abe")) {
            fx.spend("2026-08-05", 1_000, 14, name)
            fx.spend("2026-09-05", 1_000, 14, name)
            fx.spend("2026-10-05", 1_000, 14, name)
            fx.spend("2026-11-05", 1_100, 14, name)
        }
        assertEquals(listOf("Abe", "Zed"), SubscriptionChange.compute(fx.input()).rows.map { it.name })
    }
}

/**
 * Emergency fund. Aug to Oct 2026 each hold one expense of 1,000.00 (so 1,000.00 a month); liquid money is what the test says.
 */
class CoachFundTest {
    private fun fund(fx: CoachFx, liquid: Long): EmergencyFund.Result = EmergencyFund.compute(fx.input(mapOf(1L to liquid, 2L to 0L)))

    @Test
    fun monthsOfSpendingInTenthsAndTheNextHalfMonth() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.spend("2026-09-03", 100_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        val r = fund(fx, 210_000)
        assertEquals(Kind.READY, r.kind)
        assertEquals(100_000L, r.monthlySpending)
        assertEquals(3, r.monthsUsed)
        assertEquals(21, r.tenths)
        assertEquals(25, r.targetTenths)
        // 2.5 months is 250,000; 40,000 more over 4 weeks is 10,000 a week.
        assertEquals(10_000L, r.weeklyToTarget)
    }

    @Test
    fun onAHalfMonthBoundaryTheNextOneIsASuggestion() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.spend("2026-09-03", 100_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        val r = fund(fx, 250_000)
        assertEquals(25, r.tenths)
        assertEquals(30, r.targetTenths)
        assertEquals(12_500L, r.weeklyToTarget)
        val empty = fund(fx, 0)
        assertEquals(0, empty.tenths)
        assertEquals(5, empty.targetTenths)
        assertEquals(12_500L, empty.weeklyToTarget)
    }

    @Test
    fun theWeeklyAmountRoundsUp() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.spend("2026-09-03", 100_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        // 250,000 - 210,001 = 39,999, over 4 weeks 9,999.75 -> 10,000 a week. 210,001 is still 2.1 months.
        val r = fund(fx, 210_001)
        assertEquals(21, r.tenths)
        assertEquals(10_000L, r.weeklyToTarget)
        // 249,999: tenths 24, target 25, needed 1 -> 1 cent a week.
        val s = fund(fx, 249_999)
        assertEquals(24, s.tenths)
        assertEquals(25, s.targetTenths)
        assertEquals(1L, s.weeklyToTarget)
    }

    @Test
    fun sixMonthsIsEnough() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.spend("2026-09-03", 100_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        val r = fund(fx, 600_000)
        assertEquals(60, r.tenths)
        assertEquals(0, r.targetTenths)
        assertEquals(0L, r.weeklyToTarget)
        val big = fund(fx, 90_000_000_000)
        assertEquals(EmergencyFund.CAP_TENTHS, big.tenths)
    }

    @Test
    fun onlyLiquidAccountsCountAndANegativeBalanceIsZero() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.spend("2026-09-03", 100_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        fx.account(5, "Term deposit", AccountType.FIXED_DEPOSIT, opening = 9_000_000)
        fx.account(6, "Brokerage", AccountType.INVESTMENT, opening = 9_000_000)
        fx.account(7, "House", AccountType.OTHER_ASSET, opening = 9_000_000)
        fx.account(8, "Wallet", AccountType.PREPAID, opening = 10_000)
        fx.account(9, "Cash", AccountType.CASH, opening = 10_000)
        fx.account(3, "Card", AccountType.CREDIT_CARD, opening = -500_000)
        val r = fund(fx, 90_000)
        // 90,000 + 10,000 + 10,000 = 110,000: 1.1 months. The card's debt is not liquid money.
        assertEquals(110_000L, r.liquid)
        assertEquals(11, r.tenths)
        val under = EmergencyFund.compute(fx.input(mapOf(1L to -500_000L, 2L to 0L, 8L to 0L, 9L to 0L)))
        assertEquals(0L, under.liquid)
        assertEquals(0, under.tenths)
    }

    @Test
    fun needsAWholeMonth() {
        val fx = CoachFx()
        // Began on 15 October: no budget month before this one is whole.
        fx.spend("2026-10-15", 100_000, 10)
        fx.spend("2026-11-03", 100_000, 10)
        assertEquals(Kind.NOT_ENOUGH_DATA, fund(fx, 500_000).kind)
        assertEquals(500_000L, fund(fx, 500_000).liquid)
        assertEquals(Kind.NOT_ENOUGH_DATA, EmergencyFund.compute(CoachFx().input()).kind)
    }

    @Test
    fun usesAsManyWholeMonthsAsItHas() {
        val fx = CoachFx()
        fx.spend("2026-09-01", 300_000, 10)
        fx.spend("2026-10-03", 100_000, 10)
        val r = fund(fx, 400_000)
        assertEquals(2, r.monthsUsed)
        assertEquals(200_000L, r.monthlySpending)
        assertEquals(20, r.tenths)
    }

    @Test
    fun noSpendingAtAllIsNotInfinity() {
        val fx = CoachFx()
        fx.earn("2026-08-01", 100_000)
        fx.earn("2026-10-01", 100_000)
        val r = fund(fx, 500_000)
        assertEquals(Kind.NO_SPENDING, r.kind)
        assertEquals(0, r.tenths)
    }

    @Test
    fun monthlySpendingRoundsHalfUp() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 1, 10)
        fx.spend("2026-09-03", 2, 10)
        // 1 + 2 + 0 over three months is 1.
        assertEquals(1L, fund(fx, 100).monthlySpending)
        fx.spend("2026-10-04", 1, 10)
        // 4 / 3 = 1.33 -> 1; with a fourth cent 5 / 3 = 1.67 -> 2.
        assertEquals(1L, fund(fx, 100).monthlySpending)
        fx.spend("2026-10-05", 1, 10)
        assertEquals(2L, fund(fx, 100).monthlySpending)
    }

    @Test
    fun spendingIncludesUncategorisedAndYourShareOnly() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, null)
        fx.spend("2026-09-03", 300_000, 10, share = 100_000)
        fx.spend("2026-10-03", 100_000, 12)
        fx.earn("2026-10-04", 900_000)
        fx.other(TxKind.TRANSFER, "2026-10-04", 900_000, to = 2)
        val r = fund(fx, 100_000)
        assertEquals(100_000L, r.monthlySpending)
        assertEquals(10, r.tenths)
    }

    @Test
    fun aCountedLoanPrincipalIsMonthlySpending() {
        val fx = CoachFx()
        fx.spend("2026-08-01", 100_000, 10)
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        fx.loanTerms[4] = LoanTerms(
            accountId = 4, type = InterestType.REDUCING, rate = 0, payment = 50_000, nextPaymentDay = fx.day("2026-12-05"), dueDay = 5,
            countAllAsSpending = true, principalCategoryId = 12,
        )
        var n = 0L
        for (m in listOf("2026-09", "2026-10")) {
            val p = fx.other(TxKind.TRANSFER, "$m-05", 50_000, account = 1, to = 4)
            fx.loanPayments.add(LoanPayment(++n, 4, fx.day("$m-05"), 50_000, p, null, null, 0, 0, false, 0, true))
        }
        // August 1,000.00, September 500.00, October 500.00 -> 2,000 / 3 = 666.67 -> 666.67 (66,667 cents).
        assertEquals(66_667L, fund(fx, 100_000).monthlySpending)
        fx.loanTerms[4] = fx.loanTerms.getValue(4).copy(countAllAsSpending = false)
        assertEquals(33_333L, fund(fx, 100_000).monthlySpending)
    }

    @Test
    fun aMonthThatStartsOnTheFifteenth() {
        val fx = CoachFx("2026-11-20")
        fx.monthStart = 15
        // Full budget months before 15 Nov: 15 Oct to 14 Nov, 15 Sep to 14 Oct, 15 Aug to 14 Sep. The first entry is 15 August.
        fx.spend("2026-08-15", 90_000, 10)
        fx.spend("2026-09-20", 60_000, 10)
        fx.spend("2026-10-20", 30_000, 10)
        val r = fund(fx, 120_000)
        assertEquals(3, r.monthsUsed)
        assertEquals(60_000L, r.monthlySpending)
        assertEquals(20, r.tenths)
    }

    @Test
    fun theHelperDivisionsAreExact() {
        assertEquals(3L, CoachMath.divHalfUp(5, 2))
        assertEquals(1L, CoachMath.divHalfUp(4, 3))
        assertEquals(0L, CoachMath.divHalfUp(5, 0))
        assertEquals(0L, CoachMath.divHalfUp(-5, 2))
        assertEquals(3L, CoachMath.divCeil(5, 2))
        assertEquals(2L, CoachMath.divFloor(5, 2))
    }
}
