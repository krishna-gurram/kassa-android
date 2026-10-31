package app.kassa.money.data.jobs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupStreakTest {
    @Test
    fun theThirdFailedDayInARowNotifiesOnce() {
        var s = BackupStreak()
        val first = s.failed(100); s = first.streak
        assertFalse(first.notifyNow)
        val second = s.failed(101); s = second.streak
        assertFalse(second.notifyNow)
        val third = s.failed(102); s = third.streak
        assertTrue(third.notifyNow)
        assertEquals(3, s.failedDays)
        s = s.wasAlerted()
        // Day four, five and more: no second notification.
        for (d in 103L..110L) {
            val o = s.failed(d); s = o.streak
            assertFalse(o.notifyNow, "day $d")
        }
    }

    @Test
    fun aRetryTheSameDayIsNotAnotherDay() {
        var s = BackupStreak()
        repeat(5) { s = s.failed(200).streak }
        assertEquals(1, s.failedDays)
        assertFalse(s.failed(200).notifyNow)
    }

    @Test
    fun aWorkingBackupEndsTheStreakAndTheNextOneMayNotifyAgain() {
        var s = BackupStreak()
        for (d in 1..3) s = s.failed(d.toLong()).streak.let { if (d == 3) it.wasAlerted() else it }
        assertTrue(s.alerted)
        s = s.succeeded()
        assertEquals(BackupStreak(), s)
        for (d in 10..11) assertFalse(s.failed(d.toLong()).also { s = it.streak }.notifyNow)
        assertTrue(s.failed(12).notifyNow)
    }

    @Test
    fun anAlertThatCouldNotBePostedIsOfferedAgain() {
        // Without notification permission nothing is shown, so wasAlerted isn't called and the next failed day asks again.
        var s = BackupStreak()
        for (d in 1..2) s = s.failed(d.toLong()).streak
        val third = s.failed(3)
        assertTrue(third.notifyNow)
        s = third.streak
        assertTrue(s.failed(4).notifyNow)
    }

    @Test
    fun daysWithNoRunDontBreakOrBumpTheStreak() {
        var s = BackupStreak()
        s = s.failed(1).streak
        s = s.failed(5).streak // the phone was off for days: still two failed days, not a success
        assertEquals(2, s.failedDays)
        assertTrue(s.failed(9).notifyNow)
    }
}
