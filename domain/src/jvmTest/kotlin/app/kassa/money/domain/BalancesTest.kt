package app.kassa.money.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalancesTest {

    private val checking = Account(id = 1, name = "Checking", type = AccountType.CHECKING, openingBalance = 100_000)
    private val savings = Account(id = 2, name = "Savings", type = AccountType.SAVINGS, openingBalance = 5_000)
    private val card = Account(id = 3, name = "Card", type = AccountType.CREDIT_CARD, openingBalance = -2_000)

    private fun tx(id: Long, kind: TxKind, amount: Long, from: Long?, to: Long? = null, person: Long? = null) =
        Transaction(id = id, kind = kind, amount = amount, accountId = from, toAccountId = to, personId = person, epochMillis = id * 1_000)

    private val entries = listOf(
        tx(1, TxKind.INCOME, 30_000, 1),
        tx(2, TxKind.EXPENSE, 4_500, 1),
        tx(3, TxKind.TRANSFER, 10_000, 1, 2),
        tx(4, TxKind.TRANSFER, 5_000, 1, 3),
        tx(5, TxKind.ADJUST_OUT, 1_500, 2),
        tx(6, TxKind.ADJUST_IN, 700, 2),
        tx(7, TxKind.PERSON_OUT, 900, 1, person = 9),
        tx(8, TxKind.PERSON_IN, 400, 3, person = 9),
        tx(9, TxKind.BUY, 250, 1),
        tx(10, TxKind.SELL, 100, 1),
        tx(11, TxKind.EXPENSE, 1_000, null, person = 9),
    )

    @Test
    fun everyKindMovesTheBalanceTheWayTheSqlSumDoes() {
        val balances = Balances.of(listOf(checking, savings, card), entries)
        // Checking: 100000 +30000 -4500 -10000 -5000 -900 -250 +100.
        assertEquals(109_450L, balances[1])
        // Savings: 5000 +10000 -1500 +700.
        assertEquals(14_200L, balances[2])
        // Card: -2000 +5000 +400.
        assertEquals(3_400L, balances[3])
    }

    @Test
    fun agreesWithEffectOnForEveryAccount() {
        val accounts = listOf(checking, savings, card)
        val balances = Balances.of(accounts, entries)
        for (a in accounts) assertEquals(balanceOf(a, entries), balances[a.id], a.name)
    }

    @Test
    fun anAccountWithNoEntriesHoldsItsOpeningBalance() {
        assertEquals(mapOf(1L to 100_000L), Balances.of(listOf(checking), emptyList()))
    }

    @Test
    fun entriesOnUnknownAccountsAreIgnored() {
        val balances = Balances.of(listOf(checking), listOf(tx(1, TxKind.EXPENSE, 100, 77), tx(2, TxKind.TRANSFER, 50, 1, 78)))
        assertEquals(mapOf(1L to 99_950L), balances)
    }

    @Test
    fun loanEntryIndexKnowsTagsAndCountedPrincipal() {
        val terms = LoanTerms(accountId = 4, type = InterestType.REDUCING, rate = 60_000, payment = 100_000, nextPaymentDay = 20_470, dueDay = 17, countAllAsSpending = true)
        val payment = LoanPayment(
            id = 1, accountId = 4, day = 20_470, amount = 101_000, principalEntryId = 10, interestEntryId = 11, otherEntryId = 12,
            escrow = 1_000, charge = 0, edited = false, estimate = 5_000, scheduled = true,
        )
        val index = LoanEntryIndex(listOf(payment), mapOf(4L to terms))
        assertEquals("principal", index.tagFor(10))
        assertEquals("interest", index.tagFor(11))
        assertEquals("escrow", index.tagFor(12))
        assertNull(index.tagFor(13))
        assertTrue(index.isLoanEntry(11))
        assertFalse(index.isLoanEntry(13))
        assertTrue(index.countsAsSpending(10))
        assertFalse(index.countsAsSpending(11))
        val off = LoanEntryIndex(listOf(payment), mapOf(4L to terms.copy(countAllAsSpending = false)))
        assertFalse(off.countsAsSpending(10))
    }
}
