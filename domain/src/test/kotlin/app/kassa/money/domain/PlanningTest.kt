package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlanningTest {
    private val zone = ZoneOffset.UTC
    private fun at(d: String) = LocalDate.parse(d).atTime(10, 0).toInstant(zone).toEpochMilli()

    private val groceries = Category(id = 1, name = "Groceries", kind = CategoryKind.EXPENSE, color = 0)
    private val costco = Category(id = 2, name = "Store C", kind = CategoryKind.EXPENSE, parentId = 1, color = 0)
    private val home = Category(id = 3, name = "Home", kind = CategoryKind.EXPENSE, color = 0, countsTowardDaily = false)

    @Test
    fun monthOverrideWinsOverDefault() {
        val b = listOf(Budget(1, 0, 85_000), Budget(1, 202610, 90_000))
        assertEquals(90_000, Budgets.amountFor(1, YearMonth.of(2026, 10), b))
        assertEquals(85_000, Budgets.amountFor(1, YearMonth.of(2026, 11), b))
        assertEquals(null, Budgets.amountFor(9, YearMonth.of(2026, 11), b))
    }

    @Test
    fun splitsAlwaysAddUp() {
        val r = Split.equal(10_001, 2)
        assertEquals(3_334, r.mine)
        assertEquals(10_001, r.mine + r.others.sum())
        assertEquals(Split.Result(4_225, listOf(4_225)), Split.equal(8_450, 1))
        assertFailsWith<IllegalArgumentException> { Split.equal(100, 0) }
    }

    @Test
    fun personBalanceCombinesSplitsAndPayments() {
        val loans = listOf(LoanEntry(personId = 7, amount = 14_230, epochMillis = 0), LoanEntry(personId = 7, amount = -6_000, epochMillis = 0))
        val txs = listOf(
            Transaction(kind = TxKind.PERSON_IN, amount = 5_000, accountId = 1, personId = 7, epochMillis = 0),
            Transaction(kind = TxKind.PERSON_OUT, amount = 1_000, accountId = 1, personId = 7, epochMillis = 0),
        )
        assertEquals(14_230 - 6_000 - 5_000 + 1_000, personBalance(7, loans, txs))
    }

    @Test
    fun paidByPersonExpenseNeedsAPerson() {
        Transaction(kind = TxKind.EXPENSE, amount = 6_000, accountId = null, personId = 3, epochMillis = 0)
        assertFailsWith<IllegalArgumentException> { Transaction(kind = TxKind.INCOME, amount = 1, accountId = null, epochMillis = 0) }
    }

    @Test
    fun splitWithSeveralPeopleYouPaid() {
        val o = Split.outcome(12_000, mapOf(1L to 5_000L, 2L to 4_000L), paidBy = null)
        assertEquals(3_000, o.myShare)
        assertEquals(listOf(1L to 5_000L, 2L to 4_000L), o.owed)
    }

    @Test
    fun splitSomeoneElsePaidYouOweYourShare() {
        val shares = Split.equalShares(12_000, listOf(1L, 2L))
        val o = Split.outcome(12_000, shares, paidBy = 1L)
        assertEquals(4_000, o.myShare)
        assertEquals(listOf(1L to -4_000L), o.owed)
    }

    @Test
    fun equalSharesKeepEveryCent() {
        val shares = Split.equalShares(10_000, listOf(1L, 2L))
        assertEquals(10_000 - shares.values.sum(), Split.outcome(10_000, shares, null).myShare)
        assertEquals(10_000, shares.values.sum() + Split.outcome(10_000, shares, null).myShare)
    }
}
