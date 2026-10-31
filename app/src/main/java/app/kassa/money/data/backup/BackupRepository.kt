package app.kassa.money.data.backup

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import app.kassa.money.data.Settings
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.jobs.DueNotifier
import app.kassa.money.domain.Currencies
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Saves everything to a backup file and brings it back. The database file itself can't move to another phone
 * (its key lives in this phone's Keystore), so this is how data moves to a new phone or survives a reset.
 */
class BackupRepository(private val context: Context, private val db: KassaDatabase, private val settings: Settings) {

    private val iconDir get() = File(context.filesDir, "icons")

    private companion object {
        /** Entries are written this many at a time. */
        const val PAGE = 2_000
    }

    /**
     * Writes a backup; a null or empty [password] means a file anyone can open. The tables are read in one database
     * transaction, so the file is one moment of the data, and written one table at a time (the entries in pages), straight
     * into the gzip and cipher: no JSON text and no tree of it ever exists. What is held meanwhile is the compressed file,
     * a small fraction of the data, so the transaction ends before [out] (which may be a slow cloud folder) is touched.
     */
    suspend fun write(out: OutputStream, password: CharArray?) {
        val packed = ByteArrayOutputStream()
        val sink = BackupFile.sink(packed, password)
        try {
            db.withTransaction {
                val w = BackupWriter(JsonOut(sink.writer)) { Base64.encodeToString(it, Base64.NO_WRAP) }
                w.begin(System.currentTimeMillis(), settings.monthStartDay.value, settings.currency.value, settings.accountArrangement.value?.encode())
                w.groups(db.groups().all())
                w.accounts(db.accounts().all())
                w.categories(db.categories().all())
                w.beginTransactions()
                var after = 0L
                while (true) {
                    val page = db.transactions().page(after, PAGE)
                    if (page.isEmpty()) break
                    w.transactions(page)
                    after = page.last().id
                    if (page.size < PAGE) break
                }
                w.endTransactions()
                w.budgets(db.budgets().all())
                w.people(db.people().all())
                w.loans(db.loans().all())
                w.bills(db.recurring().all())
                w.holdings(db.holdings().all())
                w.trades(db.trades().all())
                w.loanTerms(db.loanTerms().all())
                w.loanChanges(db.loanChanges().all())
                w.loanPayments(db.loanPayments().all())
                w.investmentTransfers(db.investmentTransfers().all())
                w.beginIcons()
                // The photos are files, not rows: one at a time, so they aren't all in memory together.
                iconDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { w.icon(it.name, it.readBytes()) }
                w.endIcons()
                w.end()
            }
            sink.close()
        } catch (e: Throwable) {
            runCatching { sink.close() }
            throw e
        }
        packed.writeTo(out)
    }

    /**
     * Reads a backup without changing anything. Throws the [BackupFile] errors, whose messages are written to show:
     * [BackupFile.NotABackup], [BackupFile.NeedsPassword], [BackupFile.WrongPassword], [BackupFile.NewerVersion] and
     * [BackupFile.TooLarge].
     */
    fun read(input: InputStream, password: CharArray?): BackupContents =
        BackupFile.open(input, password) { reader -> BackupContents.readJson(reader) { Base64.decode(it, Base64.NO_WRAP) } }

    /** Replaces everything with [c], in one database transaction, then its photos and settings. */
    suspend fun restore(c: BackupContents) {
        db.withTransaction {
            db.transactions().deleteAll(); db.budgets().deleteAll(); db.categories().deleteAll(); db.accounts().deleteAll(); db.groups().deleteAll()
            db.people().deleteAll(); db.loans().deleteAll(); db.recurring().deleteAll(); db.holdings().deleteAll(); db.investmentTransfers().deleteAll()
            db.trades().deleteAll(); db.loanTerms().deleteAll(); db.loanChanges().deleteAll(); db.loanPayments().deleteAll()
            db.groups().insertAll(c.groups)
            db.accounts().insertAll(c.accounts)
            db.categories().insertAll(c.categories)
            db.transactions().insertAll(c.transactions)
            db.budgets().insertAll(c.budgets)
            db.people().insertAll(c.people)
            db.loans().insertAll(c.loans)
            db.recurring().insertAll(c.bills)
            db.holdings().insertAll(c.holdings)
            db.trades().insertAll(c.trades)
            db.investmentTransfers().insertAll(c.investmentTransfers)
            db.loanTerms().insertAll(c.loanTerms)
            db.loanChanges().insertAll(c.loanChanges)
            db.loanPayments().insertAll(c.loanPayments)
        }
        // The photos come after the data is safely in. One that can't be written is a missing picture, not a lost ledger.
        if (c.icons.isNotEmpty()) iconDir.apply { mkdirs() }.let { dir ->
            c.icons.forEach { (name, bytes) ->
                val file = File(name).name.takeIf { it.isNotBlank() && it != "." && it != ".." }
                if (file != null) runCatching { File(dir, file).writeBytes(bytes) }
            }
        }
        // Pins, cleared notifications and notified due dates name the old accounts' ids.
        settings.forgetAccountReferences()
        DueNotifier.forgetPosted(context)
        settings.setMonthStartDay(c.monthStartDay)
        c.currency?.takeIf { code -> Currencies.all.any { it.code == code } }?.let(settings::setCurrency)
        // An older backup has none: keep whatever order this phone has.
        app.kassa.money.domain.AccountArrangement.decode(c.accountArrangement)?.let(settings::setAccountArrangement)
    }
}
