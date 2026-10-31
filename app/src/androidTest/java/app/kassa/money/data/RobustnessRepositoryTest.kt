package app.kassa.money.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.repo.BillRepository
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.data.repo.EntryRepository
import app.kassa.money.data.repo.InvestmentRepository
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.TxKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour that lives in the database layer: waiting investment transfers follow their entry, closed accounts take nothing
 * new, a bill's approval is one step, and Activity search treats what is typed as text. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class RobustnessRepositoryTest {
    private lateinit var db: KassaDatabase
    private lateinit var entries: EntryRepository
    private lateinit var investing: InvestmentRepository
    private lateinit var bills: BillRepository
    private var checking = 0L
    private var savings = 0L
    private var brokerage = 0L
    private var otherBroker = 0L
    private var food = 0L

    @Before
    fun open(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KassaDatabase::class.java).build()
        entries = EntryRepository(db)
        investing = InvestmentRepository(db, entries)
        bills = BillRepository(db, entries)
        checking = account("Checking", "CHECKING", 500_000)
        savings = account("Savings", "SAVINGS", 0)
        brokerage = account("Brokerage", "INVESTMENT", 0)
        otherBroker = account("Second brokerage", "INVESTMENT", 0)
        food = db.categories().insert(CategoryEntity(0, "Food", "EXPENSE", null, "emoji:x", 0, true, false, 0, null))
    }

    @After
    fun close() = db.close()

    private suspend fun account(name: String, type: String, opening: Long) =
        db.accounts().insert(AccountEntity(name = name, type = type, groupId = null, openingBalance = opening, statementDay = null, dueDay = null, payFromId = null, creditLimit = null, archived = false, sortOrder = 0, externalId = null))

    private suspend fun close(id: Long) {
        val row = db.accounts().byId(id)!!
        db.accounts().update(row.copy(closedDay = 20_000))
    }

    private fun transfer(from: Long, to: Long, amount: Long = 10_000, at: Long = 1_000, id: Long? = null, note: String = "Move") = EntryDraft(
        id = id, kind = TxKind.TRANSFER, amount = amount, accountId = from, toAccountId = to, categoryId = null, personId = null,
        epochMillis = at, note = note,
    )

    private fun expense(account: Long, amount: Long = 2_500, note: String = "Lunch") = EntryDraft(
        id = null, kind = TxKind.EXPENSE, amount = amount, accountId = account, toAccountId = null, categoryId = food, personId = null,
        epochMillis = 2_000, note = note,
    )

    private suspend fun waiting() = db.investmentTransfers().all()

    // ---------- waiting investment transfers ----------

    @Test
    fun deletingATransferStopsItWaiting(): Unit = runBlocking {
        val tx = entries.save(transfer(checking, brokerage))
        investing.awaitExplanation(tx)
        assertEquals(1, waiting().size)
        entries.delete(tx.id)
        assertTrue(waiting().isEmpty())
    }

    @Test
    fun editingATransferUpdatesWhatIsWaiting(): Unit = runBlocking {
        val tx = entries.save(transfer(checking, brokerage))
        investing.awaitExplanation(tx)
        entries.save(transfer(checking, otherBroker, amount = 12_000, at = 5_000, id = tx.id))
        val row = waiting().single()
        assertEquals(tx.id, row.transactionId)
        assertEquals(otherBroker, row.accountId)
        assertEquals(12_000L, row.amount)
        assertEquals(5_000L, row.epochMillis)
    }

    @Test
    fun editingATransferToAnotherKindOfAccountStopsItWaiting(): Unit = runBlocking {
        val tx = entries.save(transfer(checking, brokerage))
        investing.awaitExplanation(tx)
        entries.save(transfer(checking, savings, id = tx.id))
        assertTrue(waiting().isEmpty())
    }

    @Test
    fun editingAnEntryThatWasNeverWaitingAddsNothing(): Unit = runBlocking {
        val tx = entries.save(transfer(checking, savings))
        entries.save(transfer(checking, brokerage, id = tx.id))
        assertTrue(waiting().isEmpty())
    }

    // ---------- closed accounts ----------

    @Test
    fun aClosedAccountTakesNoNewEntry(): Unit = runBlocking {
        close(savings)
        val e = assertFailsWith<IllegalArgumentException> { entries.save(expense(savings)) }
        assertEquals("Savings is closed. Reopen it first to add to it.", e.message)
        assertTrue(db.transactions().all().isEmpty())
    }

    @Test
    fun aTransferInOrOutOfAClosedAccountIsRefused(): Unit = runBlocking {
        close(savings)
        assertFailsWith<IllegalArgumentException> { entries.save(transfer(checking, savings)) }
        assertFailsWith<IllegalArgumentException> { entries.save(transfer(savings, checking)) }
        assertTrue(db.transactions().all().isEmpty())
    }

    @Test
    fun anOldEntryOnAClosedAccountCanStillBeEditedAndDeleted(): Unit = runBlocking {
        val tx = entries.save(expense(savings))
        close(savings)
        entries.save(expense(savings, amount = 3_000).copy(id = tx.id))
        assertEquals(3_000L, db.transactions().byId(tx.id)!!.amount)
        entries.delete(tx.id)
        assertNull(db.transactions().byId(tx.id))
    }

    @Test
    fun movingAnOldEntryOntoAClosedAccountIsRefused(): Unit = runBlocking {
        val tx = entries.save(expense(checking))
        close(savings)
        assertFailsWith<IllegalArgumentException> { entries.save(expense(savings).copy(id = tx.id)) }
        assertEquals(checking, db.transactions().byId(tx.id)!!.accountId)
    }

    @Test
    fun aBuyOrSaleOnAClosedInvestmentAccountIsRefused(): Unit = runBlocking {
        close(brokerage)
        assertFailsWith<IllegalArgumentException> { investing.buy(brokerage, "VTI", 1_000_000, 10_000, at = 1_000, transferId = null) }
        assertFailsWith<IllegalArgumentException> { investing.sell(brokerage, "VTI", 1_000_000, 10_000, at = 1_000) }
        assertTrue(db.transactions().all().isEmpty())
    }

    @Test
    fun aNewBalanceCorrectionOnAClosedAccountIsRefused(): Unit = runBlocking {
        close(checking)
        assertFailsWith<IllegalArgumentException> { entries.correctBalance(checking, 400_000, 1_000) }
        assertTrue(db.transactions().all().isEmpty())
    }

    @Test
    fun approvingABillIntoAClosedAccountIsRefusedAndLeavesTheBillWaiting(): Unit = runBlocking {
        bills.save(Recurring(name = "Rent", categoryId = food, accountId = savings, dayOfMonth = 1, fixedAmount = 1_000))
        val bill = bills.bills.first().single()
        close(savings)
        assertFailsWith<IllegalArgumentException> { bills.approve(bill, expense(savings, 1_000, "Rent"), 202611) }
        assertTrue(db.transactions().all().isEmpty())
        assertEquals(0, bills.bills.first().single().handledMonth)
    }

    // ---------- approving a bill ----------

    @Test
    fun approvingABillSavesTheEntryAndMarksItHandledTogether(): Unit = runBlocking {
        bills.save(Recurring(name = "Gym", categoryId = food, accountId = checking, dayOfMonth = 3, fixedAmount = 4_000))
        val bill = bills.bills.first().single()
        bills.approve(bill, expense(checking, 4_000, "Gym"), 202611)
        assertEquals(1, db.transactions().all().size)
        assertEquals(202611, bills.bills.first().single().handledMonth)
    }

    @Test
    fun approvingABillThatWasDeletedAddsNoEntry(): Unit = runBlocking {
        bills.save(Recurring(name = "Gym", categoryId = food, accountId = checking, dayOfMonth = 3, fixedAmount = 4_000))
        val bill = bills.bills.first().single()
        bills.delete(bill.id)
        val e = assertFailsWith<IllegalArgumentException> { bills.approve(bill, expense(checking, 4_000, "Gym"), 202611) }
        assertEquals("That bill is no longer there.", e.message)
        assertTrue(db.transactions().all().isEmpty())
    }

    // ---------- Activity search ----------

    @Test
    fun searchTreatsPercentAndUnderscoreAsPlainText(): Unit = runBlocking {
        entries.save(expense(checking, note = "50% off sale"))
        entries.save(expense(checking, note = "50 percent"))
        entries.save(expense(checking, note = "a_b"))
        entries.save(expense(checking, note = "axb"))
        entries.save(expense(checking, note = "back\\slash"))
        assertEquals(listOf("50% off sale"), entries.search("50%").first().map { it.note })
        assertEquals(listOf("a_b"), entries.search("a_b").first().map { it.note })
        assertEquals(listOf("50% off sale"), entries.search("%").first().map { it.note })
        assertEquals(listOf("back\\slash"), entries.search("\\").first().map { it.note })
    }
}
