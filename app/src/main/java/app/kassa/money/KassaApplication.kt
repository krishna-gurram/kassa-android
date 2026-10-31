package app.kassa.money

import android.app.Application
import app.kassa.money.data.Settings
import app.kassa.money.data.backup.BackupRepository
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.jobs.DailyJobs
import app.kassa.money.data.repo.AccountRepository
import app.kassa.money.data.repo.BillRepository
import app.kassa.money.data.repo.CategoryRepository
import app.kassa.money.data.repo.EntryRepository
import app.kassa.money.data.repo.ImportRepository
import app.kassa.money.data.repo.InvestmentRepository
import app.kassa.money.data.repo.LoanRepository
import app.kassa.money.data.repo.PeopleRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/** The app's single instances, wired by hand: few enough that a DI framework would cost more than it saves. */
class KassaApplication : Application() {
    private val database by lazy { KassaDatabase.open(this) }

    val settings by lazy { Settings(this) }
    val entries by lazy { EntryRepository(database) }
    val accounts by lazy { AccountRepository(database) }
    val categories by lazy { CategoryRepository(database) }
    val people by lazy { PeopleRepository(database) }
    val bills by lazy { BillRepository(database) }
    val investments by lazy { InvestmentRepository(database, entries) }
    val loans by lazy { LoanRepository(database, accounts, categories) }
    val imports by lazy { ImportRepository(database) }
    val backups by lazy { BackupRepository(this, database, settings) }

    /** The last crash, kept on the phone for Settings to copy. Nothing is sent anywhere. */
    val crashFile: File get() = File(filesDir, "last-crash.txt")

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
                crashFile.writeText("${LocalDateTime.now()} ${BuildConfigInfo.versionName}\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
        // Loading settings sets the currency and the hide-balances hook before anything formats an amount.
        settings.currency
        // Re-book the daily backup and reminder in case something dropped them. Refresh keeps what's already booked
        // for the same time, so a process started to run the backup or reminder doesn't push it to tomorrow.
        runCatching { DailyJobs.refresh(this) }
    }
}
