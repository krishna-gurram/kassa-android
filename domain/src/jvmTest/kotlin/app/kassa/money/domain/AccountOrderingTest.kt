package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountOrderingTest {

    private fun acct(id: Long, name: String, type: AccountType = AccountType.CHECKING) =
        Account(id = id, name = name, type = type, openingBalance = 0)

    private val lines = listOf(
        AccountLine(acct(1, "Beta"), 500),
        AccountLine(acct(2, "alpha"), 9_000),
        AccountLine(acct(3, "Gamma"), -20_000),
        AccountLine(acct(4, "Delta"), 500),
    )

    @Test
    fun aStoredSortHoldsAndYourOrderNeedsAnArrangement() {
        val some = AccountArrangement(groups = listOf("LOANS"))
        assertEquals(AccountSort.BALANCE, AccountOrdering.effective("BALANCE", some))
        assertEquals(AccountSort.CUSTOM, AccountOrdering.effective("CUSTOM", some))
        assertEquals(AccountSort.NAME, AccountOrdering.effective("CUSTOM", null))
        assertEquals(AccountSort.NAME, AccountOrdering.effective("nonsense", some))
    }

    @Test
    fun noStoredSortShowsAnArrangementThatCameWithABackup() {
        assertEquals(AccountSort.CUSTOM, AccountOrdering.effective(null, AccountArrangement(groups = listOf("LOANS"))))
        assertEquals(AccountSort.CUSTOM, AccountOrdering.effective("", AccountArrangement(groups = listOf("LOANS"))))
        assertEquals(AccountSort.NAME, AccountOrdering.effective(null, null))
    }

    @Test
    fun onlyYourOrderKeepsTheArrangement() {
        val a = AccountArrangement(groups = listOf("LOANS"))
        assertEquals(a, AccountOrdering.arrangementFor(AccountSort.CUSTOM, a))
        assertNull(AccountOrdering.arrangementFor(AccountSort.NAME, a))
        assertNull(AccountOrdering.arrangementFor(AccountSort.DUE, a))
    }

    @Test
    fun nameIsAToZAndBalanceIsLargestFirstByTheSizeThenName() {
        val byName = lines.sortedWith(AccountOrdering.comparator(AccountSort.NAME) { null })
        assertEquals(listOf("alpha", "Beta", "Delta", "Gamma"), byName.map { it.account.name })
        val byBalance = lines.sortedWith(AccountOrdering.comparator(AccountSort.BALANCE) { null })
        assertEquals(listOf("Gamma", "alpha", "Beta", "Delta"), byBalance.map { it.account.name })
    }

    @Test
    fun dueDatePutsWhatIsComingFirstAndTheRestAToZ() {
        val days = mapOf(1L to 200, 3L to 150)
        val sorted = lines.sortedWith(AccountOrdering.comparator(AccountSort.DUE) { days[it.id] })
        assertEquals(listOf("Gamma", "Beta", "alpha", "Delta"), sorted.map { it.account.name })
    }

    @Test
    fun shownKeepsTheGroupsAndRowsAsTheTabDrawsThem() {
        val accounts = listOf(acct(1, "B"), acct(2, "A"), acct(3, "Visa", AccountType.CREDIT_CARD))
        val overview = AccountsOverview.of(accounts, { 0L })
        val shown = AccountOrdering.shown(overview)
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_CARDS"), shown.groups)
        assertEquals(listOf(2L, 1L), shown.accounts["CASH_AND_BANK"])
        assertEquals(shown, AccountArrangement.decode(shown.encode()))
    }

    @Test
    fun pinsShowTheFirstEightUntilThePersonPicks() {
        val choices = (1L..10L).map { PinRules.key(it) } + PinRules.PEOPLE_KEY
        assertEquals(choices.take(8), PinRules.shown(choices, null))
        assertEquals(listOf("acct:2", "people"), PinRules.shown(choices, setOf("people", "acct:2", "acct:99")))
        assertEquals(emptyList(), PinRules.shown(choices, emptySet()))
    }

    @Test
    fun theFirstTickSavesWhatWasShownAndNineIsRefused() {
        val choices = (1L..10L).map { PinRules.key(it) }
        val untick = PinRules.toggled(choices, null, "acct:1")
        assertEquals(7, untick.size)
        assertTrue("acct:1" !in untick)
        // Eight are shown, so a ninth waits for one to go.
        assertEquals(choices.take(8).toSet(), PinRules.toggled(choices, null, "acct:9"))
        // With one untick made, the ninth fits.
        assertTrue("acct:9" in PinRules.toggled(choices, untick, "acct:9"))
        // A key that isn't a choice is never added.
        assertEquals(setOf("acct:1"), PinRules.toggled(choices, setOf("acct:1"), "acct:404"))
    }

    @Test
    fun pinsRoundTripAndAnEmptySetIsNotNever() {
        assertNull(PinRules.decode(null))
        assertEquals(emptySet(), PinRules.decode(""))
        assertEquals(setOf("acct:1", "people"), PinRules.decode(PinRules.encode(listOf("acct:1", "people"))))
    }
}
