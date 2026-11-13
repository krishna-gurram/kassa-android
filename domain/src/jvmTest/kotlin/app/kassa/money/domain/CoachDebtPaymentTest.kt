package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paying a card or a loan is not spending and not a subscription. Synthetic ledger: a card account called "Credit card" that is paid
 * every month twice over (a TRANSFER with Kassa's own "Card payment" note, and the same bill typed in as an EXPENSE in a category called
 * "Credit card"), the latest one higher, plus a closed loan whose interest entries are monthly and fixed. None of it may show up as a
 * subscription row, a pace flag, usual spending, a safe-to-spend part or an input of the emergency fund.
 */
class CoachDebtPaymentTest {

    private fun fixture(): CoachFx {
        val fx = CoachFx()
        fx.opening(1, 5_000_000L)
        fx.account(3, "Credit card", AccountType.CREDIT_CARD, statementDay = 20, dueDay = 10)
        fx.account(4, "Old car loan", AccountType.LOAN)
        fx.accounts[fx.accounts.size - 1] = fx.accounts.last().copy(closedDay = fx.day("2026-09-30"))
        fx.categories.add(Category(id = 30, name = "Credit card", kind = CategoryKind.EXPENSE, color = 0))
        fx.categories.add(Category(id = 31, name = "Loan interest", kind = CategoryKind.EXPENSE, color = 0))
        // Three whole months of groceries, so the earlier months count.
        fx.monthly(CoachFx.AUG_TO_OCT, 1, 10_000, 10, "")
        val months = CoachFx.AUG_TO_OCT + "2026-11"
        for ((i, m) in months.withIndex()) {
            val cents = if (i == 3) 130_000L else 120_000L
            fx.spend("$m-05", cents, 30, "Credit card payment")
            fx.other(TxKind.TRANSFER, "$m-06", cents, account = 1, to = 3, note = "Card payment")
        }
        for ((i, m) in CoachFx.AUG_TO_OCT.withIndex()) fx.spend("$m-15", 20_000L + i * 100L, 31, "Old car loan interest")
        return fx
    }

    @Test
    fun aCardPaymentIsNeverASubscription() {
        val r = SubscriptionChange.compute(fixture().input())
        assertTrue(r.rows.isEmpty(), "rows: ${r.rows}")
        assertEquals(0, r.count)
        assertEquals(0L, r.monthlyTotal)
    }

    @Test
    fun aCardPaymentIsNeverAPaceFlagOrARow() {
        val r = SpendingPace.compute(fixture().input())
        assertTrue(r.rows.none { it.categoryId == 30L }, "rows: ${r.rows}")
        assertTrue(r.flagged.none { it.categoryId == 30L })
    }

    @Test
    fun theEmergencyFundReadsLiquidAccountsAndRealSpendingOnly() {
        val fx = fixture()
        val input = fx.input(extra = mapOf(3L to 777_700L, 4L to 888_800L))
        val r = EmergencyFund.compute(input)
        assertEquals(EmergencyFund.Kind.READY, r.kind)
        // The card and the loan are debts: only the checking and the savings account are liquid money.
        assertEquals(input.balances[1]!! + input.balances[2]!!, r.liquid)
        // Groceries of 100.00 plus the closed loan's interest, average 201.00 (a loan's own interest is spending as everywhere): never the
        // 1,200.00 card bill, typed in as an expense or sent as a transfer.
        assertEquals(30_100L, r.monthlySpending)
    }

    @Test
    fun noRegularChargeOrLoanInterestIsExpectedBeforePayday() {
        val s = SafeToSpend.compute(fixture().input())
        assertNull(s.part(SafeToSpend.PartKind.RECURRING))
    }

    @Test
    fun theCheckInHasNothingToWatchAboutACardPayment() {
        val r = WeeklyCheckIn.compute(fixture().input())
        assertNull(r.watch)
        assertNull(r.decision)
    }

    @Test
    fun aClosedLoansInterestIsNotASubscription() {
        val fx = CoachFx()
        fx.account(4, "Old car loan", AccountType.LOAN)
        fx.accounts[fx.accounts.size - 1] = fx.accounts.last().copy(closedDay = fx.day("2026-10-31"))
        fx.monthly(CoachFx.AUG_TO_OCT, 15, 20_000, 10, "Old car loan interest")
        fx.spend("2026-11-10", 25_000, 10, "Old car loan interest")
        val r = SubscriptionChange.compute(fx.input())
        assertTrue(r.rows.isEmpty(), "rows: ${r.rows}")
        assertTrue(r.count == 0)
    }

    @Test
    fun aSubscriptionNamedLikeNothingOwedStillCounts() {
        val fx = fixture()
        fx.spend("2026-08-07", 1_599, 14, "Streaming")
        fx.spend("2026-09-07", 1_599, 14, "Streaming")
        fx.spend("2026-10-07", 1_599, 14, "Streaming")
        fx.spend("2026-11-07", 1_999, 14, "Streaming")
        val r = SubscriptionChange.compute(fx.input())
        assertEquals(listOf("Streaming"), r.rows.map { it.name })
    }
}
