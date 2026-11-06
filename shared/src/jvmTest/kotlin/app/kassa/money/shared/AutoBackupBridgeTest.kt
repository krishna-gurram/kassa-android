package app.kassa.money.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupBridgeTest {

    @Test
    fun namesAndDeletionCrossAsPlainValues() {
        val name = AutoBackupBridge.nameFor(2026, 11, 5, 9, 30, 7)
        assertEquals("Kassa_20261105_093007.kassa", name)
        assertTrue(AutoBackupBridge.isOurs(name))
        assertEquals(30, AutoBackupBridge.keep())
        val names = (1..31).map { AutoBackupBridge.nameFor(2026, 10, it, 8, 0, 0) } + AutoBackupBridge.nameFor(2026, 11, 1, 8, 0, 0) + listOf("a.kassa", "Kassa_x.kassa")
        assertEquals(listOf(AutoBackupBridge.nameFor(2026, 10, 2, 8, 0, 0), AutoBackupBridge.nameFor(2026, 10, 1, 8, 0, 0)), AutoBackupBridge.toDelete(names))
    }

    @Test
    fun dueAfterADay() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(AutoBackupBridge.isDue(0, 0, day))
        assertFalse(AutoBackupBridge.isDue(day, 0, day + 1000))
        assertTrue(AutoBackupBridge.isDue(day, 0, 2 * day))
    }

    @Test
    fun streakRoundTripsThroughNumbers() {
        var s = AutoBackupBridge.afterSuccess()
        assertEquals(0, s.failedDays)
        for (d in 10L..11L) {
            s = AutoBackupBridge.afterFailure(s.failedDays, s.lastFailedDay, s.alerted, d)
            assertFalse(s.notifyNow)
        }
        s = AutoBackupBridge.afterFailure(s.failedDays, s.lastFailedDay, s.alerted, 12)
        assertTrue(s.notifyNow)
        s = AutoBackupBridge.afterAlert(s.failedDays, s.lastFailedDay, s.alerted)
        assertTrue(s.alerted)
        s = AutoBackupBridge.afterFailure(s.failedDays, s.lastFailedDay, s.alerted, 13)
        assertFalse(s.notifyNow)
        assertEquals(4, s.failedDays)
        assertEquals(0, AutoBackupBridge.afterSuccess().failedDays)
    }
}
