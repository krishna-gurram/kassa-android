package app.kassa.money.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.data.repo.EntryRepository
import app.kassa.money.data.repo.InvestmentRepository
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Buys and sales keep the cash entry, the trade and the holding in step, through edits and deletes. */
@RunWith(AndroidJUnit4::class)
class InvestmentRepositoryTest {
    private lateinit var db: KassaDatabase
    private lateinit var investing: InvestmentRepository
    private var brokerage = 0L

    @Before
    fun open(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KassaDatabase::class.java).build()
        investing = InvestmentRepository(db, EntryRepository(db))
        brokerage = db.accounts().insert(AccountEntity(0, "Brokerage", "INVESTMENT", null, 500_000, null, null, null, null, false, 0, null))
    }

    @After
    fun close() = db.close()

    private suspend fun vti() = db.holdings().find(brokerage, "VTI")?.toDomain()
    private suspend fun entriesOf(kind: TxKind) = db.transactions().all().map { it.toDomain() }.filter { it.kind == kind }

    @Test
    fun aBuyRecordsCashTradeAndHolding(): Unit = runBlocking {
        investing.buy(brokerage, "vti", 10_000_000, 20_000, at = 1_000, transferId = null)
        val entry = entriesOf(TxKind.BUY).single()
        assertEquals(200_000, entry.amount)
        assertEquals(1_000, entry.epochMillis)
        assertEquals(10_000_000, investing.tradeFor(entry.id)?.microShares)
        assertEquals(10_000_000, vti()?.microShares)
        assertEquals(20_000, vti()?.avgCost)
    }

    @Test
    fun editingABuyReplacesItOnTheHolding(): Unit = runBlocking {
        investing.buy(brokerage, "VTI", 10_000_000, 20_000, at = 1_000, transferId = null)
        investing.buy(brokerage, "VTI", 5_000_000, 24_000, at = 2_000, transferId = null)
        val second = entriesOf(TxKind.BUY).first { it.epochMillis == 2_000L }
        investing.editTrade(second, "VTI", 10_000_000, 22_000, at = 3_000)
        assertEquals(20_000_000, vti()?.microShares)
        assertEquals(21_000, vti()?.avgCost) // 10 at 200 + 10 at 220
        val edited = db.transactions().byId(second.id)!!.toDomain()
        assertEquals(220_000, edited.amount)
        assertEquals(3_000, edited.epochMillis)
    }

    @Test
    fun deletingTradesUndoesThem(): Unit = runBlocking {
        investing.buy(brokerage, "VTI", 10_000_000, 20_000, at = 1_000, transferId = null)
        investing.sell(brokerage, "VTI", 10_000_000, 25_000, at = 2_000)
        assertNull(vti())
        investing.deleteTrade(entriesOf(TxKind.SELL).single())
        assertEquals(10_000_000, vti()?.microShares)
        investing.deleteTrade(entriesOf(TxKind.BUY).single())
        assertNull(vti())
        assertTrue(db.transactions().all().isEmpty())
        assertTrue(db.trades().all().isEmpty())
    }

    @Test
    fun anOlderTradeOnlyMovesOrReturnsItsCash(): Unit = runBlocking {
        investing.buy(brokerage, "VTI", 10_000_000, 20_000, at = 1_000, transferId = null)
        // A buy from before trades were kept: an entry with no trade row.
        val oldId = db.transactions().insert(Transaction(kind = TxKind.BUY, amount = 50_000, accountId = brokerage, epochMillis = 500, note = "VTI").toEntity())
        val old = db.transactions().byId(oldId)!!.toDomain()
        assertFailsWith<IllegalArgumentException> { investing.editTrade(old, "VTI", 1_000_000, 50_000, at = 600) }
        investing.moveTrade(old, at = 700)
        assertEquals(700, db.transactions().byId(oldId)!!.epochMillis)
        investing.deleteTrade(old)
        assertEquals(10_000_000, vti()?.microShares)
        assertNull(db.transactions().byId(oldId))
    }

    @Test
    fun youCantSellWhatYouDontHold(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { investing.sell(brokerage, "VTI", 1_000_000, 25_000, at = 1) }
        investing.buy(brokerage, "VTI", 1_000_000, 20_000, at = 1, transferId = null)
        assertFailsWith<IllegalArgumentException> { investing.sell(brokerage, "VTI", 2_000_000, 25_000, at = 2) }
        assertTrue(entriesOf(TxKind.SELL).isEmpty())
    }
}
