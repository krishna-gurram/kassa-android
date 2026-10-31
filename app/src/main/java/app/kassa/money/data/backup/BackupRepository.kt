package app.kassa.money.data.backup

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import app.kassa.money.data.Settings
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.domain.Currencies
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Saves everything to a backup file and brings it back. The database file itself can't move to another phone
 * (its key lives in this phone's Keystore), so this is how data moves to a new phone or survives a reset.
 */
class BackupRepository(private val context: Context, private val db: KassaDatabase, private val settings: Settings) {

    private val iconDir get() = File(context.filesDir, "icons")

    /** Writes a backup; a null or empty [password] means a file anyone can open. */
    suspend fun write(out: OutputStream, password: CharArray?) {
        val contents = BackupContents(
            createdAt = System.currentTimeMillis(),
            monthStartDay = settings.monthStartDay.value,
            currency = settings.currency.value,
            groups = db.groups().all(),
            accounts = db.accounts().all(),
            categories = db.categories().all(),
            transactions = db.transactions().all(),
            budgets = db.budgets().all(),
            people = db.people().all(),
            loans = db.loans().all(),
            bills = db.recurring().all(),
            holdings = db.holdings().all(),
            trades = db.trades().all(),
            loanTerms = db.loanTerms().all(),
            loanChanges = db.loanChanges().all(),
            loanPayments = db.loanPayments().all(),
            icons = iconDir.listFiles()?.associate { it.name to it.readBytes() }.orEmpty(),
        )
        out.write(BackupFile.encode(contents.toJson { Base64.encodeToString(it, Base64.NO_WRAP) }, password))
    }

    /** Reads a backup without changing anything. Throws the [BackupFile] errors with messages to show. */
    fun read(input: InputStream, password: CharArray?): BackupContents =
        BackupContents.fromJson(BackupFile.decode(input.readBytes(), password)) { Base64.decode(it, Base64.NO_WRAP) }

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
            db.loanTerms().insertAll(c.loanTerms)
            db.loanChanges().insertAll(c.loanChanges)
            db.loanPayments().insertAll(c.loanPayments)
        }
        if (c.icons.isNotEmpty()) iconDir.apply { mkdirs() }.let { dir -> c.icons.forEach { (name, bytes) -> File(dir, File(name).name).writeBytes(bytes) } }
        settings.setMonthStartDay(c.monthStartDay)
        c.currency?.takeIf { code -> Currencies.all.any { it.code == code } }?.let(settings::setCurrency)
    }
}
