package app.kassa.money.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.kassa.money.data.cashew.AndroidCashewSource
import app.kassa.money.importer.NotReadableBackup
import java.io.File

/** What kind of file the user picked on the Import page, worked out from what's inside it rather than its name. */
sealed interface PickedFile {
    data object MoneyManager : PickedFile
    /** A copy Kassa owns, ready for [AndroidCashewSource.open]. */
    data class Cashew(val copy: File) : PickedFile

    companion object {
        private const val SUPPORTED = "Kassa reads Money Manager backups (.mmbak) and Cashew backups (.sql)."

        /** Throws [NotReadableBackup] with a message that says what to do next. Nothing in Kassa changes either way. */
        fun identify(context: Context, uri: Uri): PickedFile {
            val copy = File(context.cacheDir, "import-picked.db")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw NotReadableBackup("That file couldn't be opened. Try picking it again.")
                copy.outputStream().use { input.copyTo(it) }
            }
            if (copy.length() == 0L) { copy.delete(); throw NotReadableBackup("The file is empty. Back up again and pick the new file.") }
            val head = copy.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            if (!String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3")) {
                val text = head.all { it.toInt() == 9 || it.toInt() == 10 || it.toInt() == 13 || it in 32..126 || it < 0 }
                copy.delete()
                throw NotReadableBackup(if (text) "This looks like a spreadsheet or text file. Kassa can't import those yet. $SUPPORTED" else "This isn't a backup Kassa can read. $SUPPORTED")
            }
            val db = try {
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                copy.delete()
                throw NotReadableBackup("The file is damaged or cut short. Back up again and pick the new file.")
            }
            val kind = try {
                fun has(vararg t: String) = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN (${t.joinToString(",") { "'$it'" }})", null,
                ).use { it.count == t.size }
                when {
                    has("INOUTCOME", "ASSETS", "ZCATEGORY") || has("ZINOUTCOME", "ZASSET", "ZASSETGROUP", "ZCATEGORY") -> "mm"
                    AndroidCashewSource.looksLikeCashew(db) -> "cashew"
                    else -> null
                }
            } catch (e: Exception) {
                copy.delete()
                throw NotReadableBackup("The file is damaged or cut short. Back up again and pick the new file.")
            } finally {
                runCatching { db.close() }
            }
            return when (kind) {
                "mm" -> { copy.delete(); MoneyManager }
                "cashew" -> Cashew(File(context.cacheDir, "import-cashew.db").also { copy.renameTo(it) })
                else -> { copy.delete(); throw NotReadableBackup("This isn't a backup Kassa can read. $SUPPORTED") }
            }
        }
    }
}
