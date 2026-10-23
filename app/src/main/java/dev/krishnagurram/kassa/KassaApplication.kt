package dev.krishnagurram.kassa

import android.app.Application
import dev.krishnagurram.kassa.data.AssistRepository
import dev.krishnagurram.kassa.data.Backup
import dev.krishnagurram.kassa.data.KassaRepository
import dev.krishnagurram.kassa.data.Settings
import dev.krishnagurram.kassa.data.db.KassaDatabase

/** Hand-wired dependencies. Small enough that a DI framework would cost more than it saves. */
class KassaApplication : Application() {
    private val database: KassaDatabase by lazy { KassaDatabase.open(this) }
    val repository: KassaRepository by lazy { KassaRepository(database) }
    val assist: AssistRepository by lazy { AssistRepository(database, repository) }
    val settings: Settings by lazy { Settings(this) }
    val backup: Backup by lazy { Backup(this, database, settings) }

    override fun onCreate() {
        super.onCreate()
        // Keep the last crash on the phone so it can be copied from Settings; nothing is sent anywhere.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                crashFile().writeText("${java.time.LocalDateTime.now()} ${BuildConfigInfo.versionName}\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
        // Reading the settings sets the currency before anything formats an amount, including bank alerts.
        settings.currency
        // Keep the daily backup and reminder booked; cheap, and fixes anything a restart or update dropped.
        runCatching { dev.krishnagurram.kassa.data.jobs.DailyJobs.refresh(this) }
    }

    fun crashFile(): java.io.File = java.io.File(filesDir, "last-crash.txt")
}
