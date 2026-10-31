package app.kassa.money.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.backup.BackupRepository
import app.kassa.money.data.db.CategoryEntity
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.repo.AccountRepository
import app.kassa.money.data.repo.CategoryRepository
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.data.repo.EntryRepository
import app.kassa.money.data.repo.LoanPaymentDraft
import app.kassa.money.data.repo.LoanRepository
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.LoanCursor
import app.kassa.money.domain.LoanDates
import app.kassa.money.domain.LoanEngine
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.TxKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Loan payments, term changes and balance corrections on a real (in-memory) database, with synthetic data: a loan of
 * 21,480.00 at 6% with a payment of 580.00, paid from a checking account.
 */
@RunWith(AndroidJUnit4::class)
class LoanRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: KassaDatabase
    private lateinit var entries: EntryRepository
    private lateinit var loans: LoanRepository
    private var checking = 0L
    private var loan = 0L

    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)
    private val nov1 = day(2026, 11, 1)
    private val dec1 = day(2026, 12, 1)
    private val jan1 = day(2027, 1, 1)
    private fun millis(day: Int) = day * 86_400_000L + 43_200_000L

    private fun terms(escrow: Long = 0, start: Int? = null) = LoanTerms(
        accountId = 0, type = InterestType.REDUCING, rate = 60_000, payment = 58_000, startDay = start,
        nextPaymentDay = nov1, dueDay = 1, escrow = escrow,
    )

    @Before
    fun open(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()
        val accounts = AccountRepository(db)
        entries = EntryRepository(db)
        loans = LoanRepository(db, accounts, CategoryRepository(db))
        checking = accounts.add(Account(name = "Checking", type = AccountType.CHECKING, openingBalance = 500_000), null)
        loan = loans.addLoan(Account(name = "Car loan", type = AccountType.LOAN, openingBalance = -2_148_000), terms(), null)
    }

    @After
    fun close() = db.close()

    private fun draft(
        id: Long? = null, day: Int = nov1, principal: Long = 47_260, interest: Long = 10_740, escrow: Long = 0, escrowCategory: Long? = null,
    ) = LoanPaymentDraft(
        id = id, accountId = loan, fromId = checking, day = day, epochMillis = millis(day), amount = principal + interest + escrow,
        principal = principal, interest = interest, escrow = escrow, charge = 0, categoryId = null, escrowCategoryId = escrowCategory,
        edited = false, estimate = interest,
    )

    private suspend fun balance(id: Long): Long = db.accounts().balanceOf(id) ?: 0L
    private suspend fun next() = db.loanTerms().forAccount(loan)!!.nextPaymentDay
    private suspend fun spent() = db.transactions().all().filter { it.kind == "EXPENSE" }.sumOf { it.amount }

    // ---------- payments ----------

    @Test
    fun aScheduledPaymentIsAPrincipalTransferAndAnInterestExpenseAndMovesTheNextDate(): Unit = runBlocking {
        val p = loans.recordPayment(draft())
        assertEquals(-2_148_000L + 47_260L, balance(loan))
        assertEquals(500_000L - 58_000L, balance(checking))
        assertEquals(dec1, next())
        val kinds = p.entryIds.map { db.transactions().byId(it)!!.kind }
        assertEquals(listOf("TRANSFER", "EXPENSE"), kinds)
        assertEquals(10_740L, spent())
        // Every entry of the payment finds the payment, whichever it is.
        p.entryIds.forEach { assertEquals(p.id, loans.paymentForEntry(it)?.id) }
        val names = db.categories().all().map { it.name }
        assertTrue("Loan interest" in names)
    }

    @Test
    fun editingAPaymentReplacesItsEntriesAndKeepsTheNextDateUntilItStopsBeingScheduled(): Unit = runBlocking {
        val p = loans.recordPayment(draft())
        loans.recordPayment(draft(id = p.id, principal = 47_000, interest = 11_000))
        assertEquals(-2_148_000L + 47_000L, balance(loan))
        assertEquals(500_000L - 58_000L, balance(checking))
        assertEquals(dec1, next())
        assertEquals(2, db.transactions().all().size)
        // Edited down to less than the scheduled payment: it no longer counts as that payment, so the date goes back.
        loans.recordPayment(draft(id = p.id, principal = 40_000, interest = 10_000))
        assertEquals(nov1, next())
        assertEquals(-2_148_000L + 40_000L, balance(loan))
        // And back up to the scheduled payment: forward again.
        loans.recordPayment(draft(id = p.id))
        assertEquals(dec1, next())
        assertEquals(1, db.loanPayments().all().size)
    }

    @Test
    fun deletingAPaymentRemovesItsEntriesPutsTheBalanceBackAndMovesTheNextDateBack(): Unit = runBlocking {
        val first = loans.recordPayment(draft())
        val second = loans.recordPayment(draft(day = dec1, principal = 47_496, interest = 10_504))
        assertEquals(day(2027, 1, 1), next())
        loans.deletePayment(second.id)
        assertEquals(dec1, next())
        assertEquals(-2_148_000L + 47_260L, balance(loan))
        loans.deletePayment(first.id)
        assertEquals(nov1, next())
        assertEquals(-2_148_000L, balance(loan))
        assertEquals(500_000L, balance(checking))
        assertEquals(0, db.transactions().all().size)
        assertEquals(0, db.loanPayments().all().size)
    }

    @Test
    fun anExtraPaymentDoesNotMoveTheDateTwiceAndOneBelowScheduleDoesNotMoveIt(): Unit = runBlocking {
        loans.recordPayment(draft(principal = 20_000, interest = 10_000))
        assertEquals(nov1, next())
        loans.recordPayment(draft(principal = 100_000, interest = 10_740))
        assertEquals(dec1, next())
    }

    @Test
    fun escrowIsItsOwnEntryInTheSharedEscrowCategoryAndAPaymentCanNameAnother(): Unit = runBlocking {
        val house = loans.addLoan(Account(name = "House loan", type = AccountType.LOAN, openingBalance = -5_000_000), terms(escrow = 25_000), null)
        val shared = db.categories().all().first { it.name == "Escrow" }
        assertEquals("EXPENSE", shared.kind)
        val p = loans.recordPayment(draft(escrow = 25_000).copy(accountId = house))
        assertEquals(3, p.entryIds.size)
        val escrowEntry = db.transactions().byId(p.otherEntryId!!)!!
        assertEquals(shared.id, escrowEntry.categoryId)
        assertEquals(25_000L, escrowEntry.amount)
        assertEquals(25_000L, p.escrow)
        // The escrow counts as spending, and so does the interest; the principal is a transfer and does not.
        assertEquals(10_740L + 25_000L, spent())
        assertEquals(1, db.categories().all().count { it.name == "Escrow" })

        // A single payment can name any category; the category the payment holds is the one it was saved with.
        val housing = db.categories().insert(CategoryEntity(0, "Housing", "EXPENSE", null, "emoji:\uD83C\uDFE0", 0, true, false, 0, null))
        val q = loans.recordPayment(draft(id = p.id, escrow = 25_000, escrowCategory = housing).copy(accountId = house))
        assertEquals(housing, db.transactions().byId(q.otherEntryId!!)!!.categoryId)
        assertEquals(housing, loans.detail(q.id)!!.escrowCategoryId)
        assertEquals(1, db.loanPayments().all().size)
    }

    @Test
    fun aPaymentThatBreaksARuleIsRefusedAndChangesNothing(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { loans.recordPayment(draft().copy(fromId = null)) }
        assertFailsWith<IllegalArgumentException> { loans.recordPayment(draft(principal = 3_000_000, interest = 0)) }
        assertFailsWith<IllegalArgumentException> { loans.recordPayment(draft().copy(amount = 1)) }
        assertEquals(0, db.transactions().all().size)
        assertEquals(nov1, next())
    }

    // ---------- term changes ----------

    @Test
    fun aTermChangeAppliesFromItsDateAndLeavesEarlierPaymentsAlone(): Unit = runBlocking {
        val p = loans.recordPayment(draft())
        val before = db.transactions().all()
        loans.addChange(LoanTermChange(accountId = loan, effectiveDay = jan1, rate = 72_500))
        assertEquals(before, db.transactions().all())
        assertEquals(p.id, db.loanPayments().all().single().id)

        val t = db.loanTerms().forAccount(loan)!!.toDomain()
        val changes = db.loanChanges().all().map { it.toDomain() }
        assertEquals(1, changes.size)
        // Dec 1 still splits at 6%, Jan 1 at 7.25%.
        val dec = LoanEngine.suggest(58_000, dec1, 2_100_740, nov1, t, changes)
        assertEquals(10_504L, dec.interest)
        val jan = LoanEngine.suggest(58_000, jan1, 2_053_244, dec1, t, changes)
        assertEquals(12_405L, jan.interest)
        val rows = LoanEngine.project(t, changes, LoanCursor(2_100_740, dec1, nov1))
        assertEquals(10_504L, rows[0].interest)
        assertEquals(12_405L, rows[1].interest)

        loans.deleteChange(changes.single().id)
        assertEquals(0, db.loanChanges().all().size)
    }

    @Test
    fun aChangeThatChangesNothingOrStartsBeforeTheLoanIsRefused(): Unit = runBlocking {
        loans.updateDetails(Account(id = loan, name = "Car loan", type = AccountType.LOAN, openingBalance = -2_148_000), terms(start = day(2025, 4, 1)).copy(accountId = loan))
        assertFailsWith<IllegalArgumentException> { loans.addChange(LoanTermChange(accountId = loan, effectiveDay = jan1)) }
        assertFailsWith<IllegalArgumentException> { loans.addChange(LoanTermChange(accountId = loan, effectiveDay = day(2025, 3, 1), rate = 70_000)) }
        assertFailsWith<IllegalArgumentException> { loans.addChange(LoanTermChange(accountId = loan, effectiveDay = jan1, rate = 2_000_000)) }
        assertFailsWith<IllegalArgumentException> { loans.addChange(LoanTermChange(accountId = 999, effectiveDay = jan1, rate = 70_000)) }
        assertEquals(0, db.loanChanges().all().size)
        loans.addChange(LoanTermChange(accountId = loan, effectiveDay = day(2025, 4, 1), payment = 60_000))
        assertEquals(1, db.loanChanges().all().size)
    }

    @Test
    fun editingDetailsRenamesTheLoanAndKeepsItsEntries(): Unit = runBlocking {
        loans.recordPayment(draft())
        val t = db.loanTerms().forAccount(loan)!!.toDomain()
        loans.updateDetails(Account(id = loan, name = "Truck loan", type = AccountType.LOAN, openingBalance = -2_148_000), t.copy(escrow = 5_000, dueDay = 15))
        assertEquals("Truck loan", db.accounts().all().first { it.id == loan }.name)
        assertEquals(5_000L, db.loanTerms().forAccount(loan)!!.escrow)
        assertEquals(2, db.transactions().all().size)
        assertTrue(db.categories().all().any { it.name == "Escrow" })
    }

    // ---------- balance corrections ----------

    @Test
    fun aCorrectionMakesTheBalanceMatchWithoutCountingAsSpendingOrTouchingEarlierEntries(): Unit = runBlocking {
        val p = loans.recordPayment(draft())
        val earlier = db.transactions().all()
        val spentBefore = spent()
        // Owed 21,007.40 after the payment; the lender shows 20,982.60.
        val fix = entries.correctBalance(loan, target = -2_098_260, epochMillis = millis(dec1))
        assertEquals(TxKind.ADJUST_IN, fix.kind)
        assertEquals(2_480L, fix.amount)
        assertEquals(-2_098_260L, balance(loan))
        assertEquals(spentBefore, spent())
        assertEquals(earlier, db.transactions().all().filter { it.id != fix.id })
        assertEquals(p.id, db.loanPayments().all().single().id)
        // The checking account is untouched.
        assertEquals(500_000L - 58_000L, balance(checking))

        // Edited: the difference is worked out without the earlier correction.
        val again = entries.correctBalance(loan, target = -2_110_000, epochMillis = millis(dec1), editingId = fix.id)
        assertEquals(fix.id, again.id)
        assertEquals(TxKind.ADJUST_OUT, again.kind)
        assertEquals(9_260L, again.amount)
        assertEquals(-2_110_000L, balance(loan))

        // The next payment starts from the corrected balance.
        assertFailsWith<IllegalArgumentException> { loans.recordPayment(draft(day = dec1, principal = 2_110_001, interest = 0)) }

        entries.delete(fix.id)
        assertEquals(-2_148_000L + 47_260L, balance(loan))
        assertFailsWith<IllegalArgumentException> { entries.correctBalance(loan, target = -2_148_000 + 47_260, epochMillis = millis(dec1)) }
        assertFailsWith<IllegalArgumentException> { entries.correctBalance(loan, target = -2_000_000, epochMillis = millis(dec1), editingId = p.principalEntryId) }
    }

    @Test
    fun aCorrectionSurvivesABackupAndRestore(): Unit = runBlocking {
        loans.recordPayment(draft())
        entries.correctBalance(loan, target = -2_098_260, epochMillis = millis(dec1))
        entries.correctBalance(checking, target = 400_000, epochMillis = millis(dec1))
        val target = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()
        try {
            val backups = { d: KassaDatabase -> BackupRepository(context, d, Settings(context)) }
            val file = ByteArrayOutputStream().also { backups(db).write(it, null) }.toByteArray()
            backups(target).restore(backups(target).read(ByteArrayInputStream(file), null))
            assertEquals(db.transactions().all(), target.transactions().all())
            assertEquals(db.loanPayments().all(), target.loanPayments().all())
            assertEquals(balance(loan), target.accounts().balanceOf(loan) ?: 0L)
            assertEquals(balance(checking), target.accounts().balanceOf(checking) ?: 0L)
            assertEquals(listOf("ADJUST_IN", "ADJUST_OUT"), target.transactions().all().map { it.kind }.filter { it.startsWith("ADJUST") }.sorted())
        } finally {
            target.close()
        }
    }
}
