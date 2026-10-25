package app.kassa.money.data.cashew

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.kassa.money.importer.CashewSource
import app.kassa.money.importer.CwBudget
import app.kassa.money.importer.CwCategory
import app.kassa.money.importer.CwCategoryLimit
import app.kassa.money.importer.CwObjective
import app.kassa.money.importer.CwTransaction
import app.kassa.money.importer.CwWallet
import app.kassa.money.importer.NotReadableBackup
import java.io.Closeable
import java.io.File

/**
 * Reads a Cashew backup (Cashew's SQLite database, saved as a .sql file) that was copied into the app's cache. Opened
 * read-only; [close] deletes the copy. Columns are looked up by name, so backups from Cashew versions that lack newer
 * columns still read: a missing column gives null or its default.
 */
class AndroidCashewSource private constructor(private val copy: File, private val db: SQLiteDatabase) : CashewSource, Closeable {

    override fun wallets(): List<CwWallet> = rows("wallets") { c ->
        CwWallet(pk = c.str("wallet_pk") ?: return@rows null, name = c.str("name").orEmpty(), currency = c.str("currency")?.takeIf { it.isNotBlank() }, order = c.int("order") ?: 0)
    }

    override fun categories(): List<CwCategory> = rows("categories") { c ->
        CwCategory(
            pk = c.str("category_pk") ?: return@rows null, name = c.str("name").orEmpty(), colour = c.str("colour"),
            iconName = c.str("icon_name"), emoji = c.str("emoji_icon_name"), order = c.int("order") ?: 0,
            income = c.bool("income") ?: false, mainCategoryPk = c.str("main_category_pk")?.takeIf { it.isNotBlank() },
        )
    }

    override fun transactions(): List<CwTransaction> = rows("transactions") { c ->
        CwTransaction(
            pk = c.str("transaction_pk") ?: return@rows null,
            pairedPk = c.str("paired_transaction_fk")?.takeIf { it.isNotBlank() },
            title = c.str("name").orEmpty(), amount = c.decimal("amount"), note = c.str("note").orEmpty(),
            categoryPk = c.str("category_fk"), subCategoryPk = c.str("sub_category_fk")?.takeIf { it.isNotBlank() },
            walletPk = c.str("wallet_fk"), epochMillis = c.millis("date_created"),
            income = c.bool("income") ?: false, type = c.int("type"), paid = c.bool("paid") ?: true,
            skipPaid = c.bool("skip_paid") ?: false, reoccurrence = c.int("reoccurrence"), periodLength = c.int("period_length"),
            objectivePk = c.str("objective_fk")?.takeIf { it.isNotBlank() },
            objectiveLoanPk = c.str("objective_loan_fk")?.takeIf { it.isNotBlank() },
        )
    }

    override fun objectives(): List<CwObjective> = if (!hasTable("objectives")) emptyList() else rows("objectives") { c ->
        CwObjective(
            pk = c.str("objective_pk") ?: return@rows null, type = c.int("type") ?: 0, name = c.str("name").orEmpty(),
            amount = c.decimal("amount"), income = c.bool("income") ?: false, epochMillis = c.millis("date_created"),
            archived = c.bool("archived") ?: false,
        )
    }

    override fun budgets(): List<CwBudget> = if (!hasTable("budgets")) emptyList() else rows("budgets") { c ->
        CwBudget(
            pk = c.str("budget_pk") ?: return@rows null, name = c.str("name").orEmpty(), amount = c.decimal("amount"),
            reoccurrence = c.int("reoccurrence"), periodLength = c.int("period_length") ?: 1, archived = c.bool("archived") ?: false,
            absoluteLimits = c.bool("is_absolute_spending_limit") ?: true, order = c.int("order") ?: 0,
        )
    }

    override fun categoryLimits(): List<CwCategoryLimit> = if (!hasTable("category_budget_limits")) emptyList() else rows("category_budget_limits") { c ->
        CwCategoryLimit(categoryPk = c.str("category_fk") ?: return@rows null, budgetPk = c.str("budget_fk") ?: return@rows null, amount = c.decimal("amount"))
    }

    override fun primaryWalletPk(): String? = runCatching {
        if (!hasTable("app_settings")) return null
        val json = db.rawQuery("SELECT settings_j_s_o_n FROM app_settings LIMIT 1", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null } ?: return null
        Regex("\"selectedWalletPk\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }.getOrNull()

    override fun close() {
        db.close()
        copy.delete()
    }

    private fun hasTable(name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.count > 0 }

    private fun <T : Any> rows(table: String, map: (Cursor) -> T?): List<T> =
        db.rawQuery("SELECT * FROM \"$table\"", null).use { c -> buildList { while (c.moveToNext()) map(c)?.let(::add) } }

    private fun Cursor.col(name: String): Int = getColumnIndex(name)
    private fun Cursor.str(name: String): String? = col(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
    private fun Cursor.int(name: String): Int? = col(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it).trim().toDoubleOrNull()?.toInt() }
    private fun Cursor.bool(name: String): Boolean? = int(name)?.let { it != 0 } ?: str(name)?.let { it.equals("true", ignoreCase = true) }

    /** A number column as plain decimal text, whether stored as a float, an integer or text. */
    private fun Cursor.decimal(name: String): String {
        val i = col(name)
        if (i < 0 || isNull(i)) return ""
        return when (getType(i)) {
            Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_INTEGER -> java.math.BigDecimal.valueOf(getDouble(i)).toPlainString()
            else -> getString(i).trim()
        }
    }

    /** Cashew stores times as Unix seconds; some settings store them as ISO text instead. */
    private fun Cursor.millis(name: String): Long {
        val i = col(name)
        if (i < 0 || isNull(i)) return 0L
        return if (getType(i) == Cursor.FIELD_TYPE_INTEGER || getType(i) == Cursor.FIELD_TYPE_FLOAT) {
            val v = getLong(i)
            // Seconds unless it's clearly already milliseconds (after the year 2286 in seconds).
            if (v > 10_000_000_000L) v else v * 1000
        } else runCatching { java.time.LocalDateTime.parse(getString(i).trim().replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        /** Tables and columns every Cashew backup Kassa reads has; older backups that lack them are refused. */
        private val REQUIRED = mapOf(
            "wallets" to listOf("wallet_pk", "name"),
            "categories" to listOf("category_pk", "name", "income"),
            "transactions" to listOf("transaction_pk", "amount", "category_fk", "wallet_fk", "date_created", "paid"),
        )

        /** True when [db] has Cashew's tables, whatever its version. */
        fun looksLikeCashew(db: SQLiteDatabase): Boolean =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('wallets','transactions','categories')", null).use { it.count == 3 }

        /** Opens [file] (already a copy Kassa owns). Throws [NotReadableBackup] with what to do when it can't. */
        fun open(file: File): AndroidCashewSource {
            val db = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                file.delete()
                throw NotReadableBackup("The file is damaged or cut short. Back up again in Cashew and pick the new file.")
            }
            try {
                // A file cut short still opens; checking the pages catches it before any numbers are read.
                val check = db.rawQuery("PRAGMA quick_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
                if (check != "ok") throw IllegalStateException("damaged: $check")
                for ((table, columns) in REQUIRED) {
                    val have = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
                    if (!have.containsAll(columns)) throw NotReadableBackup(
                        "This Cashew backup is from a version older than Kassa can read. Open it in an up-to-date Cashew, back up again, and pick the new file.",
                    )
                }
            } catch (e: NotReadableBackup) {
                db.close(); file.delete(); throw e
            } catch (e: Exception) {
                db.close(); file.delete()
                throw NotReadableBackup("The file is damaged or cut short. Back up again in Cashew and pick the new file.")
            }
            return AndroidCashewSource(file, db)
        }
    }
}
