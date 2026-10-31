package app.kassa.money.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.db.HoldingEntity
import app.kassa.money.data.db.InvestmentTransferEntity
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.LoanEntryEntity
import app.kassa.money.data.db.LoanPaymentEntity
import app.kassa.money.data.db.LoanTermChangeEntity
import app.kassa.money.data.db.LoanTermsEntity
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.RecurringEntity
import app.kassa.money.data.db.TradeEntity
import app.kassa.money.data.db.TransactionEntity
import app.kassa.money.data.repo.ImportRepository
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.importer.ImportPlan
import app.kassa.money.importer.Reconcile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Importing over existing data replaces everything that points at the old accounts and entries, in one go. */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: KassaDatabase

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test
    fun nothingIsLeftPointingAtDeletedAccountsOrEntries(): Unit = runBlocking {
        db.people().insertAll(listOf(PersonEntity(1, "Sam", false)))
        db.transactions().insertAll(listOf(TransactionEntity(1, "BUY", 5_000, 1, null, null, null, 1_760_000_000_000, "", null, null)))
        db.holdings().insertAll(listOf(HoldingEntity(1, 1, "VTI", 1_000_000, 25_000, 1_760_000_000_000)))
        db.trades().insertAll(listOf(TradeEntity(1, 1, "VTI", 1_000_000, 25_000)))
        db.investmentTransfers().insertAll(listOf(InvestmentTransferEntity(1, 1, 1, 5_000, 1_760_000_000_000)))
        db.loans().insertAll(
            listOf(
                LoanEntryEntity(1, 1, 2_000, 1_760_000_000_000, "split share", 1),
                LoanEntryEntity(2, 1, 900, 1_760_000_000_000, "starting balance", null),
            ),
        )
        db.recurring().insertAll(listOf(RecurringEntity(1, "Rent", 1, 1, 1, 100_000, true, 0)))
        db.loanTerms().insertAll(listOf(LoanTermsEntity(3, "REDUCING", false, 61_250, 58_000, 60, null, null, 20_758, 1, 0, 0, 0, null, null)))
        db.loanChanges().insertAll(listOf(LoanTermChangeEntity(1, 3, 21_000, 72_500, null, null, null)))
        db.loanPayments().insertAll(listOf(LoanPaymentEntity(1, 3, 20_758, 100, null, null, null, 0, 0, false, 0, false)))

        val account = Account(id = 10, name = "Checking", type = AccountType.CHECKING)
        val entry = Transaction(id = 10, kind = TxKind.INCOME, amount = 1_000, accountId = 10, epochMillis = 1_760_000_100_000)
        ImportRepository(db).replaceWith(
            ImportPlan(
                groups = emptyList(), accounts = listOf(account), categories = emptyList(), transactions = listOf(entry),
                budgets = emptyList(), issues = emptyList(), reconcile = Reconcile(emptyList(), emptyList()),
            ),
        )

        assertEquals(listOf(10L), db.accounts().all().map { it.id })
        assertEquals(listOf(10L), db.transactions().all().map { it.id })
        assertTrue(db.holdings().all().isEmpty())
        assertTrue(db.trades().all().isEmpty())
        assertTrue(db.investmentTransfers().all().isEmpty())
        assertTrue(db.recurring().all().isEmpty())
        assertTrue(db.loanTerms().all().isEmpty())
        assertTrue(db.loanChanges().all().isEmpty())
        assertTrue(db.loanPayments().all().isEmpty())
        // The person stays, with the starting balance that was never tied to an entry; the split share went with its entry.
        assertEquals(listOf("Sam"), db.people().all().map { it.name })
        assertEquals(listOf(2L), db.loans().all().map { it.id })
    }
}
