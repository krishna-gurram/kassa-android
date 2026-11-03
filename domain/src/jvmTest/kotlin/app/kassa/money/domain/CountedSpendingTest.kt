package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CountedSpendingTest {
    private fun cat(id: Long, name: String, kind: CategoryKind = CategoryKind.EXPENSE) = Category(id = id, name = name, kind = kind, color = 0)

    private val categories = listOf(cat(1, "Loan interest"), cat(2, "Mortgage"), cat(3, "Escrow"), cat(4, "Groceries"), cat(5, "Salary", CategoryKind.INCOME))

    private fun terms(on: Boolean = false, principal: Long? = null, interest: Long? = null) = LoanTerms(
        accountId = 10, type = InterestType.REDUCING, rate = 60_000, payment = 100_000, nextPaymentDay = 0, dueDay = 1,
        interestCategoryId = interest, countAllAsSpending = on, principalCategoryId = principal,
    )

    // Payment 1: principal 11, interest 12, escrow 13. Checking is account 1, the loan is account 10.
    private fun payment(over: Boolean? = null, principal: Long? = null) = LoanPayment(
        id = 1, accountId = 10, day = 0, amount = 150_000, principalEntryId = 11, interestEntryId = 12, otherEntryId = 13,
        escrow = 20_000, charge = 0, edited = false, estimate = 0, scheduled = true, countAllAsSpending = over, principalCategoryId = principal,
    )

    private val entries = listOf(
        Transaction(id = 11, kind = TxKind.TRANSFER, amount = 90_000, accountId = 1, toAccountId = 10, epochMillis = 1, note = "Home principal"),
        Transaction(id = 12, kind = TxKind.EXPENSE, amount = 40_000, accountId = 1, categoryId = 1, epochMillis = 1, note = "Home interest"),
        Transaction(id = 13, kind = TxKind.EXPENSE, amount = 20_000, accountId = 1, categoryId = 3, epochMillis = 1, note = "Home escrow"),
        Transaction(id = 20, kind = TxKind.TRANSFER, amount = 5_000, accountId = 1, toAccountId = 2, epochMillis = 1, note = "Savings"),
        Transaction(id = 21, kind = TxKind.INCOME, amount = 300_000, accountId = 1, categoryId = 5, epochMillis = 1),
        Transaction(id = 22, kind = TxKind.ADJUST_OUT, amount = 700, accountId = 10, epochMillis = 1),
        Transaction(id = 23, kind = TxKind.EXPENSE, amount = 8_000, accountId = 1, categoryId = 4, epochMillis = 1),
    )

    private fun spent(list: List<Transaction>) = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }

    private fun apply(t: LoanTerms, p: LoanPayment, list: List<Transaction> = entries) =
        CountedSpending.apply(list, listOf(p), mapOf(10L to t), categories)

    @Test
    fun offLeavesEveryEntryAsItIs() {
        val out = apply(terms(), payment())
        assertSame(entries, out)
        assertEquals(68_000, spent(out))
    }

    @Test
    fun onCountsThePrincipalInTheInterestCategoryByDefault() {
        val out = apply(terms(on = true), payment())
        val principal = out.first { it.id == 11L }
        assertEquals(TxKind.EXPENSE, principal.kind)
        assertEquals(1L, principal.categoryId)
        assertEquals(90_000, principal.amount)
        assertEquals(1L, principal.accountId)
        assertEquals(68_000 + 90_000, spent(out))
        assertEquals(1, out.indices.count { out[it] != entries[it] })
    }

    @Test
    fun theLoansPrincipalCategoryAndThePaymentsOwnCategoryWinInOrder() {
        assertEquals(2L, apply(terms(on = true, principal = 2), payment()).first { it.id == 11L }.categoryId)
        assertEquals(4L, apply(terms(on = true, principal = 2), payment(principal = 4)).first { it.id == 11L }.categoryId)
    }

    @Test
    fun sameAsInterestFollowsWhereThePaymentsInterestEntryWent() {
        val moved = entries.map { if (it.id == 12L) it.copy(categoryId = 4) else it }
        assertEquals(4L, apply(terms(on = true, interest = 2), payment(), moved).first { it.id == 11L }.categoryId)
        // With no interest entry in the list, the loan's interest category and then "Loan interest" stand in.
        val noInterest = entries.filter { it.id != 12L }
        assertEquals(2L, apply(terms(on = true, interest = 2), payment(), noInterest).first { it.id == 11L }.categoryId)
        assertEquals(1L, apply(terms(on = true), payment(), noInterest).first { it.id == 11L }.categoryId)
    }

    @Test
    fun aPaymentCanTurnItOffOrOnForItself() {
        assertSame(entries, apply(terms(on = true), payment(over = false)))
        assertEquals(68_000 + 90_000, spent(apply(terms(on = false), payment(over = true))))
    }

    @Test
    fun itReachesPaymentsRecordedBeforeTheSwitchWasTurnedOn() {
        // Nothing is rewritten: the same stored entries give the old figure with the switch off and the new one with it on.
        val p = payment()
        assertEquals(68_000, spent(apply(terms(on = false), p)))
        assertEquals(158_000, spent(apply(terms(on = true), p)))
        assertEquals(68_000, spent(apply(terms(on = false), p)))
    }

    @Test
    fun incomeCorrectionsAndOtherTransfersNeverCount() {
        val out = apply(terms(on = true), payment())
        assertEquals(TxKind.TRANSFER, out.first { it.id == 20L }.kind)
        assertEquals(TxKind.INCOME, out.first { it.id == 21L }.kind)
        assertEquals(TxKind.ADJUST_OUT, out.first { it.id == 22L }.kind)
        assertEquals(300_000, out.filter { it.kind == TxKind.INCOME }.sumOf { it.amount })
    }

    @Test
    fun balancesAndNetWorthNeverChange() {
        val checking = Account(id = 1, name = "Checking", type = AccountType.CHECKING, openingBalance = 1_000_000)
        val loan = Account(id = 10, name = "Home", type = AccountType.LOAN, openingBalance = -5_000_000)
        val saving = Account(id = 2, name = "Savings", type = AccountType.SAVINGS)
        fun netWorth() = listOf(checking, loan, saving).sumOf { balanceOf(it, entries) }
        val before = netWorth()
        apply(terms(on = true), payment())
        // The rule only builds a spending view: the real entries are untouched, so the principal still lowers the loan.
        assertEquals(before, netWorth())
        assertEquals(-5_000_000 + 90_000 - 700, balanceOf(loan, entries))
        assertEquals(TxKind.TRANSFER, entries.first { it.id == 11L }.kind)
    }

    @Test
    fun anIncomeOrMissingCategoryIsSkippedAndNoneLeavesThePrincipalOut() {
        // An income category can't hold spending, so the chain moves on to where the interest went.
        assertEquals(1L, apply(terms(on = true, principal = 5), payment()).first { it.id == 11L }.categoryId)
        val none = CountedSpending.apply(entries, listOf(payment()), mapOf(10L to terms(on = true)), emptyList())
        assertEquals(TxKind.TRANSFER, none.first { it.id == 11L }.kind)
    }

    @Test
    fun countedIdsAreThePrincipalEntriesOfPaymentsThatCount() {
        assertEquals(setOf(11L), CountedSpending.countedIds(listOf(payment()), mapOf(10L to terms(on = true))))
        assertTrue(CountedSpending.countedIds(listOf(payment()), mapOf(10L to terms())).isEmpty())
    }
}
