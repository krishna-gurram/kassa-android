package dev.krishnagurram.kassa.data.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.R
import dev.krishnagurram.kassa.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The two things Kassa does by itself once a day, at times you set: save a backup to your folder, and remind you
 * to record the day's spending. Android's JobScheduler runs them near the set time (it can be a few minutes late
 * while the phone is idle) and keeps them across restarts. No library and no network: nothing here talks to anything.
 */
object DailyJobs {
    const val BACKUP = 101
    const val REMINDER = 102
    private const val CHANNEL = "daily_reminder"
    const val ACTION_ADD = "dev.krishnagurram.kassa.ADD"

    /** Schedules or cancels both jobs to match Settings. Safe to call any time; it replaces what was there. */
    fun refresh(context: Context) {
        val s = (context.applicationContext as KassaApplication).settings
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (s.autoBackup.value && s.backupFolder.value != null) schedule(context, js, BACKUP, s.backupMinute.value) else js.cancel(BACKUP)
        if (s.reminder.value) schedule(context, js, REMINDER, s.reminderMinute.value) else js.cancel(REMINDER)
    }

    private fun schedule(context: Context, js: JobScheduler, id: Int, minuteOfDay: Int) {
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        js.schedule(
            JobInfo.Builder(id, ComponentName(context, DailyJobService::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 20 * 60_000L)
                .setPersisted(true)
                .build()
        )
    }

    /**
     * Saves a backup with no password into the chosen folder as Kassa_yyyyMMdd_HHmmss.kassa, the way Money Manager
     * names its files. Every backup is kept; nothing is ever deleted. Returns the file's name.
     */
    suspend fun backupNow(context: Context): String {
        val app = context.applicationContext as KassaApplication
        val s = app.settings
        try {
            val folder = s.backupFolder.value ?: throw IllegalStateException("Pick a backup folder first.")
            val tree = Uri.parse(folder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = "Kassa_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".kassa"
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
                ?: throw IllegalStateException("The backup folder can't be written to. Pick it again.")
            context.contentResolver.openOutputStream(doc)!!.use { app.backups.write(it, null) }
            s.backupDone(System.currentTimeMillis())
            return name
        } catch (e: SecurityException) {
            s.backupFailed("Kassa lost access to the backup folder. Pick it again.")
            throw IllegalStateException("Kassa lost access to the backup folder. Pick it again.")
        } catch (e: Exception) {
            s.backupFailed(e.message ?: "The backup couldn't be saved.")
            throw e
        }
    }

    /** The daily nudge. Tapping it opens Add. Does nothing if notifications aren't allowed. */
    fun remind(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_ADD).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kassa)
            .setContentTitle("Have you recorded today's transactions?")
            .setContentText("Tap to add anything you spent or received today.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(REMINDER, n)
    }
}

/** Runs a daily job, then books the next one for tomorrow at the same time. */
class DailyJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            when (params.jobId) {
                DailyJobs.BACKUP -> runCatching { DailyJobs.backupNow(applicationContext) }
                DailyJobs.REMINDER -> runCatching { DailyJobs.remind(applicationContext) }
            }
            DailyJobs.refresh(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
