package app.kassa.money

import android.app.Application
import app.kassa.money.data.CrashReport
import app.kassa.money.data.Settings
import app.kassa.money.data.backup.BackupRepository
import app.kassa.money.data.db.DatabaseOpen
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.security.DatabaseKey
import app.kassa.money.data.security.OpenProblem
import app.kassa.money.data.jobs.DailyJobs
import app.kassa.money.data.jobs.DueNotifier
import app.kassa.money.data.repo.AccountRepository
import app.kassa.money.data.repo.BillRepository
import app.kassa.money.data.repo.CategoryRepository
import app.kassa.money.data.repo.EntryRepository
import app.kassa.money.data.repo.ImportRepository
import app.kassa.money.data.repo.InvestmentRepository
import app.kassa.money.data.repo.LoanRepository
import app.kassa.money.data.repo.PeopleRepository
import java.io.File
import java.time.LocalDateTime

/** The app's single instances, wired by hand: few enough that a DI framework would cost more than it saves. */
class KassaApplication : Application() {
    /** The database, or why it can't be opened. Kept after the first try so every caller sees the same answer. */
    private var opened: DatabaseOpen? = null

    /**
     * Opens the database once and remembers the result. Blocks while it opens (and upgrades), so call it off the main
     * thread. A failure is returned, not thrown: the UI then shows a page for it instead of crash-looping.
     */
    @Synchronized
    fun openDatabase(): DatabaseOpen {
        opened?.let { return it }
        val result: DatabaseOpen = try {
            DatabaseOpen.Ready(KassaDatabase.open(this))
        } catch (e: Exception) {
            DatabaseOpen.Failed(OpenProblem.classify(e))
        }
        opened = result
        return result
    }

    /** Tries again after a failure (the cause may have been temporary). */
    @Synchronized
    fun retryOpenDatabase(): DatabaseOpen {
        (opened as? DatabaseOpen.Ready)?.let { return it }
        opened = null
        return openDatabase()
    }

    /**
     * "Start over" on the can't-open page: deletes the database and its key, forgets everything the phone remembers about
     * the old data, and opens a new empty database. Only ever called when the data could not be opened.
     */
    @Synchronized
    fun eraseAndStartOver(): DatabaseOpen {
        check(opened !is DatabaseOpen.Ready) { "Only unreadable data is erased." }
        opened = null
        deleteDatabase(KassaDatabase.FILE_NAME)
        DatabaseKey.delete(this)
        File(filesDir, "icons").deleteRecursively()
        settings.forgetAccountReferences()
        settings.startedFresh = false
        DueNotifier.forgetPosted(this)
        return openDatabase()
    }

    private val database: KassaDatabase
        get() = (openDatabase() as? DatabaseOpen.Ready)?.database ?: throw IllegalStateException("Kassa's data can't be opened.")

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

    /**
     * Forgets what the phone remembers by account id (Overview pins, account order, cleared and already-notified
     * payments). Called when accounts are replaced wholesale.
     */
    fun forgetAccountReferences() {
        settings.forgetAccountReferences()
        DueNotifier.forgetPosted(this)
        DueNotifier.refresh(this)
    }

    /** The last crash, kept on the phone for Settings to copy. Nothing is sent anywhere. */
    val crashFile: File get() = File(filesDir, "last-crash.txt")

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                // Class names and stack frames only: exception messages can hold the user's names, notes or amounts.
                crashFile.writeText("${LocalDateTime.now()} ${BuildConfigInfo.versionName}\n${CrashReport.describe(e)}")
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
