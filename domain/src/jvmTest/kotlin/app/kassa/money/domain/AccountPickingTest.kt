package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountPickingTest {
    private val checking = Account(id = 1, name = "Everyday checking", type = AccountType.CHECKING, last4 = "4821")
    private val savings = Account(id = 2, name = "college savings", type = AccountType.SAVINGS)
    private val wallet = Account(id = 3, name = "Wallet", type = AccountType.CASH)
    private val card = Account(id = 4, name = "Travel card", type = AccountType.CREDIT_CARD, last4 = "9911")
    private val loan = Account(id = 5, name = "Car loan", type = AccountType.LOAN)
    private val closed = Account(id = 6, name = "Old savings", type = AccountType.SAVINGS, closedDay = 20_000)
    private val archived = Account(id = 7, name = "Hidden", type = AccountType.CHECKING, archived = true)
    private val all = listOf(card, wallet, checking, loan, closed, savings, archived)

    @Test
    fun closedArchivedAndExcludedAccountsAreLeftOut() {
        val available = AccountPicking.available(all, excluded = setOf(3L))
        assertEquals(setOf(1L, 2L, 4L, 5L), available.map { it.id }.toSet())
    }

    @Test
    fun groupsFollowTheKindOrderAndAreAToZInside() {
        val groups = AccountPicking.groups(AccountPicking.available(all))
        assertEquals(listOf(AccountSection.CASH_AND_BANK, AccountSection.CREDIT_CARDS, AccountSection.LOANS), groups.map { it.section })
        assertEquals(listOf("college savings", "Everyday checking", "Wallet"), groups.first().accounts.map { it.name })
    }

    @Test
    fun theKindChipNarrowsTheGroups() {
        val groups = AccountPicking.groups(AccountPicking.available(all), AccountKindFilter.CARDS)
        assertEquals(listOf(card), groups.single().accounts)
    }

    @Test
    fun typingMatchesNameOrLastFourIgnoringCase() {
        val available = AccountPicking.available(all)
        assertEquals(listOf(savings), AccountPicking.groups(available, query = "SAV").single().accounts)
        assertEquals(listOf(card), AccountPicking.groups(available, query = "9911").single().accounts)
        assertTrue(AccountPicking.groups(available, query = "zzz").isEmpty())
    }

    @Test
    fun onlyKindsThatHaveAnAccountGetAChip() {
        val chips = AccountPicking.filtersFor(AccountPicking.available(listOf(checking, card)))
        assertEquals(listOf(AccountKindFilter.ALL, AccountKindFilter.CASH_AND_BANK, AccountKindFilter.CARDS), chips)
    }

    @Test
    fun recentKeepsUseOrderSkipsUnavailableAndDuplicatesAndCaps() {
        val available = AccountPicking.available(all)
        val recent = AccountPicking.recent(listOf(6L, 4L, 4L, 1L, 2L, 5L), available, limit = 3)
        assertEquals(listOf(4L, 1L, 2L), recent.map { it.id })
        assertTrue(AccountPicking.recent(emptyList(), available, 3).isEmpty())
    }
}
