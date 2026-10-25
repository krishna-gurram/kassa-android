package app.kassa.money.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.data.backup.BackupFile
import app.kassa.money.data.backup.BackupRepository
import app.kassa.money.data.db.AccountEntity
import app.kassa.money.data.db.BudgetEntity
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.GroupEntity
import app.kassa.money.data.db.HoldingEntity
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.LoanEntryEntity
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.RecurringEntity
import app.kassa.money.data.db.TradeEntity
import app.kassa.money.data.db.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A backup written from one database and restored into another leaves every table the same. */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: KassaDatabase
    private lateinit var target: KassaDatabase

    @Before
    fun open() {
        source = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()
    }

    @After
    fun close() {
        source.close(); target.close()
    }

    private fun repo(db: KassaDatabase) = BackupRepository(context, db, Settings(context))

    @Test
    fun everyTableSurvivesBackupAndRestore(): Unit = runBlocking {
        repo(source).restore(sample)
        for (password in listOf<CharArray?>(null, "a long password".toCharArray())) {
            val file = ByteArrayOutputStream().also { repo(source).write(it, password) }.toByteArray()
            val read = repo(target).read(ByteArrayInputStream(file), password)
            repo(target).restore(read)
            assertSameTables(source, target)
        }
    }

    @Test
    fun aProtectedBackupNeedsItsPassword(): Unit = runBlocking {
        repo(source).restore(sample)
        val file = ByteArrayOutputStream().also { repo(source).write(it, "a long password".toCharArray()) }.toByteArray()
        assertFailsWith<BackupFile.NeedsPassword> { repo(target).read(ByteArrayInputStream(file), null) }
        assertFailsWith<BackupFile.WrongPassword> { repo(target).read(ByteArrayInputStream(file), "not it at all".toCharArray()) }
    }

    @Test
    fun restoringReplacesWhatWasThere(): Unit = runBlocking {
        target.people().insertAll(listOf(PersonEntity(9, "Someone old", false)))
        repo(target).restore(sample)
        assertEquals(sample.people, target.people().all())
    }

    private suspend fun assertSameTables(a: KassaDatabase, b: KassaDatabase) {
        assertEquals(a.groups().all(), b.groups().all())
        assertEquals(a.accounts().all(), b.accounts().all())
        assertEquals(a.categories().all(), b.categories().all())
        assertEquals(a.transactions().all(), b.transactions().all())
        assertEquals(a.budgets().all(), b.budgets().all())
        assertEquals(a.people().all(), b.people().all())
        assertEquals(a.loans().all(), b.loans().all())
        assertEquals(a.recurring().all(), b.recurring().all())
        assertEquals(a.holdings().all(), b.holdings().all())
        assertEquals(a.trades().all(), b.trades().all())
    }

    /** Synthetic data shaped like a real ledger: every table, nullable columns both set and empty. */
    private val sample = BackupContents(
        createdAt = 1_760_000_000_000,
        monthStartDay = 1,
        currency = null,
        groups = listOf(GroupEntity(1, "Bank", 0)),
        accounts = listOf(
            AccountEntity(1, "Everyday checking", "CHECKING", 1, 150_000, null, null, null, null, false, 0, "mm-1", null),
            AccountEntity(2, "Travel card", "CREDIT_CARD", null, -42_000, 16, 10, 1, 500_000, false, 1, null, "1004"),
        ),
        categories = listOf(
            CategoryEntity(1, "Food", "EXPENSE", null, "emoji:🍜", -3_000_000, true, false, 0, null),
            CategoryEntity(2, "Groceries", "EXPENSE", 1, "emoji:🛒", -3_000_000, true, false, 0, "mm-c2"),
        ),
        transactions = listOf(
            TransactionEntity(1, "EXPENSE", 6_000, 2, null, 2, null, 1_760_000_100_000, "Corner market", 2_000, null),
            TransactionEntity(2, "EXPENSE", 3_000, null, null, 1, 1, 1_760_000_200_000, "Dinner, Sam paid", 1_500, null),
            TransactionEntity(3, "TRANSFER", 10_000, 1, 2, null, null, 1_760_000_300_000, "", null, "mm-t3"),
        ),
        budgets = listOf(BudgetEntity(1, 0, 40_000), BudgetEntity(1, 202610, 45_000)),
        people = listOf(PersonEntity(1, "Sam", false), PersonEntity(2, "Alex", true)),
        loans = listOf(
            LoanEntryEntity(1, 2, 4_000, 1_760_000_100_000, "Corner market", 1),
            LoanEntryEntity(2, 1, -1_500, 1_760_000_200_000, "Dinner, Sam paid", 2),
        ),
        bills = listOf(RecurringEntity(1, "Rent", 1, 1, 1, 120_000, true, 202609)),
        holdings = listOf(HoldingEntity(1, 1, "VTI", 2_500_000, 25_050, 1_760_000_000_000, 21_333), HoldingEntity(2, 1, "SCHD", 80_000_000, 2_750, 1_760_000_000_000)),
        trades = listOf(TradeEntity(1, 3, "VTI", 2_500_000, 25_050)),
        icons = emptyMap(),
    )
}
