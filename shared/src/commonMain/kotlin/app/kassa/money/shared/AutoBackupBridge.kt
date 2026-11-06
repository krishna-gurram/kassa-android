package app.kassa.money.shared

import app.kassa.money.domain.AutoBackupRules
import app.kassa.money.domain.AutoBackupStreak

/**
 * What the failed-day streak of automatic backups looks like to Swift: three numbers it keeps in UserDefaults, plus [notifyNow] on the
 * answer to a failure ("Backup needs attention" is due).
 */
data class BackupStreakData(val failedDays: Int, val lastFailedDay: Long, val alerted: Boolean, val notifyNow: Boolean)

/**
 * Automatic backups on iOS for Swift (`AutoBackupBridge.shared`): whether a backup is due, which files in the folder are Kassa's own and
 * which of those to delete, and the failed-day streak. Every rule is `AutoBackupRules` / `AutoBackupStreak` in :domain; Swift only does
 * the folder bookmark, the file work, the background refresh and the notification. Times are epoch milliseconds (0 for never).
 */
object AutoBackupBridge {

    fun keep(): Int = AutoBackupRules.KEEP

    fun isDue(lastSuccessMillis: Long, lastAttemptMillis: Long, nowMillis: Long): Boolean =
        AutoBackupRules.isDue(lastSuccessMillis, lastAttemptMillis, nowMillis)

    fun nameFor(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
        AutoBackupRules.nameFor(year, month, day, hour, minute, second)

    fun isOurs(name: String): Boolean = AutoBackupRules.isOurs(name)

    /** The names (of those given) to delete: Kassa's own beyond the newest [keep()]. Nothing else is ever in the answer. */
    fun toDelete(names: List<String>): List<String> = AutoBackupRules.toDelete(names)

    /** The streak after a failure on [epochDay]. */
    fun afterFailure(failedDays: Int, lastFailedDay: Long, alerted: Boolean, epochDay: Long): BackupStreakData {
        val outcome = AutoBackupStreak(failedDays, lastFailedDay, alerted).failed(epochDay)
        return flat(outcome.streak, outcome.notifyNow)
    }

    /** The streak once the notification was really shown. */
    fun afterAlert(failedDays: Int, lastFailedDay: Long, alerted: Boolean): BackupStreakData =
        flat(AutoBackupStreak(failedDays, lastFailedDay, alerted).wasAlerted(), false)

    /** The streak after a backup that worked: no failed days. */
    fun afterSuccess(): BackupStreakData = flat(AutoBackupStreak().succeeded(), false)

    private fun flat(streak: AutoBackupStreak, notifyNow: Boolean): BackupStreakData =
        BackupStreakData(streak.failedDays, streak.lastFailedDay, streak.alerted, notifyNow)
}
