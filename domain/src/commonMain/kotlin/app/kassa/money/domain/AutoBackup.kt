package app.kassa.money.domain

/**
 * The rules of automatic backups into a chosen folder, for the iOS app (Android keeps the same rules in `data/jobs/BackupNames` and
 * `BackupStreak`, next to its JobScheduler job). Plain Kotlin with JVM tests; Swift only does the file work, the bookmark, the background
 * task and the notification.
 *
 * iOS cannot run anything at a set time, so a backup is due when the app becomes active and the last good one is more than a day old
 * ([isDue]); a best-effort background refresh may do it sooner, and nothing relies on it.
 */
object AutoBackupRules {

    /** How many backups stay in the folder. */
    const val KEEP = 30

    /** A backup is due once the last good one is this old. */
    const val DUE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

    /** After an attempt that did not work, the next one waits this long, so opening the app again and again does not retry each time. */
    const val RETRY_AFTER_MILLIS = 60L * 60L * 1000L

    private const val PREFIX = "Kassa_"
    private const val SUFFIX = ".kassa"

    /** The file name for a backup made at this wall-clock moment: Kassa_yyyyMMdd_HHmmss.kassa. */
    fun nameFor(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
        PREFIX + pad(year, 4) + pad(month, 2) + pad(day, 2) + "_" + pad(hour, 2) + pad(minute, 2) + pad(second, 2) + SUFFIX

    /** True when [name] is exactly a name [nameFor] makes (a real date and time). */
    fun isOurs(name: String): Boolean {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false
        if (name.length != PREFIX.length + 15 + SUFFIX.length) return false
        val stamp = name.substring(PREFIX.length, name.length - SUFFIX.length)
        if (stamp[8] != '_') return false
        for (i in stamp.indices) if (i != 8 && stamp[i] !in '0'..'9') return false
        val year = stamp.substring(0, 4).toInt()
        val month = stamp.substring(4, 6).toInt()
        val day = stamp.substring(6, 8).toInt()
        val hour = stamp.substring(9, 11).toInt()
        val minute = stamp.substring(11, 13).toInt()
        val second = stamp.substring(13, 15).toInt()
        if (month !in 1..12 || hour > 23 || minute > 59 || second > 59) return false
        return day in 1..daysIn(year, month)
    }

    /**
     * Of the names in the folder, the ones to delete: Kassa's own files beyond the newest [keep]. The stamp sorts by time, so newest is
     * first in descending name order. Anything not matching the pattern is never in the answer.
     */
    fun toDelete(names: Collection<String>, keep: Int = KEEP): List<String> =
        names.filter(::isOurs).distinct().sortedDescending().drop(keep.coerceAtLeast(1))

    /**
     * Whether to back up now. [lastSuccessMillis] is the last backup that worked (0 for never), [lastAttemptMillis] the last try of any
     * kind (0 for none). Due when there is no good backup, or the last is a day old (or dated in the future, which a wrong clock can do),
     * unless a try was made within the last hour.
     */
    fun isDue(lastSuccessMillis: Long, lastAttemptMillis: Long, nowMillis: Long): Boolean {
        val old = lastSuccessMillis <= 0L || kotlin.math.abs(nowMillis - lastSuccessMillis) >= DUE_AFTER_MILLIS
        if (!old) return false
        val recentTry = lastAttemptMillis > 0L && nowMillis >= lastAttemptMillis && nowMillis - lastAttemptMillis < RETRY_AFTER_MILLIS
        return !recentTry
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}

/**
 * How many days in a row an automatic backup has failed, and whether the person has been told. After [ALERT_AFTER] failed days one
 * notification asks them to look; it is not repeated until a backup has worked again. The same rule as Android's `BackupStreak`.
 */
data class AutoBackupStreak(val failedDays: Int = 0, val lastFailedDay: Long = -1, val alerted: Boolean = false) {

    /** What a failure leaves behind, and whether to notify now. */
    data class Outcome(val streak: AutoBackupStreak, val notifyNow: Boolean)

    /** A backup failed on [epochDay]. A second failure the same day is not another day. */
    fun failed(epochDay: Long): Outcome {
        val days = if (epochDay == lastFailedDay) failedDays else failedDays + 1
        val due = days >= ALERT_AFTER && !alerted
        return Outcome(copy(failedDays = days, lastFailedDay = epochDay), due)
    }

    /** The notification was shown: no repeat for this streak. */
    fun wasAlerted(): AutoBackupStreak = copy(alerted = true)

    /** A backup worked: the streak ends, and the next one may alert again. */
    fun succeeded(): AutoBackupStreak = AutoBackupStreak()

    companion object {
        const val ALERT_AFTER = 3
    }
}
