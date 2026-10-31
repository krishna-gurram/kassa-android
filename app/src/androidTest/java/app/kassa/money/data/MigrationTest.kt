package app.kassa.money.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.db.ALL_MIGRATIONS
import app.kassa.money.data.db.DATABASE_VERSION
import app.kassa.money.data.db.KassaDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Upgrades databases from every older version and checks the result matches the current schema exactly.
 *
 * Old databases are built from app/schemas. 4.json was exported by Room; 1 to 3 were rebuilt from it by undoing
 * each migration (Room only started exporting at version 4).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KassaDatabase::class.java)

    @Test
    fun everyOlderVersionUpgradesToTheCurrentSchema() {
        for (from in 1 until DATABASE_VERSION) {
            val name = "upgrade-from-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, DATABASE_VERSION, true, *ALL_MIGRATIONS).close()
        }
    }

    @Test
    fun entriesFromTheFirstVersionSurvive() {
        helper.createDatabase("entries-v1.db", 1).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, groupId, openingBalance, statementDay, dueDay, payFromId, creditLimit, archived, sortOrder, externalId) " +
                    "VALUES (1, 'Everyday checking', 'CHECKING', NULL, 10000, NULL, NULL, NULL, NULL, 0, 0, NULL)"
            )
            execSQL(
                "INSERT INTO transactions (id, kind, amount, accountId, toAccountId, categoryId, epochMillis, note, myShare, externalId) " +
                    "VALUES (7, 'EXPENSE', 2500, 1, NULL, NULL, 1760000000000, 'Corner market', NULL, NULL)"
            )
            close()
        }
        helper.runMigrationsAndValidate("entries-v1.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT amount, accountId, note, personId, (SELECT last4 FROM accounts WHERE id = 1) FROM transactions WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals(2500, c.getLong(0))
                assertEquals(1, c.getLong(1))
                assertEquals("Corner market", c.getString(2))
                assertTrue(c.isNull(3))
                assertTrue(c.isNull(4))
            }
        }
    }

    @Test
    fun waitingInvestmentTransfersCarryOverAndAlertsAreDropped() {
        helper.createDatabase("pending-v4.db", 4).apply {
            execSQL(
                "INSERT INTO pending_items (id, type, amount, merchant, accountId, categoryId, epochMillis, source, isCredit, txId) " +
                    "VALUES (1, 'ALLOCATE', 50000, NULL, 3, NULL, 1760000000000, NULL, 0, 12)"
            )
            execSQL(
                "INSERT INTO pending_items (id, type, amount, merchant, accountId, categoryId, epochMillis, source, isCredit, txId) " +
                    "VALUES (2, 'ALERT', 1999, 'Coffee', 2, NULL, 1760000000000, 'bank', 0, NULL)"
            )
            close()
        }
        helper.runMigrationsAndValidate("pending-v4.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT id, transactionId, accountId, amount FROM investment_transfers").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(listOf(1L, 12L, 3L, 50000L), (0..3).map(c::getLong))
            }
            db.query("SELECT name FROM sqlite_master WHERE name IN ('pending_items', 'alert_rules')").use { c -> assertFalse(c.moveToFirst()) }
        }
    }

    @Test
    fun holdingsKeepTheirSharesAndStartWithoutAnAverageCost() {
        helper.createDatabase("holdings-v5.db", 5).apply {
            execSQL("INSERT INTO holdings (id, accountId, ticker, microShares, price, priceMillis) VALUES (1, 3, 'VTI', 15000000, 25000, 1760000000000)")
            close()
        }
        helper.runMigrationsAndValidate("holdings-v5.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT microShares, price, avgCost FROM holdings WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(15000000L, c.getLong(0))
                assertEquals(25000L, c.getLong(1))
                assertTrue(c.isNull(2))
            }
        }
    }

    @Test
    fun tradesStartEmptyAndEarlierBuysStay() {
        helper.createDatabase("trades-v6.db", 6).apply {
            execSQL("INSERT INTO transactions (id, kind, amount, accountId, toAccountId, categoryId, personId, epochMillis, note, myShare, externalId) VALUES (4, 'BUY', 200000, 3, NULL, NULL, NULL, 1760000000000, 'VTI', NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate("trades-v6.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM trades").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
            db.query("SELECT amount FROM transactions WHERE id = 4").use { c -> c.moveToFirst(); assertEquals(200000L, c.getLong(0)) }
        }
    }

    @Test
    fun loanTablesStartEmptyAndAnExistingLoanAccountKeepsItsEntries() {
        helper.createDatabase("loans-v7.db", 7).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, groupId, openingBalance, statementDay, dueDay, payFromId, creditLimit, archived, sortOrder, externalId, last4) " +
                    "VALUES (3, 'Car loan', 'LOAN', NULL, -2148000, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL)"
            )
            execSQL("INSERT INTO transactions (id, kind, amount, accountId, toAccountId, categoryId, personId, epochMillis, note, myShare, externalId) VALUES (9, 'TRANSFER', 58000, 1, 3, NULL, NULL, 1760000000000, 'Payment', NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate("loans-v7.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            for (table in listOf("loan_terms", "loan_term_changes", "loan_payments")) {
                db.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0), table) }
            }
            db.query("SELECT type, openingBalance FROM accounts WHERE id = 3").use { c ->
                c.moveToFirst()
                assertEquals("LOAN", c.getString(0))
                assertEquals(-2148000L, c.getLong(1))
            }
            db.query("SELECT amount FROM transactions WHERE id = 9").use { c -> c.moveToFirst(); assertEquals(58000L, c.getLong(0)) }
            // The new tables take rows: a loan's terms, and a payment linking its entries.
            db.execSQL(
                "INSERT INTO loan_terms (accountId, type, profit, rate, payment, termMonths, originalAmount, startDay, nextPaymentDay, dueDay, escrow, fee, earlyCharge, interestCategoryId, payFromId) " +
                    "VALUES (3, 'REDUCING', 0, 60000, 58000, NULL, NULL, NULL, 20758, 1, 0, 0, 0, NULL, NULL)"
            )
            db.execSQL("INSERT INTO loan_payments (accountId, day, amount, principalEntryId, interestEntryId, otherEntryId, escrow, charge, edited, estimate, scheduled) VALUES (3, 20758, 58000, 9, NULL, NULL, 0, 0, 0, 0, 1)")
        }
    }

    @Test
    fun accountsGainMaturityAndClosedDaysAndEveryExistingOneStaysOpen() {
        helper.createDatabase("accounts-v8.db", 8).apply {
            execSQL(
                "INSERT INTO accounts (id, name, type, groupId, openingBalance, statementDay, dueDay, payFromId, creditLimit, archived, sortOrder, externalId, last4) " +
                    "VALUES (1, 'Everyday checking', 'CHECKING', NULL, 150000, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL)"
            )
            close()
        }
        helper.runMigrationsAndValidate("accounts-v8.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT openingBalance, maturityDay, closedDay FROM accounts WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(150000L, c.getLong(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }
            // The new columns take values: a fixed deposit with a maturity day, and a closed account.
            db.execSQL(
                "INSERT INTO accounts (id, name, type, groupId, openingBalance, statementDay, dueDay, payFromId, creditLimit, archived, sortOrder, externalId, last4, maturityDay, closedDay) " +
                    "VALUES (2, 'Term deposit', 'FIXED_DEPOSIT', NULL, 300000, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, 20800, NULL)"
            )
            db.execSQL("UPDATE accounts SET closedDay = 20900 WHERE id = 1")
            db.query("SELECT closedDay FROM accounts WHERE id = 1").use { c -> c.moveToFirst(); assertEquals(20900L, c.getLong(0)) }
        }
    }

    @Test
    fun loansGainTheSpendingSwitchOffAndExistingPaymentsFollowTheirLoan() {
        helper.createDatabase("loans-v9.db", 9).apply {
            execSQL(
                "INSERT INTO loan_terms (accountId, type, profit, rate, payment, termMonths, originalAmount, startDay, nextPaymentDay, dueDay, escrow, fee, earlyCharge, interestCategoryId, payFromId) " +
                    "VALUES (3, 'REDUCING', 0, 60000, 58000, NULL, NULL, NULL, 20758, 1, 0, 0, 0, NULL, NULL)"
            )
            execSQL("INSERT INTO loan_payments (accountId, day, amount, principalEntryId, interestEntryId, otherEntryId, escrow, charge, edited, estimate, scheduled) VALUES (3, 20758, 58000, 9, 10, NULL, 0, 0, 0, 0, 1)")
            close()
        }
        helper.runMigrationsAndValidate("loans-v9.db", DATABASE_VERSION, true, *ALL_MIGRATIONS).use { db ->
            db.query("SELECT payment, countAllAsSpending, principalCategoryId FROM loan_terms WHERE accountId = 3").use { c ->
                c.moveToFirst()
                assertEquals(58000L, c.getLong(0))
                assertEquals(0L, c.getLong(1))
                assertTrue(c.isNull(2))
            }
            db.query("SELECT amount, countAllAsSpending, principalCategoryId FROM loan_payments WHERE accountId = 3").use { c ->
                c.moveToFirst()
                assertEquals(58000L, c.getLong(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }
            // The new columns take values.
            db.execSQL("UPDATE loan_terms SET countAllAsSpending = 1, principalCategoryId = 4 WHERE accountId = 3")
            db.execSQL("UPDATE loan_payments SET countAllAsSpending = 0, principalCategoryId = 5 WHERE accountId = 3")
            db.query("SELECT countAllAsSpending, principalCategoryId FROM loan_terms WHERE accountId = 3").use { c ->
                c.moveToFirst(); assertEquals(1L, c.getLong(0)); assertEquals(4L, c.getLong(1))
            }
        }
    }
}
