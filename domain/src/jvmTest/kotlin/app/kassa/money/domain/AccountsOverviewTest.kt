package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountsOverviewTest {

    private fun acct(id: Long, name: String, type: AccountType, opening: Long = 0, closed: Int? = null, archived: Boolean = false) =
        Account(id = id, name = name, type = type, openingBalance = opening, closedDay = closed, archived = archived)

    private fun overview(list: List<Account>, receivable: Long = 0, payable: Long = 0) =
        AccountsOverview.of(list, value = { it.openingBalance }, receivable = receivable, payable = payable)

    @Test
    fun aFreshUserWithOnlyCashSeesOneGroupAndNoLiabilities() {
        val o = overview(listOf(acct(1, "Wallet", AccountType.CASH, 6_000), acct(2, "Bank", AccountType.CHECKING, 125_000)))
        assertEquals(listOf(AccountSection.CASH_AND_BANK), o.groups.map { it.section })
        assertTrue(o.liabilityGroups.isEmpty())
        assertEquals(131_000, o.assets)
        assertEquals(0, o.liabilities)
        assertEquals(131_000, o.netWorth)
        assertEquals(listOf("Bank", "Wallet"), o.groups.single().lines.map { it.account.name })
    }

    @Test
    fun groupsFollowTheFixedOrderAndOnlyExistWhenUsed() {
        val o = overview(
            listOf(
                acct(1, "Mortgage", AccountType.LOAN, -20_000_000),
                acct(2, "Gold", AccountType.OTHER_ASSET, 500_000),
                acct(3, "Term deposit", AccountType.FIXED_DEPOSIT, 1_000_000),
                acct(4, "Brokerage", AccountType.INVESTMENT, 300_000),
                acct(5, "Card", AccountType.CREDIT_CARD, -40_000),
                acct(6, "Friend", AccountType.OTHER_DEBT, -10_000),
            ),
        )
        assertEquals(
            listOf(
                AccountSection.CASH_AND_BANK, AccountSection.INVESTMENTS, AccountSection.OTHER_ASSETS,
                AccountSection.CREDIT_CARDS, AccountSection.LOANS, AccountSection.OTHER_DEBTS,
            ),
            o.groups.map { it.section },
        )
        assertEquals(listOf(AccountSection.CASH_AND_BANK, AccountSection.INVESTMENTS, AccountSection.OTHER_ASSETS), o.assetGroups.map { it.section })
        assertEquals("Term deposit", o.assetGroups.first().lines.single().account.name)
    }

    @Test
    fun fixedDepositCountsAsCashAndBankAndAsAnAsset() {
        assertEquals(AccountSection.CASH_AND_BANK, AccountSection.of(AccountType.FIXED_DEPOSIT))
        assertTrue(!AccountType.FIXED_DEPOSIT.isDebt)
        assertTrue(AccountType.FIXED_DEPOSIT.hasMaturity)
        assertTrue(!AccountType.SAVINGS.hasMaturity)
    }

    @Test
    fun debtsAreShownAsWhatIsOwed() {
        val o = overview(listOf(acct(1, "Card", AccountType.CREDIT_CARD, -125_000), acct(2, "Car loan", AccountType.LOAN, -2_000_000)))
        assertEquals(125_000, o.liabilityGroups[0].lines.single().shown)
        assertEquals(125_000, o.liabilityGroups[0].total)
        assertEquals(2_125_000, o.liabilities)
        assertEquals(-2_125_000, o.netWorth)
    }

    @Test
    fun anOverpaidCardIsACreditAndLowersWhatIsOwed() {
        val o = overview(listOf(acct(1, "Card", AccountType.CREDIT_CARD, 5_900), acct(2, "Other", AccountType.CREDIT_CARD, -10_000)))
        assertEquals(listOf(-5_900L, 10_000L), o.liabilityGroups.single().lines.sortedBy { it.account.id }.map { it.shown })
        assertEquals(4_100, o.liabilities)
    }

    @Test
    fun netWorthBelowZeroKeepsItsSign() {
        val o = overview(listOf(acct(1, "Bank", AccountType.CHECKING, 100_000), acct(2, "Loan", AccountType.LOAN, -900_000)))
        assertEquals(-800_000, o.netWorth)
        assertTrue(o.netWorth < 0)
    }

    @Test
    fun closedAndArchivedAccountsAreInNoFigureAndNoGroup() {
        val o = overview(
            listOf(
                acct(1, "Bank", AccountType.CHECKING, 100_000),
                acct(2, "Old card", AccountType.CREDIT_CARD, 0, closed = 20_000),
                acct(3, "Closed savings", AccountType.SAVINGS, 777, closed = 19_000),
                acct(4, "Archived", AccountType.CASH, 5_000, archived = true),
            ),
        )
        assertEquals(100_000, o.assets)
        assertEquals(0, o.liabilities)
        assertEquals(listOf(AccountSection.CASH_AND_BANK), o.groups.map { it.section })
        assertEquals(listOf("Closed savings", "Old card"), o.closed.map { it.name })
    }

    @Test
    fun peopleOweingAndOwedAddToTheTotals() {
        val o = overview(listOf(acct(1, "Bank", AccountType.CHECKING, 100_000)), receivable = 2_500, payable = 700)
        assertEquals(102_500, o.assets)
        assertEquals(700, o.liabilities)
        assertEquals(101_800, o.netWorth)
    }

    @Test
    fun orderAppliesInsideEachGroup() {
        val list = listOf(acct(1, "B", AccountType.CASH, 50), acct(2, "a", AccountType.CASH, 900), acct(3, "C", AccountType.CASH, 10))
        val byName = overview(list).groups.single().lines.map { it.account.name }
        assertEquals(listOf("a", "B", "C"), byName)
        val bySize = AccountsOverview.of(list, { it.openingBalance }, order = compareByDescending { it.shown })
        assertEquals(listOf("a", "B", "C"), bySize.groups.single().lines.map { it.account.name })
        val smallestFirst = AccountsOverview.of(list, { it.openingBalance }, order = compareBy { it.shown })
        assertEquals(listOf("C", "B", "a"), smallestFirst.groups.single().lines.map { it.account.name })
    }

    @Test
    fun noAccountsGivesAnEmptyOverview() {
        val o = overview(emptyList())
        assertTrue(o.groups.isEmpty() && o.closed.isEmpty())
        assertEquals(0, o.netWorth)
    }

    @Test
    fun onlyASettledAccountCloses() {
        assertEquals(null, closeProblem(0))
        assertEquals("Pay off or move the balance first.", closeProblem(1))
        assertEquals("Pay off or move the balance first.", closeProblem(-1))
    }

    @Test
    fun aPaymentIsUrgentFromItsDueDay() {
        assertTrue(AccountsOverview.dueNeedsAttention(due = 100, today = 100))
        assertTrue(AccountsOverview.dueNeedsAttention(due = 99, today = 100))
        assertTrue(!AccountsOverview.dueNeedsAttention(due = 101, today = 100))
    }

    @Test
    fun closedDayDecidesIfAnAccountIsActive() {
        assertTrue(acct(1, "A", AccountType.CASH).isActive)
        assertTrue(!acct(1, "A", AccountType.CASH, closed = 5).isActive)
        assertTrue(acct(1, "A", AccountType.CASH, closed = 5).isClosed)
        assertTrue(!acct(1, "A", AccountType.CASH, archived = true).isActive)
    }
}
