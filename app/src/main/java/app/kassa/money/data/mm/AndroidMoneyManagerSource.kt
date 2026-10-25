package app.kassa.money.data.mm

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.kassa.money.importer.MmAsset
import app.kassa.money.importer.MmBudget
import app.kassa.money.importer.MmCategory
import app.kassa.money.importer.MmGroup
import app.kassa.money.importer.MmTransaction
import app.kassa.money.importer.MoneyManagerSource
import java.io.Closeable
import java.io.File

/**
 * Reads a Money Manager .mmbak (a plain SQLite file) that the user picked. The file is copied into the app's
 * private cache, opened read-only, and deleted again in [close]. Nothing is written back to the original.
 */
class AndroidMoneyManagerSource private constructor(
    private val copy: File,
    private val db: SQLiteDatabase,
    /** Money Manager for iPhone saves the same data in Core Data tables (ZASSET, ZINOUTCOME...). */
    private val ios: Boolean,
) : MoneyManagerSource, Closeable {

    override fun groups(): List<MmGroup> = if (ios) query(
        "SELECT ZUID, ZASSETGROUPNAME, ZSTATUS, ZORDER FROM ZASSETGROUP WHERE COALESCE(ZISDEL, 0) = 0"
    ) { MmGroup(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 7, it.int(3) ?: 0) } else query(
        "SELECT uid, ACC_GROUP_NAME, TYPE, ORDERSEQ FROM ASSETGROUP WHERE IS_DEL IS NULL OR IS_DEL = 0"
    ) { MmGroup(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 7, it.int(3) ?: 0) }

    override fun assets(): List<MmAsset> = if (ios) query(
        "SELECT ZUID, ZNICNAME, ZGROUPUID, ZORDER, ZCARD_DAYFIN, ZCARD_DAYPAY, ZZDATA FROM ZASSET"
    ) { MmAsset(it.str(0)!!, it.str(1).orEmpty(), it.str(2), it.int(3) ?: 0, it.int(4), it.int(5), it.str(6)) } else query(
        "SELECT uid, NIC_NAME, groupUid, ORDERSEQ, CARD_DAY_FIN, CARD_DAY_PAY, ZDATA FROM ASSETS"
    ) { MmAsset(it.str(0)!!, it.str(1).orEmpty(), it.str(2), it.int(3) ?: 0, it.int(4), it.int(5), it.str(6)) }

    override fun categories(): List<MmCategory> = if (ios) query(
        "SELECT ZUID, ZNAME, ZDOTYPE, ZPUID, ZISDEL, ZORDER FROM ZCATEGORY"
    ) { MmCategory(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 1, it.str(3), it.int(4) == 1, it.int(5) ?: 0) } else query(
        "SELECT uid, NAME, TYPE, pUid, C_IS_DEL, ORDERSEQ FROM ZCATEGORY"
    ) { MmCategory(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 1, it.str(3), it.int(4) == 1, it.int(5) ?: 0) }

    override fun transactions(): List<MmTransaction> = if (ios) query(
        // ZDATE is Core Data time: seconds since 2001-01-01. ZMONEY is empty on iPhone; the amount is ZAMOUNT.
        """SELECT ZUID, ZDO_TYPE, ZASSETUID, ZTOASSETUID, ZCATEGORYUID, COALESCE(ZMONEY, ZAMOUNT), ZDATE, ZCONTENT, ZTXUIDTRANS
           FROM ZINOUTCOME WHERE COALESCE(ZISDEL, 0) = 0"""
    ) {
        MmTransaction(
            uid = it.str(0)!!, doType = it.int(1) ?: -1, assetUid = it.str(2).orEmpty(), toAssetUid = it.str(3),
            categoryUid = it.str(4), amount = it.decimal(5), epochMillis = if (it.isNull(6)) 0L else Math.round((it.getDouble(6) + CORE_DATA_EPOCH) * 1000),
            note = it.str(7), pairKey = it.str(8),
        )
    } else query(
        """SELECT uid, DO_TYPE, assetUid, toAssetUid, ctgUid, ZMONEY, ZDATE, ZCONTENT, txUidTrans
           FROM INOUTCOME WHERE IS_DEL IS NULL OR IS_DEL = 0"""
    ) {
        MmTransaction(
            uid = it.str(0)!!, doType = it.int(1) ?: -1, assetUid = it.str(2).orEmpty(), toAssetUid = it.str(3),
            categoryUid = it.str(4), amount = it.str(5) ?: "0", epochMillis = it.str(6)?.toLongOrNull() ?: 0L,
            note = it.str(7), pairKey = it.str(8),
        )
    }

    override fun budgets(): List<MmBudget> = if (ios) query(
        """SELECT b.ZTARGETUID, a.ZBUDGETPERIOD, a.ZAMOUNT FROM ZBUDGET b JOIN ZBUDGETAMOUNT a ON a.ZBUDGETUID = b.ZUID
           WHERE COALESCE(a.ZISDEL, 0) = 0 AND COALESCE(b.ZISDEL, 0) = 0"""
    ) { MmBudget(it.str(0).orEmpty(), it.int(1) ?: 0, it.getDouble(2)) } else query(
        """SELECT b.targetUid, a.BUDGET_PERIOD, a.AMOUNT FROM BUDGET b JOIN BUDGET_AMOUNT a ON a.budgetUid = b.uid
           WHERE COALESCE(a.IS_DEL, 0) = 0 AND COALESCE(b.IS_DEL, 0) = 0"""
    ) { MmBudget(it.str(0).orEmpty(), it.int(1) ?: 0, it.getDouble(2)) }

    /** The backup's main currency code (like AED), when the backup records one. */
    fun mainCurrency(): String? = runCatching {
        if (ios) query("SELECT ZISO FROM ZCURRENCY WHERE ZISMAINCURRENCY = 1 AND COALESCE(ZISDEL, 0) = 0") { it.str(0) }.firstOrNull()
        else null
    }.getOrNull()

    override fun close() {
        db.close()
        copy.delete()
    }

    private fun <T> query(sql: String, map: (Cursor) -> T): List<T> =
        db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
    /** A number column as plain decimal text, whether it's stored as text or as a float. */
    private fun Cursor.decimal(i: Int): String = when {
        isNull(i) -> "0"
        getType(i) == Cursor.FIELD_TYPE_FLOAT || getType(i) == Cursor.FIELD_TYPE_INTEGER -> java.math.BigDecimal.valueOf(getDouble(i)).toPlainString()
        else -> getString(i)
    }
    private fun Cursor.int(i: Int): Int? = if (isNull(i)) null else getString(i).trim().toDoubleOrNull()?.toInt()

    companion object {
        /** Throws [NotAMoneyManagerBackup] when the picked file is not a readable backup. */
        fun open(context: Context, uri: Uri): AndroidMoneyManagerSource {
            val copy = File(context.cacheDir, "import.mmbak")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open the file" }
                copy.outputStream().use { input.copyTo(it) }
            }
            val db = try {
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                copy.delete()
                throw NotAMoneyManagerBackup()
            }
            fun has(vararg tables: String) = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN (${tables.joinToString(",") { "'$it'" }})", null,
            ).use { it.count == tables.size }
            val android = has("INOUTCOME", "ASSETS", "ZCATEGORY")
            val ios = !android && has("ZINOUTCOME", "ZASSET", "ZASSETGROUP", "ZCATEGORY")
            if (!android && !ios) {
                db.close(); copy.delete()
                throw NotAMoneyManagerBackup()
            }
            return AndroidMoneyManagerSource(copy, db, ios)
        }
    }
}

/** Seconds from 1970-01-01 to 2001-01-01, where Apple's Core Data dates start. */
private const val CORE_DATA_EPOCH = 978_307_200.0

class NotAMoneyManagerBackup : Exception("This file isn't a Money Manager backup. In Money Manager, use Backup to save a .mmbak file, then pick that file.")
