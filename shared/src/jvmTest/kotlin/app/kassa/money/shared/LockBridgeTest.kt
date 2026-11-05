package app.kassa.money.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockBridgeTest {

    @Test
    fun namesCrossAsText() {
        assertEquals("READY", LockBridge.availability(canAuthenticate = true, noScreenLock = false))
        assertEquals("NO_SCREEN_LOCK", LockBridge.availability(canAuthenticate = false, noScreenLock = true))
        assertEquals("UNAVAILABLE", LockBridge.availability(canAuthenticate = false, noScreenLock = false))
        assertEquals("ALLOW", LockBridge.turnOnResult("READY"))
        assertEquals("NEEDS_SCREEN_LOCK", LockBridge.turnOnResult("NO_SCREEN_LOCK"))
        assertEquals("TRY_LATER", LockBridge.turnOnResult("UNAVAILABLE"))
        assertEquals("TURN_OFF", LockBridge.unlockStep("NO_SCREEN_LOCK"))
        assertEquals("ASK", LockBridge.unlockStep("READY"))
    }

    @Test
    fun anUnknownAvailabilityIsAPassingProblem() {
        assertEquals("TRY_LATER", LockBridge.turnOnResult("???"))
        assertEquals("ASK", LockBridge.unlockStep("???"))
    }

    @Test
    fun minuteRuleAndCover() {
        assertEquals(60_000L, LockBridge.relockAfterMillis())
        assertTrue(LockBridge.startsLocked(true))
        assertTrue(LockBridge.lockedAfterAway(false, true, 1_000L, 70_000L))
        assertFalse(LockBridge.lockedAfterAway(false, true, 1_000L, 30_000L))
        assertTrue(LockBridge.lockShown(true, true))
        assertTrue(LockBridge.privacyCover(lockOn = false, hideAmounts = true, appActive = false))
        assertFalse(LockBridge.privacyCover(lockOn = false, hideAmounts = true, appActive = true))
    }
}
