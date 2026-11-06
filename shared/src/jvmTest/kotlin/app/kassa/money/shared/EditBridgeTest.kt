package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the iOS edit forms ask of :shared for an account, a card and a person. The rules themselves are tested in :domain. */
class EditBridgeTest {

    private fun acct(id: Long, name: String, type: String, opening: Long, statement: Int? = null, due: Int? = null, payFrom: Long? = null, closed: Int? = null, archived: Boolean = false) =
        BackupAccount(id, name, type, null, opening, statement, due, payFrom, null, archived, id.toInt(), null, null, null, closed)

    private fun tx(id: Long, kind: String, amount: Long, from: Long?, to: Long? = null) =
        BackupTransaction(id, kind, amount, from, to, null, null, 43_200_000L * (id + 1), "")

    private fun data() = BackupData(
        createdAt = 0, monthStartDay = 1, currency = "USD",
        accounts = listOf(
            acct(1, "Checking", "CHECKING", 100_000),
            acct(2, "Savings", "SAVINGS", 50_000),
            acct(3, "Visa", "CREDIT_CARD", 0, statement = 10, due = 28, payFrom = 1),
            acct(4, "Mortgage", "LOAN", -1_000_000),
            acct(5, "Old pot", "SAVINGS", 0, closed = 20_000),
            acct(6, "Gone", "CASH", 0, archived = true),
        ),
        transactions = listOf(tx(1, "INCOME", 50_000, 1), tx(2, "EXPENSE", 20_000, 3)),
        people = listOf(BackupPerson(1, "Sam", false), BackupPerson(2, "Ann", false), BackupPerson(3, "Old friend", true)),
        loans = listOf(
            BackupLoanEntry(1, 1, 2_500, 1_000, "Starting balance"),
            BackupLoanEntry(2, 2, -7_000, 1_000, "Starting balance"),
            BackupLoanEntry(3, 2, 500, 2_000, "lunch", 1),
        ),
    )

    private fun view(): LedgerView {
        KassaCore.installDefaults()
        return LedgerView(data(), null)
    }

    private fun input(
        type: String, name: String, balance: String, last4: String = "", statement: String = "", due: String = "", limit: String = "", payFrom: Long = 0,
    ) = AccountInput(type, name, last4, balance, statement, due, limit, payFrom)

    private fun col(name: String) = BackupRows.columns("accounts").indexOf(name)

    // ---------- accounts ----------

    @Test
    fun theFormOpensOnWhatIsStoredAndTheBalanceToday() {
        val v = view()
        val e = v.accountEdit(1)
        assertTrue(e.found)
        assertEquals("CHECKING", e.input.type)
        assertEquals("Checking", e.input.name)
        // 100,000 opening + 50,000 income = 1,500.00 today.
        assertEquals("1500", e.input.balance)
        assertFalse(e.closed)
        assertTrue("CREDIT_CARD" in e.kinds)
        assertFalse("LOAN" in e.kinds)
        val card = v.accountEdit(3)
        assertEquals("10", card.input.statementDay)
        assertEquals("28", card.input.dueDay)
        assertEquals(1L, card.input.payFromId)
        // A debt is shown as what is owed.
        assertEquals("200", card.input.balance)
        assertTrue(v.accountEdit(4).kinds.isEmpty())
        assertFalse(v.accountEdit(99).found)
    }

    @Test
    fun aNewBalanceMovesTheStartingBalanceByTheDifference() {
        val v = view()
        val edited = input("CHECKING", "  Main  checking ", "1600")
        assertEquals(emptyList(), v.accountEditProblems(1, edited))
        assertEquals("110000", v.editedAccountCell(1, edited, col("openingBalance")))
        assertEquals("Main checking", v.editedAccountCell(1, edited, col("name")))
        assertEquals("CHECKING", v.editedAccountCell(1, edited, col("type")))
        assertNull(v.editedAccountCell(1, edited, col("statementDay")))
        // A card's balance is typed as what is owed.
        val card = input("CREDIT_CARD", "Visa", "300", statement = "10", due = "28", payFrom = 1)
        // Owed 200.00 today, typed 300.00: the starting balance moves by 100.00 more owed.
        assertEquals("-10000", v.editedAccountCell(3, card, col("openingBalance")))
        assertEquals("1", v.editedAccountCell(3, card, col("payFromId")))
    }

    @Test
    fun anEditOwnsOnlyTheFormsColumns() {
        val names = BackupRows.columns("accounts")
        val owned = names.filterIndexed { i, _ -> EditBridge.ownsAccountColumn(i) }
        assertEquals(
            listOf("name", "type", "openingBalance", "statementDay", "dueDay", "payFromId", "creditLimit", "last4", "maturityDay"),
            owned,
        )
        assertFalse(EditBridge.ownsAccountColumn(-1))
    }

    @Test
    fun theFirstProblemIsAndroidsSentence() {
        val v = view()
        assertEquals(
            listOf(FieldProblemData("NAME", "savings is already used.")),
            v.accountEditProblems(1, input("CHECKING", "savings", "1500")),
        )
        // An account may keep its own name, in any case.
        assertEquals(emptyList(), v.accountEditProblems(1, input("CHECKING", "CHECKING", "1500")))
        assertEquals(FieldProblemData("NAME", "Give the account a name."), v.accountEditProblems(1, input("CHECKING", " ", "1500")).first())
        // A card needs both days.
        val noDays = v.accountEditProblems(3, input("CREDIT_CARD", "Visa", "200", statement = "", due = ""))
        assertEquals(listOf("STATEMENT_DAY", "DUE_DAY"), noDays.map { it.field })
        assertEquals(emptyList(), v.accountEditProblems(99, input("CHECKING", "x", "")).filter { it.field != "" })
        assertEquals("That account is no longer here.", v.accountEditProblems(99, input("CHECKING", "x", "")).first().message)
    }

    @Test
    fun aCardCantBePaidFromItselfAndALoanKeepsItsKind() {
        val v = view()
        assertEquals(
            FieldProblemData("PAY_FROM", "A card can't be paid from itself."),
            v.accountEditProblems(3, input("CREDIT_CARD", "Visa", "200", statement = "10", due = "28", payFrom = 3)).first(),
        )
        assertEquals("NAME", v.accountEditProblems(4, input("CHECKING", "Mortgage", "10000")).first().field)
        assertEquals(emptyList(), v.accountEditProblems(4, input("LOAN", "Mortgage", "10000")))
        // A checking account becomes a card once it has its days.
        assertEquals(emptyList(), v.accountEditProblems(2, input("CREDIT_CARD", "Savings", "0", statement = "5", due = "25")))
    }

    @Test
    fun aClosedAccountRefusesANewBalanceButKeepsRenames() {
        val v = view()
        assertTrue(v.accountEdit(5).closed)
        assertEquals(
            FieldProblemData("BALANCE", "Old pot is closed. Reopen it first to add to it."),
            v.accountEditProblems(5, input("SAVINGS", "Old pot", "10")).first(),
        )
        assertEquals(emptyList(), v.accountEditProblems(5, input("SAVINGS", "Old pot 2", "")))
    }

    @Test
    fun problemsAreWordedInTheLedgersCurrencyAndNeverMasked() {
        val v = view()
        Money.hideAmounts = { true }
        try {
            val message = v.accountEditProblems(1, input("CHECKING", "Checking", "999999999")).first().message
            assertTrue(message.contains("$"), message)
            assertTrue(Money.isHidden)
        } finally {
            Money.hideAmounts = { false }
        }
    }

    // ---------- people ----------

    @Test
    fun thePersonFormOpensOnTheStartingBalance() {
        val v = view()
        val sam = v.personEdit(1, false)
        assertTrue(sam.found)
        assertEquals("Sam", sam.name)
        assertTrue(sam.theyOwe)
        assertEquals("25", sam.amount)
        assertTrue(sam.archiveBlocked)
        // Ann was added owing 70.00; a split line (tied to an entry) is not part of the starting balance.
        val ann = v.personEdit(2, false)
        assertFalse(ann.theyOwe)
        assertEquals("70", ann.amount)
        assertEquals(2_500L, v.startingBalanceOf(1))
        assertEquals(-7_000L, v.startingBalanceOf(2))
        assertFalse(v.personEdit(99, false).found)
    }

    @Test
    fun theSizeThatBlocksArchivingIsMaskedWhileHidden() {
        val v = view()
        assertEquals("$25.00", v.personEdit(1, false).openSizeText)
        assertEquals(Money.MASK, v.personEdit(1, true).openSizeText)
        assertTrue(v.personArchiveBlocked(1))
    }

    @Test
    fun namesTheOthersUseAreTheOnesRefused() {
        val v = view()
        assertEquals(listOf("Ann", "Old friend"), v.otherPersonNames(1))
        assertEquals(listOf("Checking", "Savings", "Visa", "Mortgage", "Old pot", "Gone").filter { it != "Savings" }, v.otherAccountNames(2))
        assertTrue(EditBridge.startingChanged(2_500, 0))
        assertFalse(EditBridge.startingChanged(2_500, 2_500))
    }

    // ---------- archived groups ----------

    @Test
    fun archivedAccountsAndPeopleAreListedFoldedAtTheEnd() {
        val a = view().accounts(false, 20_473, "UTC")
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_CARDS", "LOANS", "PEOPLE", "CLOSED", "ARCHIVED", "ARCHIVED_PEOPLE"), a.groups.map { it.key })
        assertEquals(listOf("Gone"), a.groups.first { it.key == "ARCHIVED" }.rows.map { it.name })
        assertEquals(listOf("Old friend"), a.groups.first { it.key == "ARCHIVED_PEOPLE" }.rows.map { it.name })
        // An archived person is out of the People group and its totals.
        assertEquals(listOf("Ann", "Sam"), a.groups.first { it.key == "PEOPLE" }.rows.map { it.name })
        // An archived account is out of every total.
        assertFalse(a.groups.first { it.key == "CASH_AND_BANK" }.rows.any { it.name == "Gone" })
    }
}
