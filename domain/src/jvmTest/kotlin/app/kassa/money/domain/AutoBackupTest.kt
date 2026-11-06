package app.kassa.money.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupTest {

    private val day = AutoBackupRules.DUE_AFTER_MILLIS

    @Test
    fun nameHasTheAndroidPattern() {
        assertEquals("Kassa_20261105_093007.kassa", AutoBackupRules.nameFor(2026, 11, 5, 9, 30, 7))
        assertTrue(AutoBackupRules.isOurs(AutoBackupRules.nameFor(2026, 11, 5, 9, 30, 7)))
    }

    @Test
    fun onlyExactNamesAreOurs() {
        assertTrue(AutoBackupRules.isOurs("Kassa_20240229_235959.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20230229_000000.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261301_000000.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261105_240000.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261105_093007 (1).kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261105_093007.kassa.part"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261105-093007.kassa"))
        assertFalse(AutoBackupRules.isOurs("kassa_20261105_093007.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_2026110_0930071.kassa"))
        assertFalse(AutoBackupRules.isOurs("Kassa_20261105_093007.KASSA"))
        assertFalse(AutoBackupRules.isOurs("Kassa_2026１105_093007.kassa"))
        assertFalse(AutoBackupRules.isOurs("notes.txt"))
    }

    @Test
    fun keepsTheNewestThirtyAndNeverTouchesOtherFiles() {
        val ours = (1..35).map { AutoBackupRules.nameFor(2026, 10, it.coerceAtMost(28), 8, 0, it) }
        val others = listOf("holiday.jpg", "Kassa_20261105_093007 (1).kassa", "Kassa_copy.kassa", "Kassa_20261105_093007.kassa.part")
        val doomed = AutoBackupRules.toDelete(ours + others)
        assertEquals(5, doomed.size)
        assertEquals(ours.sorted().take(5).sortedDescending(), doomed)
        assertTrue(doomed.none { it in others })
    }

    @Test
    fun fewerThanThirtyDeletesNothingAndDuplicatesCountOnce() {
        val a = AutoBackupRules.nameFor(2026, 1, 1, 1, 1, 1)
        assertTrue(AutoBackupRules.toDelete(listOf(a, a)).isEmpty())
        assertEquals(listOf(a), AutoBackupRules.toDelete(listOf(a, AutoBackupRules.nameFor(2026, 1, 2, 1, 1, 1)), keep = 1))
    }

    @Test
    fun dueWhenNeverBackedUpOrADayOld() {
        val now = 10 * day
        assertTrue(AutoBackupRules.isDue(0, 0, now))
        assertFalse(AutoBackupRules.isDue(now - day + 1, 0, now))
        assertTrue(AutoBackupRules.isDue(now - day, 0, now))
        assertTrue(AutoBackupRules.isDue(now - 5 * day, 0, now))
    }

    @Test
    fun aClockSetBackIsDueToo() {
        val now = 10 * day
        assertTrue(AutoBackupRules.isDue(now + 3 * day, 0, now))
        assertFalse(AutoBackupRules.isDue(now + 1000, 0, now))
    }

    @Test
    fun aFailedTryWaitsAnHour() {
        val now = 10 * day
        val old = now - 2 * day
        assertFalse(AutoBackupRules.isDue(old, now - 10 * 60 * 1000L, now))
        assertTrue(AutoBackupRules.isDue(old, now - AutoBackupRules.RETRY_AFTER_MILLIS, now))
        assertTrue(AutoBackupRules.isDue(old, now + 5 * day, now))
    }

    @Test
    fun theThirdFailedDayInARowNotifiesOnce() {
        var s = AutoBackupStreak()
        val first = s.failed(100); s = first.streak
        assertFalse(first.notifyNow)
        val second = s.failed(101); s = second.streak
        assertFalse(second.notifyNow)
        val third = s.failed(102); s = third.streak
        assertTrue(third.notifyNow)
        assertEquals(3, s.failedDays)
        s = s.wasAlerted()
        for (d in 103L..110L) {
            val o = s.failed(d); s = o.streak
            assertFalse(o.notifyNow, "day $d")
        }
    }

    @Test
    fun aRetryTheSameDayIsNotAnotherDay() {
        var s = AutoBackupStreak()
        repeat(5) { s = s.failed(200).streak }
        assertEquals(1, s.failedDays)
    }

    @Test
    fun aWorkingBackupEndsTheStreakAndAnUnpostedAlertIsOfferedAgain() {
        var s = AutoBackupStreak()
        for (d in 1L..2L) s = s.failed(d).streak
        val third = s.failed(3)
        assertTrue(third.notifyNow)
        assertTrue(third.streak.failed(4).notifyNow)
        s = third.streak.wasAlerted().succeeded()
        assertEquals(AutoBackupStreak(), s)
    }
}
