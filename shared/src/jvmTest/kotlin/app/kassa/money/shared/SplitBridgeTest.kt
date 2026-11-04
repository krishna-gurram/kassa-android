package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBuilder
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the iOS entry page asks of the split rules through :shared. The rules themselves are tested in :domain (`SplitRulesTest`). */
class SplitBridgeTest {

    private val at = 1_767_268_800_000L

    private fun input(amount: Long = 12_000, kind: String = "EXPENSE", account: Long = 1, split: SplitState = SplitBridge.none(), note: String = "Dinner") =
        EntryInput(0, kind, amount, account, 0, 1, at, note, split)

    private fun cells(i: EntryInput): Map<String, String?> {
        val names = BackupRows.columns("transactions")
        return names.indices.associate { names[it] to EntryBridge.cell(i, it) }
    }

    private fun pick(vararg ids: Long): SplitState {
        var s = SplitBridge.none()
        for (id in ids) s = SplitBridge.toggle(s, id)
        return s
    }

    @Test
    fun anEqualSplitWhereYouPaidStoresYourShareAndWhatEachOwes() {
        KassaCore.installDefaults()
        val i = input(split = pick(1, 2))
        assertNull(EntryBridge.problem(i, "USD"))
        val c = cells(i)
        assertEquals("1", c["accountId"])
        assertNull(c["personId"])
        assertEquals("4000", c["myShare"])
        assertEquals(listOf(SplitOwedData(1, 4000), SplitOwedData(2, 4000)), EntryBridge.owed(i))
    }

    @Test
    fun exactSharesStoreWhatWasTyped() {
        KassaCore.installDefaults()
        var s = SplitBridge.useExact(pick(1, 2), 12_000)
        s = SplitBridge.typedShare(s, 1, "50")
        s = SplitBridge.typedShare(s, 2, "40")
        val i = input(split = s)
        assertEquals("3000", cells(i)["myShare"])
        assertEquals(listOf(SplitOwedData(1, 5000), SplitOwedData(2, 4000)), EntryBridge.owed(i))
    }

    @Test
    fun sharesThatAddUpToMoreAreRefusedOnTheSplitField() {
        KassaCore.installDefaults()
        var s = SplitBridge.useExact(pick(1, 2), 12_000)
        s = SplitBridge.typedShare(s, 1, "70")
        s = SplitBridge.typedShare(s, 2, "60")
        assertEquals(FieldProblemData("SPLIT", "The shares add up to more than $120.00."), EntryBridge.problem(input(split = s), "USD"))
        assertEquals("The shares add up to more than $120.00.", SplitBridge.problem(s, "EXPENSE", 12_000, "USD"))
        assertEquals("", SplitBridge.problem(s, "INCOME", 12_000, "USD"), "only an expense is split")
        assertEquals("AMOUNT", EntryBridge.problem(input(amount = 0, split = s), "USD")?.field, "an earlier problem comes first")
    }

    @Test
    fun whenSomeoneElsePaidTheEntryMovesNoAccountOfYours() {
        KassaCore.installDefaults()
        val s = SplitBridge.payer(pick(1, 2), 2)
        val i = input(amount = 9_000, account = 0, split = s)
        assertNull(EntryBridge.problem(i, "USD"), "no account needed")
        val c = cells(i)
        assertNull(c["accountId"])
        assertEquals("2", c["personId"])
        assertEquals("3000", c["myShare"])
        assertEquals(listOf(SplitOwedData(2, -3000)), EntryBridge.owed(i))
        assertEquals(emptyList(), EntryBridge.mustBeOpen(i, 0, 0), "no account to be open")
        assertEquals("ACCOUNT", EntryBridge.missing("EXPENSE", 1, 0, 0), "without the split an account is missing")
        assertEquals("", EntryBridge.missing("EXPENSE", 1, 0, 0, s))
        assertEquals("ACCOUNT", EntryBridge.missing("EXPENSE", 1, 0, 0, pick(1, 2)))
    }

    @Test
    fun anEntryThatIsNotAnExpenseOrNotSplitCarriesNoSplit() {
        KassaCore.installDefaults()
        val income = cells(input(kind = "INCOME", split = SplitBridge.payer(pick(1), 1)))
        assertNull(income["myShare"])
        assertNull(income["personId"])
        assertEquals("1", income["accountId"])
        val plain = cells(input())
        assertNull(plain["myShare"])
        assertNull(plain["personId"])
        assertEquals(emptyList(), EntryBridge.owed(input()))
    }

    @Test
    fun theStatePassesThroughEachChange() {
        val s = pick(3, 1)
        assertEquals("3,1", s.people)
        assertTrue(SplitBridge.isSelected(s, 3))
        assertFalse(SplitBridge.isSelected(s, 2))
        assertTrue(SplitBridge.isShared(s, "EXPENSE"))
        assertFalse(SplitBridge.isShared(s, "TRANSFER"))
        val paid = SplitBridge.payer(s, 1)
        assertEquals(1L, paid.paidBy)
        assertTrue(SplitBridge.someoneElsePaid(paid, "EXPENSE"))
        assertEquals(0L, SplitBridge.payer(paid, 0).paidBy)
        assertEquals(0L, SplitBridge.toggle(paid, 1).paidBy, "taking the payer out clears them")
        assertEquals(0L, SplitBridge.payer(s, 9).paidBy, "a payer must be in the split")
        assertFalse(SplitBridge.useEqual(SplitBridge.useExact(s, 1000)).exact)
    }

    @Test
    fun theViewListsYouFirstThenEachPersonWithPercentsAndTheOweLine() {
        KassaCore.installDefaults()
        val v = SplitBridge.view(SplitBridge.payer(pick(1, 2), 2), "EXPENSE", 10_001, "USD")
        assertTrue(v.shared)
        assertTrue(v.someoneElsePaid)
        assertEquals(listOf(0L, 1L, 2L), v.lines.map { it.personId })
        assertEquals(listOf("$33.34", "$33.34", "$33.33"), v.lines.map { it.amountText })
        assertEquals(listOf("33%", "33%", "33%"), v.lines.map { it.percentText })
        assertEquals("$33.34", v.myShareText)
        assertEquals("$33.34", v.payerOwedText)
        assertEquals("", v.problem)
        val over = SplitBridge.view(SplitBridge.typedShare(SplitBridge.useExact(pick(1), 1000), 1, "20"), "EXPENSE", 1000, "USD")
        assertTrue(over.myShareNegative)
        assertTrue(over.lines.first().negative)
        assertEquals("$0.00", over.payerOwedText)
        assertEquals("The shares add up to more than $10.00.", over.problem)
        val none = SplitBridge.view(SplitBridge.none(), "EXPENSE", 1000, "USD")
        assertFalse(none.shared)
        assertEquals(emptyList(), none.lines)
    }

    @Test
    fun theViewIsNeverMaskedAndUsesTheLedgersCurrency() {
        KassaCore.installDefaults()
        app.kassa.money.domain.Money.hideAmounts = { true }
        try {
            assertEquals("£5.00", SplitBridge.view(pick(1), "EXPENSE", 1000, "GBP").myShareText)
        } finally {
            app.kassa.money.domain.Money.hideAmounts = { false }
        }
    }

    @Test
    fun theStateSurvivesBeingEncodedAndDecoded() {
        val s = SplitBridge.payer(SplitBridge.typedShare(SplitBridge.useExact(pick(1, 2), 12_000), 2, "7"), 2)
        val back = SplitBridge.decode(s.encoded)
        assertEquals(s.encoded, back.encoded)
        assertEquals("", SplitBridge.decode("garbage").people)
        assertEquals("", SplitBridge.decode("").encoded.substringBefore("|"))
        assertEquals(0L, SplitBridge.decode("1,2|0||9").paidBy, "a payer outside the split is dropped")
        assertEquals("1", SplitBridge.decode("1,1,x,-4|0||0").people, "duplicates and junk are dropped")
    }

    // ---------- reopening, the people list and "Split a transaction" ----------

    private fun acct(id: Long, name: String, type: String) =
        BackupAccount(id, name, type, null, 0, null, null, null, null, false, id.toInt(), null, null, null, null)

    private fun cat(id: Long, name: String) = BackupCategory(id, name, "EXPENSE", null, "symbol:cart", 0x112233, true, false, id.toInt())

    private fun tx(id: Long, from: Long?, at: Long, person: Long? = null, share: Long? = null, amount: Long = 9_000) =
        BackupTransaction(id, "EXPENSE", amount, from, null, 1, person, at, "n$id", share)

    private val day = 20_522
    private val noon = day * 86_400_000L + 43_200_000L

    private fun data() = BackupData(
        createdAt = 1, monthStartDay = 1, currency = "USD",
        accounts = listOf(acct(1, "Checking", "CHECKING")),
        categories = listOf(cat(1, "Groceries")),
        people = listOf(BackupPerson(1, "Zed", false), BackupPerson(2, "amy", false), BackupPerson(3, "Old", true)),
        transactions = listOf(
            tx(10, 1, noon, share = 3_000),
            tx(11, null, noon, person = 2, share = 3_000),
            tx(12, 1, noon),
            tx(13, 1, noon - 70L * 86_400_000L),
            tx(14, 1, noon - 60L * 86_400_000L),
        ),
        loans = listOf(BackupLoanEntry(1, 1, 3_000, noon, "n10", 10), BackupLoanEntry(2, 2, 3_000, noon, "n10", 10), BackupLoanEntry(3, 2, -3_000, noon, "n11", 11)),
    )

    @Test
    fun anEditedSplitReopensWithItsPeopleAndSharesAndAPaidByEntryWithItsPayer() {
        KassaCore.installDefaults()
        val v = LedgerView(data(), null)
        val split = v.splitStart(10, 0)
        assertEquals("1,2", split.people)
        assertFalse(split.exact)
        assertEquals(0L, split.paidBy)
        val paid = v.splitStart(11, 0)
        assertEquals(2L, paid.paidBy)
        assertEquals("2", paid.people)
        assertEquals("", v.splitStart(12, 0).people)
        assertEquals("3", v.splitStart(12, 3).people)
        assertEquals("", v.splitStart(99, 0).people)
        assertEquals(10L, v.entryEdit(10)?.id)
        assertEquals(11L, v.entryEdit(11)?.id)
    }

    @Test
    fun thePeopleListIsAToZAndArchivedOnlyWhenInTheSplit() {
        KassaCore.installDefaults()
        val v = LedgerView(data(), null)
        assertEquals(listOf("amy", "Zed"), v.splitPeople("").map { it.name })
        assertEquals(listOf("amy", "Old", "Zed"), v.splitPeople("3").map { it.name })
        assertTrue(v.hasSplitPeople())
        assertFalse(LedgerView(data().copy(people = listOf(BackupPerson(1, "Old", true))), null).hasSplitPeople())
        assertEquals("amy", v.personName(2))
        assertEquals("", v.personName(9))
    }

    @Test
    fun splitATransactionOffersRecentExpensesYouPaidNotAlreadySplitWithThem() {
        KassaCore.installDefaults()
        val v = LedgerView(data(), null)
        val ids = { person: Long -> v.splitCandidates(person, false, day, "UTC").map { it.entry.id } }
        assertEquals(setOf(10L, 12L, 14L), ids(3).toSet(), "recent expenses you paid, 60 days back included")
        assertFalse(13L in ids(3), "70 days is too old")
        assertFalse(11L in ids(3), "someone else paid it")
        assertFalse(10L in ids(1), "already split with Zed")
        assertTrue(12L in ids(1))
        assertFalse(10L in ids(2))
    }

    // ---------- the backup keeps the shares ----------

    @Test
    fun aSplitSurvivesTheBackupFileAndTheCellsOfTheDatabase() {
        val d = data()
        val viaJson = BackupJson.parse(BackupJson.write(d))
        assertEquals(d, viaJson)
        assertEquals(3_000L, viaJson.transactions.first { it.id == 10L }.myShare)
        assertEquals(2L, viaJson.transactions.first { it.id == 11L }.personId)
        assertEquals(listOf(10L, 10L, 11L), viaJson.loans.map { it.txId })
        val builder = BackupBuilder(d.createdAt, d.monthStartDay, d.currency, d.accountArrangement)
        for (table in BackupRows.tableNames()) {
            val columns = BackupRows.columns(table).size
            for (row in 0 until BackupRows.rowCount(d, table)) {
                for (column in 0 until columns) builder.setCell(table, column, BackupRows.cell(d, table, row, column))
                builder.endRow(table)
            }
        }
        assertEquals(d, builder.build())
    }
}
