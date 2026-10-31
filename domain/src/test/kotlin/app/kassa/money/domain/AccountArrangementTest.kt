package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountArrangementTest {

    private fun acct(id: Long, name: String, type: AccountType, opening: Long = 0, closed: Int? = null) =
        Account(id = id, name = name, type = type, openingBalance = opening, closedDay = closed)

    private val groups = listOf("CASH_AND_BANK", "CREDIT_CARDS", "LOANS")

    @Test
    fun knownGroupsFollowTheArrangementAndNewOnesGoLastAToZ() {
        val a = AccountArrangement(groups = listOf("LOANS", "CASH_AND_BANK"))
        val out = a.orderGroups(listOf("CASH_AND_BANK", "OTHER_DEBTS", "INVESTMENTS", "LOANS"), { it }, { it.lowercase() })
        assertEquals(listOf("LOANS", "CASH_AND_BANK", "INVESTMENTS", "OTHER_DEBTS"), out)
    }

    @Test
    fun rowsFollowTheArrangementUnknownLastAToZAndGoneIdsAreIgnored() {
        val a = AccountArrangement(accounts = mapOf("CASH_AND_BANK" to listOf(3L, 99L, 1L)))
        val items = listOf(1L to "Alpha", 2L to "Zed", 3L to "Mid", 4L to "Beta")
        val out = a.orderRows("CASH_AND_BANK", items, { it.first }, { it.second })
        assertEquals(listOf(3L, 1L, 4L, 2L), out.map { it.first })
    }

    @Test
    fun aGroupWithNoSavedOrderIsAToZ() {
        val out = AccountArrangement().orderRows("LOANS", listOf(2L to "b", 1L to "A"), { it.first }, { it.second })
        assertEquals(listOf(1L, 2L), out.map { it.first })
    }

    @Test
    fun movingAGroupSwapsItWithItsNeighbourAndStopsAtTheEnds() {
        val a = AccountArrangement().moveGroup(groups, groups, "CREDIT_CARDS", -1)
        assertEquals(listOf("CREDIT_CARDS", "CASH_AND_BANK", "LOANS"), a.groups)
        assertEquals(groups, AccountArrangement().moveGroup(groups, groups, "CASH_AND_BANK", -1).groups)
        assertEquals(groups, AccountArrangement().moveGroup(groups, groups, "LOANS", 1).groups)
    }

    @Test
    fun aGroupMoveStaysInsideItsColumn() {
        val all = listOf("CASH_AND_BANK", "INVESTMENTS", "CREDIT_CARDS", "LOANS")
        val assets = listOf("CASH_AND_BANK", "INVESTMENTS")
        val liabilities = listOf("CREDIT_CARDS", "LOANS")
        // The first liability can't go up into assets.
        assertEquals(all, AccountArrangement().moveGroup(all, liabilities, "CREDIT_CARDS", -1).groups)
        assertEquals(listOf("INVESTMENTS", "CASH_AND_BANK", "CREDIT_CARDS", "LOANS"), AccountArrangement().moveGroup(all, assets, "INVESTMENTS", -1).groups)
        assertEquals(listOf("CASH_AND_BANK", "INVESTMENTS", "LOANS", "CREDIT_CARDS"), AccountArrangement().moveGroup(all, liabilities, "CREDIT_CARDS", 1).groups)
    }

    @Test
    fun movingAnAccountStaysInItsGroupAndKeepsGroupOrder() {
        val a = AccountArrangement().moveAccount(groups, "CREDIT_CARDS", listOf(5L, 6L, 7L), 7L, -1)
        assertEquals(listOf(5L, 7L, 6L), a.accounts["CREDIT_CARDS"])
        assertEquals(groups, a.groups)
        val ends = a.moveAccount(groups, "CREDIT_CARDS", listOf(5L, 7L, 6L), 5L, -1)
        assertEquals(listOf(5L, 7L, 6L), ends.accounts["CREDIT_CARDS"])
    }

    @Test
    fun resetIsTheEmptyArrangement() {
        assertTrue(AccountArrangement.EMPTY.groups.isEmpty() && AccountArrangement.EMPTY.accounts.isEmpty())
    }

    @Test
    fun encodesAndDecodesRoundTrip() {
        val a = AccountArrangement(listOf("CREDIT_CARDS", "CASH_AND_BANK"), mapOf("CREDIT_CARDS" to listOf(7L, 5L), "CASH_AND_BANK" to listOf(1L)))
        assertEquals(a, AccountArrangement.decode(a.encode()))
    }

    @Test
    fun decodeToleratesNullBlankAndDamagedText() {
        assertNull(AccountArrangement.decode(null))
        assertNull(AccountArrangement.decode(""))
        assertNull(AccountArrangement.decode("garbage"))
        assertNull(AccountArrangement.decode("|"))
        assertEquals(AccountArrangement(listOf("A"), mapOf("A" to listOf(1L, 3L))), AccountArrangement.decode("A,A|A=1,x,3;bad;=4"))
    }

    @Test
    fun overviewFollowsTheArrangementAndColumnsKeepTheirOrder() {
        val list = listOf(
            acct(1, "Bank", AccountType.CHECKING, 100), acct(2, "Savings", AccountType.SAVINGS, 100),
            acct(3, "Visa", AccountType.CREDIT_CARD, -50), acct(4, "Amex", AccountType.CREDIT_CARD, -20),
            acct(5, "Old", AccountType.CHECKING, 0, closed = 100), acct(6, "Car", AccountType.LOAN, -900),
        )
        val a = AccountArrangement(listOf("CREDIT_CARDS", "CASH_AND_BANK"), mapOf("CREDIT_CARDS" to listOf(3L, 4L), "CASH_AND_BANK" to listOf(2L, 1L, 5L)))
        val o = AccountsOverview.of(list, { it.openingBalance }, arrangement = a)
        assertEquals(listOf(AccountSection.CREDIT_CARDS, AccountSection.CASH_AND_BANK, AccountSection.LOANS), o.groups.map { it.section })
        assertEquals(listOf("Visa", "Amex"), o.groups[0].lines.map { it.account.name })
        assertEquals(listOf("Savings", "Bank"), o.groups[1].lines.map { it.account.name })
        assertEquals(listOf(AccountSection.CASH_AND_BANK), o.assetGroups.map { it.section })
        assertEquals(listOf(AccountSection.CREDIT_CARDS, AccountSection.LOANS), o.liabilityGroups.map { it.section })
        // Same totals either way.
        assertEquals(AccountsOverview.of(list, { it.openingBalance }).netWorth, o.netWorth)
    }

    @Test
    fun flattenGivesGroupOrderThenRowOrder() {
        val list = listOf(acct(1, "Bank", AccountType.CHECKING), acct(3, "Visa", AccountType.CREDIT_CARD), acct(4, "Amex", AccountType.CREDIT_CARD))
        val a = AccountArrangement(listOf("CREDIT_CARDS"), mapOf("CREDIT_CARDS" to listOf(3L, 4L)))
        val out = a.flatten(list, { AccountSection.of(it.type).name }, { AccountSection.of(it.type).title }, { it.id }, { it.name })
        assertEquals(listOf("Visa", "Amex", "Bank"), out.map { it.name })
    }
}
