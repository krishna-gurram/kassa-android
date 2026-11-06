package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts > Order, Overview's pinned tiles, the phone choices and Account to person, as the iOS screens ask for them. */
class AccountOrderTest {

    private fun acct(id: Long, name: String, type: String, opening: Long, statement: Int? = null, due: Int? = null, closed: Int? = null, archived: Boolean = false) =
        BackupAccount(id, name, type, null, opening, statement, due, null, null, archived, id.toInt(), null, null, null, closed)

    private fun data(arrangement: String? = null) = BackupData(
        createdAt = 0, monthStartDay = 1, currency = "USD",
        accounts = listOf(
            acct(1, "Checking", "CHECKING", 10_000),
            acct(2, "Savings", "SAVINGS", 90_000),
            acct(3, "Visa", "CREDIT_CARD", 0, statement = 10, due = 28),
            acct(4, "Mortgage", "LOAN", -1_000_000),
            acct(5, "Old pot", "SAVINGS", 0, closed = 20_000),
            acct(6, "Gone", "CASH", 0, archived = true),
        ),
        people = listOf(BackupPerson(1, "Sam", false)),
        loans = listOf(BackupLoanEntry(1, 1, 2_500, 1_000, "Starting balance")),
        accountArrangement = arrangement,
    )

    private fun view(arrangement: String? = null): LedgerView {
        KassaCore.installDefaults()
        return LedgerView(data(arrangement), null)
    }

    private val day = 20_000

    private fun ids(v: LedgerView, key: String): List<Long> =
        v.accounts(false, day, "UTC").groups.first { it.key == key }.rows.map { it.id }

    // ---------- order ----------

    @Test
    fun withNoChoiceTheTabIsAToZAndSaysSo() {
        val a = view().accounts(false, day, "UTC")
        assertEquals("NAME", a.sort)
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_CARDS", "LOANS"), a.arrangeKeys)
        assertEquals("CASH_AND_BANK,CREDIT_CARDS,LOANS|CASH_AND_BANK=1,2;CREDIT_CARDS=3;LOANS=4", a.order)
    }

    @Test
    fun balanceOrdersAGroupLargestFirst() {
        val v = view()
        v.accountSort = "BALANCE"
        assertEquals(listOf(2L, 1L), ids(v, "CASH_AND_BANK"))
        assertEquals("BALANCE", v.accounts(false, day, "UTC").sort)
    }

    @Test
    fun aBackupsArrangementShowsAsYourOrderUntilAnotherSortIsPicked() {
        val v = view("LOANS,CASH_AND_BANK|CASH_AND_BANK=2,1")
        val a = v.accounts(false, day, "UTC")
        assertEquals("CUSTOM", a.sort)
        assertEquals(listOf("LOANS", "CASH_AND_BANK", "CREDIT_CARDS"), a.groups.map { it.key }.filter { it in a.arrangeKeys })
        assertEquals(listOf(2L, 1L), ids(v, "CASH_AND_BANK"))
        v.accountSort = "NAME"
        assertEquals(listOf(1L, 2L), ids(v, "CASH_AND_BANK"))
        assertEquals("NAME", v.accounts(false, day, "UTC").sort)
    }

    @Test
    fun yourOrderWithNothingArrangedIsAToZ() {
        val v = view()
        v.accountSort = "CUSTOM"
        assertEquals("NAME", v.accounts(false, day, "UTC").sort)
    }

    @Test
    fun movesGiveTheNewShownOrderAndStopAtTheEnds() {
        val shown = view().accounts(false, day, "UTC").order
        assertEquals("CREDIT_CARDS,CASH_AND_BANK,LOANS|CASH_AND_BANK=1,2;CREDIT_CARDS=3;LOANS=4", AccountOrderBridge.moveGroup(shown, "CREDIT_CARDS", -1))
        assertEquals(shown, AccountOrderBridge.moveGroup(shown, "CASH_AND_BANK", -1))
        assertEquals(shown, AccountOrderBridge.moveGroup(shown, "LOANS", 1))
        assertEquals("CASH_AND_BANK,CREDIT_CARDS,LOANS|CASH_AND_BANK=2,1;CREDIT_CARDS=3;LOANS=4", AccountOrderBridge.moveAccount(shown, "CASH_AND_BANK", 1, 1))
        assertEquals(shown, AccountOrderBridge.moveAccount(shown, "CASH_AND_BANK", 1, -1))
    }

    @Test
    fun theShownOrderIsReadBackAsKeysAndIds() {
        val v = view()
        val shown = v.accounts(false, day, "UTC").order
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_CARDS", "LOANS"), AccountOrderBridge.groupKeys(shown))
        assertEquals(listOf(1L, 2L), AccountOrderBridge.accountIds(shown, "CASH_AND_BANK"))
        assertEquals(emptyList(), AccountOrderBridge.accountIds(shown, "NOPE"))
        assertEquals(1, v.budgetMonthStart())
        assertEquals("Balance from Old tab", PhoneChoicesBridge.convertNote("Old tab"))
        assertEquals("Sam Lee", PhoneChoicesBridge.convertName(" Sam  Lee "))
    }

    @Test
    fun dropsUseSwiftUiDestinations() {
        val shown = view().accounts(false, day, "UTC").order
        // First group dropped before position 3 goes to the end; before its own or the next slot changes nothing.
        assertEquals("CREDIT_CARDS,LOANS,CASH_AND_BANK|CASH_AND_BANK=1,2;CREDIT_CARDS=3;LOANS=4", AccountOrderBridge.dropGroup(shown, 0, 3))
        assertEquals(shown, AccountOrderBridge.dropGroup(shown, 0, 1))
        assertEquals("LOANS,CASH_AND_BANK,CREDIT_CARDS|CASH_AND_BANK=1,2;CREDIT_CARDS=3;LOANS=4", AccountOrderBridge.dropGroup(shown, 2, 0))
        assertEquals("CASH_AND_BANK,CREDIT_CARDS,LOANS|CASH_AND_BANK=2,1;CREDIT_CARDS=3;LOANS=4", AccountOrderBridge.dropAccount(shown, "CASH_AND_BANK", 0, 2))
    }

    @Test
    fun aMovedOrderIsReadBackAsTheArrangement() {
        val moved = AccountOrderBridge.dropGroup(view().accounts(false, day, "UTC").order, 2, 0)
        val v = view(moved)
        assertEquals(listOf("LOANS", "CASH_AND_BANK", "CREDIT_CARDS"), v.accounts(false, day, "UTC").arrangeKeys)
    }

    @Test
    fun sortNamesAreInTheSheetsOrder() {
        assertEquals(listOf("NAME", "BALANCE", "DUE", "CUSTOM"), AccountOrderBridge.sortNames())
        assertEquals("NAME", AccountOrderBridge.sortOf("nope"))
    }

    // ---------- pinned tiles ----------

    @Test
    fun untilThePersonPicksOverviewShowsEveryTileUpToEight() {
        val v = view()
        val o = v.overview(false, day, "UTC", 1)
        assertEquals(listOf("Checking", "Mortgage", "Savings", "Visa"), o.pins.filter { !it.isPeople }.map { it.name })
        assertTrue(o.pins.last().isPeople)
        assertEquals(5, v.pinCount())
    }

    @Test
    fun pickedTilesAreTheOnlyOnesAndFollowAccountsOrder() {
        val v = view()
        v.pinnedKeys = "people,acct:2"
        val o = v.overview(false, day, "UTC", 1)
        assertEquals(listOf("Savings", ""), o.pins.map { it.name })
        assertTrue(o.pins.last().isPeople)
        v.pinnedKeys = ""
        assertTrue(v.overview(false, day, "UTC", 1).pins.isEmpty())
    }

    @Test
    fun theChoicesListEveryTileAndToggleSavesTheShownSet() {
        val v = view()
        val choices = v.pinChoices()
        assertEquals(listOf("acct:1", "acct:4", "acct:2", "acct:3", "people"), choices.map { it.key })
        assertTrue(choices.all { it.picked && it.enabled })
        assertEquals("Checking", choices.first().name)
        assertEquals("", choices.last().name)
        val after = v.pinToggled("acct:4")
        v.pinnedKeys = after
        assertEquals(listOf("acct:1", "acct:2", "acct:3", "people"), v.pinChoices().filter { it.picked }.map { it.key })
        assertEquals(4, v.pinCount())
    }

    @Test
    fun anArrangementOrdersTheTiles() {
        val v = view("LOANS,CREDIT_CARDS,CASH_AND_BANK|CASH_AND_BANK=2,1")
        assertEquals(listOf("Mortgage", "Visa", "Savings", "Checking"), v.overview(false, day, "UTC", 1).pins.filter { !it.isPeople }.map { it.name })
    }

    // ---------- phone choices ----------

    @Test
    fun choicesFallBackAndClamp() {
        assertEquals("SYSTEM", PhoneChoicesBridge.theme("???"))
        assertEquals("DARK", PhoneChoicesBridge.theme("DARK"))
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), PhoneChoicesBridge.themes())
        assertEquals("TODAY", PhoneChoicesBridge.homeTab("SETTINGS"))
        assertEquals(listOf("TODAY", "ACTIVITY", "INSIGHTS", "ACCOUNTS"), PhoneChoicesBridge.homeTabs())
        assertEquals(28, PhoneChoicesBridge.clampMonthStart(40))
        assertTrue(PhoneChoicesBridge.monthStartValid("15"))
        assertTrue(!PhoneChoicesBridge.monthStartValid("31"))
        assertEquals(listOf(1, 5, 10, 15, 20, 25), PhoneChoicesBridge.quickMonthDays())
        assertEquals(8, PhoneChoicesBridge.pinLimit())
    }

    // ---------- account to person ----------

    @Test
    fun onlyOpenPlainAccountsAreOfferedAToZWithWhoseMoneyItIs() {
        val list = view().convertibleAccounts(false)
        assertEquals(listOf("Checking", "Savings"), list.map { it.name })
        assertEquals(PersonSide.OwesYou, list.first().side)
        assertEquals(10_000L, view().convertBalance(1))
        assertEquals(listOf(PersonSide.Hidden, PersonSide.Hidden), view().convertibleAccounts(true).map { it.side })
    }

    @Test
    fun theNameAndTheAccountAreChecked() {
        val v = view()
        assertNull(v.convertProblem(1, "Lee"))
        assertEquals("Sam is already used.", v.convertProblem(1, "Sam"))
        assertNotNull(v.convertProblem(1, "  "))
        assertNotNull(v.convertProblem(3, "Lee"))
        assertNotNull(v.convertProblem(99, "Lee"))
    }
}
