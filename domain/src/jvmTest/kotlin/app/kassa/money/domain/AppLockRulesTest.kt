package app.kassa.money.domain

import app.kassa.money.domain.AppLockRules.Availability
import app.kassa.money.domain.AppLockRules.TurnOn
import app.kassa.money.domain.AppLockRules.UnlockStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockRulesTest {

    @Test
    fun availabilityFromTheSystemsAnswer() {
        assertEquals(Availability.READY, AppLockRules.availability(canAuthenticate = true, noScreenLock = false))
        assertEquals(Availability.READY, AppLockRules.availability(canAuthenticate = true, noScreenLock = true))
        assertEquals(Availability.NO_SCREEN_LOCK, AppLockRules.availability(canAuthenticate = false, noScreenLock = true))
        assertEquals(Availability.UNAVAILABLE, AppLockRules.availability(canAuthenticate = false, noScreenLock = false))
    }

    @Test
    fun switchTurnsOnOnlyWhenThePhoneHasAScreenLock() {
        assertEquals(TurnOn.ALLOW, AppLockRules.turnOn(Availability.READY))
        assertEquals(TurnOn.NEEDS_SCREEN_LOCK, AppLockRules.turnOn(Availability.NO_SCREEN_LOCK))
        assertEquals(TurnOn.TRY_LATER, AppLockRules.turnOn(Availability.UNAVAILABLE))
    }

    @Test
    fun coldStartLocksOnlyWhenOn() {
        assertTrue(AppLockRules.startsLocked(true))
        assertFalse(AppLockRules.startsLocked(false))
    }

    @Test
    fun lockScreenNeedsBothTheSettingAndTheLockedState() {
        assertTrue(AppLockRules.lockShown(locked = true, lockOn = true))
        assertFalse(AppLockRules.lockShown(locked = true, lockOn = false))
        assertFalse(AppLockRules.lockShown(locked = false, lockOn = true))
    }

    @Test
    fun relocksAfterMoreThanAMinuteAway() {
        val hidden = 1_000_000L
        assertFalse(AppLockRules.lockedAfterAway(false, true, hidden, hidden + 60_000L))
        assertTrue(AppLockRules.lockedAfterAway(false, true, hidden, hidden + 60_001L))
        assertFalse(AppLockRules.lockedAfterAway(false, true, hidden, hidden + 5_000L))
    }

    @Test
    fun staysLockedOnceLockedAndNeverLocksWhenOff() {
        assertTrue(AppLockRules.lockedAfterAway(true, true, 0L, 10L))
        assertFalse(AppLockRules.lockedAfterAway(false, false, 1_000L, 10_000_000L))
        assertFalse(AppLockRules.lockedAfterAway(false, true, 0L, 10_000_000L))
    }

    @Test
    fun aClockSetBackStillLocks() {
        assertTrue(AppLockRules.lockedAfterAway(false, true, 5_000_000L, 1_000L))
    }

    @Test
    fun noScreenLockTurnsTheLockOffAndAnythingElseAsks() {
        assertEquals(UnlockStep.TURN_OFF, AppLockRules.unlockStep(Availability.NO_SCREEN_LOCK))
        assertEquals(UnlockStep.ASK, AppLockRules.unlockStep(Availability.READY))
        assertEquals(UnlockStep.ASK, AppLockRules.unlockStep(Availability.UNAVAILABLE))
    }

    @Test
    fun coverWhileEitherSwitchIsOnAndTheAppIsNotActive() {
        assertTrue(AppLockRules.privacyCover(lockOn = true, hideAmounts = false, appActive = false))
        assertTrue(AppLockRules.privacyCover(lockOn = false, hideAmounts = true, appActive = false))
        assertFalse(AppLockRules.privacyCover(lockOn = true, hideAmounts = true, appActive = true))
        assertFalse(AppLockRules.privacyCover(lockOn = false, hideAmounts = false, appActive = false))
    }
}
