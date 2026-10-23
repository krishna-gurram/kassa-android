package dev.krishnagurram.kassa.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.krishnagurram.kassa.data.db.AccountEntity
import dev.krishnagurram.kassa.data.db.CategoryEntity
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.PersonEntity
import dev.krishnagurram.kassa.data.repo.EntryDraft
import dev.krishnagurram.kassa.data.repo.EntryRepository
import dev.krishnagurram.kassa.domain.TxKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving, editing and deleting entries with splits, on a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class EntryRepositoryTest {
    private lateinit var db: KassaDatabase
    private lateinit var entries: EntryRepository
    private var card = 0L
    private var food = 0L
    private var sam = 0L
    private var alex = 0L

    @Before
    fun open(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KassaDatabase::class.java).build()
        entries = EntryRepository(db)
        card = db.accounts().insert(AccountEntity(0, "Travel card", "CREDIT_CARD", null, 0, 16, 10, null, null, false, 0, null))
        food = db.categories().insert(CategoryEntity(0, "Food", "EXPENSE", null, "emoji:🍜", 0, true, false, 0, null))
        sam = db.people().insert(PersonEntity(0, "Sam", false))
        alex = db.people().insert(PersonEntity(0, "Alex", false))
    }

    @After
    fun close() = db.close()

    private fun dinner(amount: Long, splitWith: List<Long> = emptyList(), shares: Map<Long, Long>? = null, paidBy: Long? = null, id: Long? = null) = EntryDraft(
        id = id, kind = TxKind.EXPENSE, amount = amount, accountId = if (paidBy == null) card else null, toAccountId = null,
        categoryId = food, personId = null, epochMillis = 1_760_000_000_000, note = "Dinner",
        splitWith = splitWith, shares = shares, paidBy = paidBy,
    )

    private suspend fun owed(txId: Long) = entries.sharesFor(txId)

    @Test
    fun anEqualSplitYouPaidLeavesEachPersonOwingTheirShare(): Unit = runBlocking {
        val tx = entries.save(dinner(9_000, splitWith = listOf(sam, alex)))
        assertEquals(3_000, tx.myShare)
        assertEquals(card, tx.accountId)
        assertEquals(mapOf(sam to 3_000L, alex to 3_000L), owed(tx.id))
    }

    @Test
    fun exactSharesLeaveYouTheRest(): Unit = runBlocking {
        val tx = entries.save(dinner(3_000, splitWith = listOf(sam, alex), shares = mapOf(sam to 1_000L, alex to 500L)))
        assertEquals(1_500, tx.myShare)
        assertEquals(mapOf(sam to 1_000L, alex to 500L), owed(tx.id))
    }

    @Test
    fun whenSomeoneElsePaidYouOweThemYourShareAndNoAccountMoves(): Unit = runBlocking {
        val tx = entries.save(dinner(3_000, splitWith = listOf(sam), paidBy = sam))
        assertNull(tx.accountId)
        assertEquals(sam, tx.personId)
        assertEquals(1_500, tx.myShare)
        assertEquals(mapOf(sam to -1_500L), owed(tx.id))
    }

    @Test
    fun editingASplitRewritesWhoOwesWhat(): Unit = runBlocking {
        val first = entries.save(dinner(9_000, splitWith = listOf(sam, alex)))
        entries.save(dinner(5_000, splitWith = listOf(sam), id = first.id))
        assertEquals(mapOf(sam to 2_500L), owed(first.id))

        val unsplit = entries.save(dinner(5_000, id = first.id))
        assertNull(unsplit.myShare)
        assertTrue(owed(first.id).isEmpty())
        assertEquals(1, db.transactions().all().size)
    }

    @Test
    fun switchingWhoPaidOnAnEditUpdatesTheAccountAndTheDebt(): Unit = runBlocking {
        val first = entries.save(dinner(4_000, splitWith = listOf(sam)))
        val edited = entries.save(dinner(4_000, splitWith = listOf(sam), paidBy = sam, id = first.id))
        assertNull(edited.accountId)
        assertEquals(mapOf(sam to -2_000L), owed(first.id))
    }

    @Test
    fun deletingAnEntryRemovesItsSplit(): Unit = runBlocking {
        val tx = entries.save(dinner(9_000, splitWith = listOf(sam, alex)))
        entries.delete(tx.id)
        assertNull(entries.get(tx.id))
        assertTrue(db.loans().all().isEmpty())
    }

    @Test
    fun entriesThatBreakTheRulesAreRefused(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { entries.save(dinner(0)) }
        assertFailsWith<IllegalArgumentException> { entries.save(dinner(3_000, splitWith = listOf(sam), shares = mapOf(sam to 3_001L))) }
        assertFailsWith<IllegalArgumentException> { entries.save(dinner(3_000).copy(note = "x".repeat(121))) }
        assertFailsWith<IllegalArgumentException> { entries.save(dinner(3_000).copy(accountId = null)) }
        assertTrue(db.transactions().all().isEmpty())
    }
}
