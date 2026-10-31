package app.kassa.money.data.jobs

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.DocumentsContract
import app.kassa.money.KassaApplication
import app.kassa.money.R
import app.kassa.money.data.Failures
import app.kassa.money.data.Failures.Doing
import app.kassa.money.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * The two things Kassa does by itself once a day, at times you set: save a backup to your folder, and remind you
 * to record the day's spending. No library and no network: nothing here talks to anything.
 *
 * - The reminder is an inexact alarm ([ReminderReceiver], allow-while-idle): it may arrive a few minutes late while
 *   the phone dozes, never a day late. Kassa asks for no exact-alarm permission.
 * - The backup is a JobScheduler job: it may wait for a quiet moment, which is fine for a backup, and it survives
 *   restarts. A pending job for the same time is left alone, so refreshing never pushes it to tomorrow.
 */
object DailyJobs {
    const val BACKUP = 101
    const val REMINDER = 102
    private const val CHANNEL = "daily_reminder"
    const val ACTION_ADD = "app.kassa.money.ADD"
    /** The "Backup needs attention" notification's tap: open Settings > Backup and restore. */
    const val ACTION_OPEN_BACKUP = "app.kassa.money.OPEN_BACKUP"
    private const val BACKUP_CHANNEL = "backup_problem"
    /** Notification id and request code of the backup notification; different from the reminder's and the due ones. */
    private const val BACKUP_ALERT = 104
    /** The time the pending backup job was booked for, kept in its extras. */
    private const val EXTRA_AT = "at"
    /** App-private, like Settings: when the reminder alarm is set for, so an overdue one isn't lost. */
    private const val PREFS = "kassa_jobs"
    private const val REMINDER_AT = "reminder_at"
    private const val OVERDUE_GRACE = 3 * 60 * 60_000L

    /**
     * Books or cancels both to match Settings, and brings the payments-due notifier up to date too ([done] runs when
     * that finishes, for a receiver's goAsync). Safe to call any time: what's already booked for the same time stays.
     */
    fun refresh(context: Context, done: () -> Unit = {}) {
        DueNotifier.refresh(context, done)
        val s = (context.applicationContext as KassaApplication).settings
        val js = context.getSystemService(JobScheduler::class.java)
        // Earlier versions booked the reminder as a job; the alarm replaces it.
        js?.cancel(REMINDER)
        if (s.autoBackup.value && s.backupFolder.value != null) scheduleBackup(context, s.backupMinute.value) else js?.cancel(BACKUP)
        if (s.reminder.value) scheduleReminder(context, s.reminderMinute.value) else cancelReminder(context)
    }

    /* ---------- backup ---------- */

    /**
     * Books the backup for the next [minuteOfDay], unless a pending job is already booked for exactly that time.
     * A job whose time has passed but hasn't run yet is overdue, not stale: it's kept so today's backup still happens.
     */
    private fun scheduleBackup(context: Context, minuteOfDay: Int, after: ZonedDateTime = ZonedDateTime.now(), keepOverdue: Boolean = true) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val next = DailyTime.next(after, minuteOfDay)
        val pending = js.getPendingJob(BACKUP)
        if (pending != null) {
            val bookedAt = pending.extras.getLong(EXTRA_AT, 0L)
            val bookedMinute = if (bookedAt > 0) Instant.ofEpochMilli(bookedAt).atZone(after.zone).let { it.hour * 60 + it.minute } else -1
            val sameTime = bookedAt == next.toInstant().toEpochMilli()
            val overdueForSameMinute = bookedAt in 1..after.toInstant().toEpochMilli() && bookedMinute == minuteOfDay
            if (sameTime || (keepOverdue && overdueForSameMinute)) return
        }
        val delay = Duration.between(after, next).toMillis().coerceAtLeast(0)
        js.schedule(
            JobInfo.Builder(BACKUP, ComponentName(context, DailyJobService::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 20 * 60_000L)
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putLong(EXTRA_AT, next.toInstant().toEpochMilli()) })
                .build()
        )
    }

    /**
     * After a backup ran: books tomorrow's, counted from a minute past now so today's time isn't picked again. The job
     * that just finished may still show as pending for a moment, so an overdue booking isn't kept here.
     */
    internal fun scheduleNextBackup(context: Context) {
        val s = (context.applicationContext as KassaApplication).settings
        if (s.autoBackup.value && s.backupFolder.value != null) scheduleBackup(context, s.backupMinute.value, ZonedDateTime.now().plusMinutes(1), keepOverdue = false)
    }

    /* ---------- reminder ---------- */

    /**
     * Sets the reminder alarm for the next [minuteOfDay]. Setting it again for the same time just replaces it.
     * A reminder that was due within the last few hours and hasn't gone off yet (the phone restarted, or Android
     * held back an inexact alarm) is set for its own time, which goes off right away, rather than skipped to tomorrow.
     */
    internal fun scheduleReminder(context: Context, minuteOfDay: Int, after: ZonedDateTime = ZonedDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val booked = prefs.getLong(REMINDER_AT, 0L)
        val bookedMinute = Instant.ofEpochMilli(booked).atZone(after.zone).let { it.hour * 60 + it.minute }
        val now = after.toInstant().toEpochMilli()
        val overdue = booked in (now - OVERDUE_GRACE)..now && bookedMinute == minuteOfDay
        val at = if (overdue) booked else DailyTime.next(after, minuteOfDay).toInstant().toEpochMilli()
        prefs.edit().putLong(REMINDER_AT, at).apply()
        val pi = reminderIntent(context)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /**
     * True when the alarm that just went off is the one booked, not a repeat: a refresh while the alarm was being
     * delivered can set the same overdue time again, and that second delivery finds tomorrow already booked.
     */
    internal fun reminderDue(context: Context): Boolean {
        val booked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(REMINDER_AT, 0L)
        return booked == 0L || booked <= System.currentTimeMillis() + 60_000L
    }

    /** After the reminder went off: tomorrow's, counted from a minute past now so today's time isn't picked again. */
    internal fun reminderFired(context: Context, minuteOfDay: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(REMINDER_AT).apply()
        scheduleReminder(context, minuteOfDay, ZonedDateTime.now().plusMinutes(1))
    }

    private fun cancelReminder(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(REMINDER_AT).apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(reminderIntent(context))
    }

    private fun reminderIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REMINDER, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Saves a backup with no password into the chosen folder as Kassa_yyyyMMdd_HHmmss.kassa, the way Money Manager names its
     * files, then keeps the newest [BackupNames.KEEP] of Kassa's own files there and deletes older ones (a file that doesn't
     * carry exactly that naming pattern is never touched). Returns the file's name. A failure deletes the half-written file,
     * is kept as the status Settings shows, and is thrown as a [Failures.UserFacing] whose message is safe to show.
     */
    suspend fun backupNow(context: Context): String {
        val app = context.applicationContext as KassaApplication
        val s = app.settings
        val resolver = context.contentResolver
        var doc: Uri? = null
        try {
            val folder = s.backupFolder.value ?: throw Failures.UserFacing("Pick a backup folder first.")
            val tree = Uri.parse(folder)
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val name = BackupNames.nameFor(LocalDateTime.now())
            val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                ?: throw FileNotFoundException("The folder can't be written to")
            doc = created
            val out = resolver.openOutputStream(created) ?: throw FileNotFoundException("The file can't be opened")
            out.use { app.backups.write(it, null) }
            s.backupDone(System.currentTimeMillis())
            // The streak of failed days ends with a backup that worked, and so does the notification about it.
            s.setBackupStreak(s.backupStreak().succeeded())
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(BACKUP_ALERT) }
            // Tidying is secondary: the backup is safe even if an old file can't be deleted.
            runCatching { trimOldBackups(context, tree, parentId) }
            return name
        } catch (e: CancellationException) {
            doc?.let { deletePartial(context, it) }
            throw e
        } catch (e: Throwable) {
            // Never leave a half-written .kassa file in the folder: it would look like a backup and not open.
            doc?.let { deletePartial(context, it) }
            val message = if (e is Failures.UserFacing) e.message.orEmpty() else Failures.message(e, Doing.WRITE_BACKUP)
            s.backupFailed(message)
            throw Failures.UserFacing(message, e)
        }
    }

    private fun deletePartial(context: Context, doc: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
    }

    /** Deletes Kassa's own backups in the folder beyond the newest [BackupNames.KEEP]; see [BackupNames.toDelete]. */
    private fun trimOldBackups(context: Context, tree: Uri, parentId: String) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val files = ArrayList<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val displayName = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                files.add(id to displayName)
            }
        }
        val doomed = BackupNames.toDelete(files.map { it.second }).toSet()
        files.filter { it.second in doomed }.forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /**
     * The backup the daily job runs: [backupNow], and what a day's result means. A failure counts toward the streak of failed days; on
     * the third, one private notification asks the person to look. A success ends the streak (see [backupNow]).
     */
    suspend fun scheduledBackup(context: Context) {
        val s = (context.applicationContext as KassaApplication).settings
        try {
            backupNow(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val outcome = s.backupStreak().failed(LocalDate.now().toEpochDay())
            // Marked as told only when the notification really went out: without permission it is offered again the next failed day.
            s.setBackupStreak(if (outcome.notifyNow && alertBackupProblem(context)) outcome.streak.wasAlerted() else outcome.streak)
        }
    }

    /**
     * "Backup needs attention": no amounts, nothing from the data, private on the lock screen; tapping it opens Settings > Backup.
     * Returns false when it can't be shown (notifications aren't allowed).
     */
    private fun alertBackupProblem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(NotificationChannel(BACKUP_CHANNEL, "Backup problems", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_BACKUP).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, BACKUP_ALERT, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = android.app.Notification.Builder(context, BACKUP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kassa)
            .setContentTitle("Backup needs attention")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        val n = android.app.Notification.Builder(context, BACKUP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kassa)
            .setContentTitle("Backup needs attention")
            .setContentText("Kassa couldn't save your last few daily backups. Tap to see why.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        nm.notify(BACKUP_ALERT, n)
        return true
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
            // Hide balances: this text has no amount. Any notification text that does (the due notifications) must build it with Money.format, which masks while hidden, so no amount reaches the lock screen.
            .setContentTitle("Have you recorded today's transactions?")
            .setContentText("Tap to add anything you spent or received today.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(REMINDER, n)
    }
}

/**
 * Runs the daily backup, then books tomorrow's. The next one is booked after [jobFinished], so this job is never
 * replaced while it runs. Reminder jobs left by earlier versions are cleared and moved to the alarm.
 */
class DailyJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            when (params.jobId) {
                DailyJobs.BACKUP -> runCatching { DailyJobs.scheduledBackup(applicationContext) }
                DailyJobs.REMINDER -> Unit
            }
            jobFinished(params, false)
            when (params.jobId) {
                DailyJobs.BACKUP -> runCatching { DailyJobs.scheduleNextBackup(applicationContext) }
                // A reminder job left by an earlier version: refresh cancels it and sets the alarm instead.
                DailyJobs.REMINDER -> runCatching { DailyJobs.refresh(applicationContext) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** The reminder alarm went off: posts the reminder and sets tomorrow's. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = (context.applicationContext as KassaApplication).settings
        if (!s.reminder.value) return
        if (DailyJobs.reminderDue(context)) runCatching { DailyJobs.remind(context) }
        DailyJobs.reminderFired(context, s.reminderMinute.value)
    }
}

/**
 * Sets the reminder, backup and payments-due alarm again when something may have dropped or moved them: a restart, an update, a clock or
 * time zone change.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        runCatching { DailyJobs.refresh(context) { runCatching { pending.finish() } } }.onFailure { runCatching { pending.finish() } }
    }
}
