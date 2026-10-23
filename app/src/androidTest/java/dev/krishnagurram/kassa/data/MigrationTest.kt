package dev.krishnagurram.kassa.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.krishnagurram.kassa.data.db.ALL_MIGRATIONS
import dev.krishnagurram.kassa.data.db.DATABASE_VERSION
import dev.krishnagurram.kassa.data.db.KassaDatabase
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
}
