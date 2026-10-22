package dev.krishnagurram.kassa.data.mm

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dev.krishnagurram.kassa.importer.MmAsset
import dev.krishnagurram.kassa.importer.MmBudget
import dev.krishnagurram.kassa.importer.MmCategory
import dev.krishnagurram.kassa.importer.MmGroup
import dev.krishnagurram.kassa.importer.MmTransaction
import dev.krishnagurram.kassa.importer.MoneyManagerSource
import java.io.Closeable
import java.io.File

/**
 * Reads a Money Manager .mmbak (a plain SQLite file) that the user picked. The file is copied into the app's
 * private cache, opened read-only, and deleted again in [close]. Nothing is written back to the original.
 */
class AndroidMoneyManagerSource private constructor(
    private val copy: File,
    private val db: SQLiteDatabase,
) : MoneyManagerSource, Closeable {

    override fun groups(): List<MmGroup> = query(
        "SELECT uid, ACC_GROUP_NAME, TYPE, ORDERSEQ FROM ASSETGROUP WHERE IS_DEL IS NULL OR IS_DEL = 0"
    ) { MmGroup(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 7, it.int(3) ?: 0) }

    override fun assets(): List<MmAsset> = query(
        "SELECT uid, NIC_NAME, groupUid, ORDERSEQ, CARD_DAY_FIN, CARD_DAY_PAY, ZDATA FROM ASSETS"
    ) { MmAsset(it.str(0)!!, it.str(1).orEmpty(), it.str(2), it.int(3) ?: 0, it.int(4), it.int(5), it.str(6)) }

    override fun categories(): List<MmCategory> = query(
        "SELECT uid, NAME, TYPE, pUid, C_IS_DEL, ORDERSEQ FROM ZCATEGORY"
    ) { MmCategory(it.str(0)!!, it.str(1).orEmpty(), it.int(2) ?: 1, it.str(3), it.int(4) == 1, it.int(5) ?: 0) }

    override fun transactions(): List<MmTransaction> = query(
        """SELECT uid, DO_TYPE, assetUid, toAssetUid, ctgUid, ZMONEY, ZDATE, ZCONTENT, txUidTrans
           FROM INOUTCOME WHERE IS_DEL IS NULL OR IS_DEL = 0"""
    ) {
        MmTransaction(
            uid = it.str(0)!!, doType = it.int(1) ?: -1, assetUid = it.str(2).orEmpty(), toAssetUid = it.str(3),
            categoryUid = it.str(4), amount = it.str(5) ?: "0", epochMillis = it.str(6)?.toLongOrNull() ?: 0L,
            note = it.str(7), pairKey = it.str(8),
        )
    }

    override fun budgets(): List<MmBudget> = query(
        """SELECT b.targetUid, a.BUDGET_PERIOD, a.AMOUNT FROM BUDGET b JOIN BUDGET_AMOUNT a ON a.budgetUid = b.uid
           WHERE COALESCE(a.IS_DEL, 0) = 0 AND COALESCE(b.IS_DEL, 0) = 0"""
    ) { MmBudget(it.str(0).orEmpty(), it.int(1) ?: 0, it.getDouble(2)) }

    override fun close() {
        db.close()
        copy.delete()
    }

    private fun <T> query(sql: String, map: (Cursor) -> T): List<T> =
        db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
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
            val ok = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('INOUTCOME','ASSETS','ZCATEGORY')", null)
                .use { it.count == 3 }
            if (!ok) {
                db.close(); copy.delete()
                throw NotAMoneyManagerBackup()
            }
            return AndroidMoneyManagerSource(copy, db)
        }
    }
}

class NotAMoneyManagerBackup : Exception("This file isn't a Money Manager backup. In Money Manager, use Backup to save a .mmbak file, then pick that file.")
