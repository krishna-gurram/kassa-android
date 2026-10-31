package app.kassa.money.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kassa.money.data.cashew.AndroidCashewSource
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.PersonEntity
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.repo.ImportRepository
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.balanceOf
import app.kassa.money.domain.personBalance
import app.kassa.money.importer.CashewChoices
import app.kassa.money.importer.CashewImporter
import app.kassa.money.importer.LoanChoice
import app.kassa.money.importer.NotReadableBackup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A real SQLite file laid out like a Cashew backup (made-up data), read the way the app reads it, imported into a
 * Kassa database. Covers the current layout and an older one without transfer links, subcategories or loans.
 */
@RunWith(AndroidJUnit4::class)
class CashewImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var kassa: KassaDatabase

    @Before fun open() { kassa = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build() }
    @After fun close() { kassa.close() }

    private fun backup(name: String, old: Boolean = false, fill: SQLiteDatabase.() -> Unit): File {
        val f = File(context.cacheDir, name).also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE wallets (wallet_pk TEXT PRIMARY KEY, name TEXT, colour TEXT, icon_name TEXT, date_created INTEGER, \"order\" INTEGER, currency TEXT${if (old) "" else ", decimals INTEGER"})")
            db.execSQL("CREATE TABLE categories (category_pk TEXT PRIMARY KEY, name TEXT, colour TEXT, icon_name TEXT, ${if (old) "" else "emoji_icon_name TEXT, "}date_created INTEGER, \"order\" INTEGER, income INTEGER, method_added INTEGER${if (old) "" else ", main_category_pk TEXT"})")
            db.execSQL(
                "CREATE TABLE transactions (transaction_pk TEXT PRIMARY KEY, ${if (old) "" else "paired_transaction_fk TEXT, "}name TEXT, amount REAL, note TEXT, category_fk TEXT, " +
                    "${if (old) "" else "sub_category_fk TEXT, "}wallet_fk TEXT, date_created INTEGER, income INTEGER, period_length INTEGER, reoccurrence INTEGER, type INTEGER, paid INTEGER, skip_paid INTEGER" +
                    "${if (old) "" else ", objective_fk TEXT, objective_loan_fk TEXT"})",
            )
            db.execSQL("CREATE TABLE budgets (budget_pk TEXT PRIMARY KEY, name TEXT, amount REAL, archived INTEGER, period_length INTEGER, reoccurrence INTEGER, \"order\" INTEGER${if (old) "" else ", is_absolute_spending_limit INTEGER"})")
            db.execSQL("CREATE TABLE category_budget_limits (category_limit_pk TEXT PRIMARY KEY, category_fk TEXT, budget_fk TEXT, amount REAL)")
            db.execSQL("CREATE TABLE app_settings (settings_pk INTEGER PRIMARY KEY, settings_j_s_o_n TEXT, date_updated INTEGER)")
            if (!old) db.execSQL("CREATE TABLE objectives (objective_pk TEXT PRIMARY KEY, type INTEGER, name TEXT, amount REAL, \"order\" INTEGER, date_created INTEGER, income INTEGER, archived INTEGER)")
            db.fill()
        }
        return f
    }

    private fun SQLiteDatabase.row(table: String, vararg values: Pair<String, Any?>) {
        insertOrThrow(table, null, ContentValues().apply {
            values.forEach { (key, v) ->
                // "order" is an SQL keyword; Cashew's column has that name, so it has to be quoted.
                val k = if (key == "order") "\"order\"" else key
                when (v) {
                    null -> putNull(k); is String -> put(k, v); is Int -> put(k, v); is Long -> put(k, v); is Double -> put(k, v); is Boolean -> put(k, if (v) 1 else 0)
                    else -> error("unsupported $v")
                }
            }
        })
    }

    private var n = 0
    private fun SQLiteDatabase.tx(amount: Double, wallet: String, category: String, secs: Long, vararg more: Pair<String, Any?>) =
        row("transactions", "transaction_pk" to "t${++n}", "name" to "Entry $n", "amount" to amount, "note" to "", "category_fk" to category,
            "wallet_fk" to wallet, "date_created" to secs, "income" to (amount > 0), "paid" to true, "skip_paid" to false, *more)

    private val oct1 = 1790812800L // 2026-10-01 UTC, in seconds the way Cashew stores it

    @Test
    fun aCurrentBackupImportsWithMatchingBalances(): Unit = runBlocking {
        val file = backup("cashew-current.db") {
            row("app_settings", "settings_pk" to 0, "settings_j_s_o_n" to "{\"selectedWalletPk\":\"0\",\"other\":1}", "date_updated" to oct1)
            row("wallets", "wallet_pk" to "0", "name" to "Everyday", "order" to 0, "currency" to "usd", "decimals" to 2)
            row("wallets", "wallet_pk" to "w2", "name" to "Card B", "order" to 1, "currency" to "usd", "decimals" to 2)
            row("categories", "category_pk" to "0", "name" to "Balance Correction", "order" to 0, "income" to false)
            row("categories", "category_pk" to "food", "name" to "Food", "colour" to "0xff3f9a5e", "icon_name" to "cutlery.png", "order" to 1, "income" to false)
            row("categories", "category_pk" to "cafe", "name" to "Coffee", "order" to 2, "income" to false, "main_category_pk" to "food")
            row("categories", "category_pk" to "pay", "name" to "Salary", "emoji_icon_name" to "💼", "order" to 3, "income" to true)
            row("objectives", "objective_pk" to "M", "type" to 1, "name" to "Mortgage", "amount" to 312000.0, "order" to 0, "date_created" to oct1, "income" to false, "archived" to false)
            row("objectives", "objective_pk" to "S", "type" to 1, "name" to "Friend A", "amount" to 600.0, "order" to 1, "date_created" to oct1, "income" to true, "archived" to false)
            tx(2480.0, "0", "pay", oct1)
            tx(-4.25, "0", "food", oct1 + 3600, "sub_category_fk" to "cafe")
            tx(-129.99, "w2", "food", oct1 + 7200)
            tx(-1850.0, "0", "food", oct1 + 86400, "objective_loan_fk" to "M")
            tx(-600.0, "0", "food", oct1 + 86400 * 2, "objective_loan_fk" to "S")
            tx(200.0, "0", "pay", oct1 + 86400 * 3, "objective_loan_fk" to "S")
            row("transactions", "transaction_pk" to "out", "name" to "Card payment", "amount" to -129.99, "note" to "", "category_fk" to "0", "wallet_fk" to "0", "date_created" to oct1 + 86400 * 4, "income" to false, "paid" to true, "skip_paid" to false)
            row("transactions", "transaction_pk" to "in", "paired_transaction_fk" to "out", "name" to "Card payment", "amount" to 129.99, "note" to "", "category_fk" to "0", "wallet_fk" to "w2", "date_created" to oct1 + 86400 * 4, "income" to true, "paid" to true, "skip_paid" to false)
            tx(-15.49, "0", "food", oct1 + 86400 * 40, "paid" to false, "type" to 1, "reoccurrence" to 3, "period_length" to 1)
            row("budgets", "budget_pk" to "b", "name" to "Monthly", "amount" to 2000.0, "archived" to false, "period_length" to 1, "reoccurrence" to 3, "order" to 0, "is_absolute_spending_limit" to true)
            row("category_budget_limits", "category_limit_pk" to "l", "category_fk" to "food", "budget_fk" to "b", "amount" to 450.0)
        }
        val picked = PickedFile.identify(context, Uri.fromFile(file))
        assertIs<PickedFile.Cashew>(picked)
        AndroidCashewSource.open(picked.copy).use { src ->
            val scan = CashewImporter().scan(src)
            assertEquals("USD", scan.mainCurrency)
            assertEquals(2, scan.loans.size)
            val choices = CashewChoices(
                accountTypes = mapOf("0" to AccountType.CHECKING, "w2" to AccountType.CREDIT_CARD),
                loans = mapOf("M" to LoanChoice.ACCOUNT, "S" to LoanChoice.PERSON),
            )
            val plan = CashewImporter().plan(src, choices)
            assertTrue(plan.reconcile.allMatch, plan.reconcile.accounts.toString())
            kassa.people().insert(PersonEntity(name = "Alex", archived = false))
            ImportRepository(kassa).replaceWith(plan)
        }
        val accounts = kassa.accounts().all().map { it.toDomain() }
        val txs = kassa.transactions().all().map { it.toDomain() }
        fun bal(name: String) = balanceOf(accounts.first { it.name == name }, txs)
        assertEquals(2480_00L - 4_25 - 1850_00 - 600_00 + 200_00 - 129_99, bal("Everyday"))
        assertEquals(0L, bal("Card B"))
        assertEquals(-312000_00L + 1850_00, bal("Mortgage"))
        val people = kassa.people().all().map { it.toDomain() }
        assertEquals(setOf("Alex", "Friend A"), people.map { it.name }.toSet())
        val friendA = people.first { it.name == "Friend A" }
        assertEquals(400_00L, personBalance(friendA.id, kassa.loans().all().map { it.toDomain() }, txs))
        assertEquals(1, kassa.recurring().all().size)
        assertEquals(450_00L, kassa.budgets().all().single().amount)
        val coffee = kassa.categories().all().first { it.name == "Coffee" }
        assertEquals(kassa.categories().all().first { it.name == "Food" }.id, coffee.parentId)
    }

    @Test
    fun anOlderBackupWithoutNewerColumnsStillReads(): Unit = runBlocking {
        val file = backup("cashew-old.db", old = true) {
            row("wallets", "wallet_pk" to "0", "name" to "Main", "order" to 0, "currency" to null)
            row("wallets", "wallet_pk" to "1", "name" to "Savings", "order" to 1, "currency" to null)
            row("categories", "category_pk" to "0", "name" to "Balance Correction", "order" to 0, "income" to false)
            row("categories", "category_pk" to "1", "name" to "Dining", "order" to 1, "income" to false)
            tx(1000.0, "0", "0", oct1)
            tx(-25.5, "0", "1", oct1 + 60)
            // A transfer from before Cashew linked the two sides.
            tx(-300.0, "0", "0", oct1 + 600)
            tx(300.0, "1", "0", oct1 + 610)
        }
        AndroidCashewSource.open(File(file.path)).use { src ->
            val plan = CashewImporter().plan(src, CashewChoices(accountTypes = mapOf("0" to AccountType.CHECKING, "1" to AccountType.SAVINGS)))
            assertTrue(plan.reconcile.allMatch)
            assertEquals(1, plan.transactions.count { it.kind == app.kassa.money.domain.TxKind.TRANSFER })
            assertEquals(100000L, plan.accounts.first { it.name == "Main" }.openingBalance)
        }
    }

    @Test
    fun filesKassaCantReadSayWhy() {
        val empty = File(context.cacheDir, "empty.sql").also { it.writeBytes(ByteArray(0)) }
        assertTrue(assertFailsWith<NotReadableBackup> { PickedFile.identify(context, Uri.fromFile(empty)) }.message!!.contains("empty"))
        val csv = File(context.cacheDir, "bank.csv").also { it.writeText("Date,Description,Amount\n10/21/2026,Coffee,-4.25\n") }
        assertTrue(assertFailsWith<NotReadableBackup> { PickedFile.identify(context, Uri.fromFile(csv)) }.message!!.contains("spreadsheet"))
        val junk = File(context.cacheDir, "junk.bin").also { it.writeBytes(ByteArray(64) { (it * 7 + 1).toByte() }) }
        assertTrue(assertFailsWith<NotReadableBackup> { PickedFile.identify(context, Uri.fromFile(junk)) }.message!!.contains("isn't a backup"))
        val cut = backup("cut.db") { row("wallets", "wallet_pk" to "0", "name" to "Main", "order" to 0) }
        val bytes = cut.readBytes()
        val damaged = File(context.cacheDir, "damaged.sql").also { it.writeBytes(bytes.copyOf(bytes.size / 3)) }
        assertFailsWith<NotReadableBackup> { PickedFile.identify(context, Uri.fromFile(damaged)).let { if (it is PickedFile.Cashew) AndroidCashewSource.open(it.copy).use { s -> s.transactions() } } }
        val other = File(context.cacheDir, "other.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(other, null).use { it.execSQL("CREATE TABLE notes (id INTEGER)") }
        assertTrue(assertFailsWith<NotReadableBackup> { PickedFile.identify(context, Uri.fromFile(other)) }.message!!.contains("isn't a backup"))
    }

    @Test
    fun aTooOldCashewBackupIsRefused() {
        val file = File(context.cacheDir, "ancient.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE wallets (wallet_pk INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE categories (category_pk INTEGER PRIMARY KEY, name TEXT, income INTEGER)")
            db.execSQL("CREATE TABLE transactions (transaction_pk INTEGER PRIMARY KEY, amount REAL, category_fk INTEGER, wallet_fk INTEGER, date_created INTEGER)")
        }
        val picked = PickedFile.identify(context, Uri.fromFile(file))
        assertIs<PickedFile.Cashew>(picked)
        assertTrue(assertFailsWith<NotReadableBackup> { AndroidCashewSource.open(picked.copy) }.message!!.contains("older than Kassa can read"))
    }
}
