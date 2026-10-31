package app.kassa.money.data.jobs

/**
 * How many days in a row the daily backup has failed, and whether the person has been told. After [ALERT_AFTER] failed days
 * one notification asks them to look; it is not repeated until a backup has worked again. Plain Kotlin, so the rule has JVM
 * tests; Settings keeps the three numbers.
 */
data class BackupStreak(val failedDays: Int = 0, val lastFailedDay: Long = -1, val alerted: Boolean = false) {

    /** What a failure leaves behind, and whether to notify now. */
    data class Outcome(val streak: BackupStreak, val notifyNow: Boolean)

    /**
     * A scheduled backup failed on [epochDay]. A second failure on the same day (a retry) isn't another day. The
     * notification is due once the streak reaches [ALERT_AFTER] and hasn't been shown for it.
     */
    fun failed(epochDay: Long): Outcome {
        val days = if (epochDay == lastFailedDay) failedDays else failedDays + 1
        val due = days >= ALERT_AFTER && !alerted
        return Outcome(copy(failedDays = days, lastFailedDay = epochDay), due)
    }

    /** The notification was shown (or the person already has the problem in front of them): no repeat for this streak. */
    fun wasAlerted(): BackupStreak = copy(alerted = true)

    /** A backup worked: the streak ends, and the next failure streak may alert again. */
    fun succeeded(): BackupStreak = BackupStreak()

    companion object {
        const val ALERT_AFTER = 3
    }
}
