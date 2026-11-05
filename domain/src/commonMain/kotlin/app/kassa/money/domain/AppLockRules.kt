package app.kassa.money.domain

/**
 * When App lock locks, when it can be turned on and what to do when the phone stops being able to ask. Plain Kotlin so Android
 * and iOS follow one set of rules; each platform only asks its own system whether the phone has a screen lock and shows its own
 * prompt (Android's BiometricPrompt, iOS's LocalAuthentication).
 */
object AppLockRules {

    /** Coming back after longer than this away locks again. */
    const val RELOCK_AFTER_MILLIS: Long = 60_000L

    /** What the phone says about asking for a fingerprint, face or screen lock. */
    enum class Availability {
        /** A screen lock or biometric is set up: the prompt will work. */
        READY,
        /** The phone has no screen lock: App lock can't work. */
        NO_SCREEN_LOCK,
        /** Something else, usually passing (a sensor busy, a security update pending): not a reason to turn the lock off. */
        UNAVAILABLE,
    }

    /** What happens when the person flips the App lock switch on. */
    enum class TurnOn {
        /** The switch goes on. */
        ALLOW,
        /** The switch stays off and the person is told to set a screen lock first. */
        NEEDS_SCREEN_LOCK,
        /** The switch stays off and the person is told to try again in a moment. */
        TRY_LATER,
    }

    /** What the lock screen does before it asks. */
    enum class UnlockStep {
        /** Show the system prompt. */
        ASK,
        /** The lock can't work: turn the setting off, tell the person once, and open (never open silently while it works). */
        TURN_OFF,
    }

    /** [canAuthenticate]: the system says a prompt would work. [noScreenLock]: it says the phone has no screen lock or biometric. */
    fun availability(canAuthenticate: Boolean, noScreenLock: Boolean): Availability = when {
        canAuthenticate -> Availability.READY
        noScreenLock -> Availability.NO_SCREEN_LOCK
        else -> Availability.UNAVAILABLE
    }

    fun turnOn(availability: Availability): TurnOn = when (availability) {
        Availability.READY -> TurnOn.ALLOW
        Availability.NO_SCREEN_LOCK -> TurnOn.NEEDS_SCREEN_LOCK
        Availability.UNAVAILABLE -> TurnOn.TRY_LATER
    }

    /** A cold start is locked whenever App lock is on. */
    fun startsLocked(lockOn: Boolean): Boolean = lockOn

    /** The lock screen covers the app only while the setting is on and the app is locked. */
    fun lockShown(locked: Boolean, lockOn: Boolean): Boolean = locked && lockOn

    /**
     * Whether the app is locked after being away. It stays locked once locked; otherwise it locks when the setting is on and the
     * app was hidden ([hiddenAtMillis], 0 when never) for more than [RELOCK_AFTER_MILLIS] before [nowMillis]. A clock that went
     * back past the time it was hidden counts as away too, so setting the clock back never skips the lock.
     */
    fun lockedAfterAway(locked: Boolean, lockOn: Boolean, hiddenAtMillis: Long, nowMillis: Long): Boolean {
        if (locked) return true
        if (!lockOn || hiddenAtMillis <= 0L) return false
        val away = nowMillis - hiddenAtMillis
        return away < 0L || away > RELOCK_AFTER_MILLIS
    }

    /** [Availability.NO_SCREEN_LOCK] turns the lock off; anything else asks (a passing problem leaves Kassa locked, not open). */
    fun unlockStep(availability: Availability): UnlockStep =
        if (availability == Availability.NO_SCREEN_LOCK) UnlockStep.TURN_OFF else UnlockStep.ASK

    /**
     * Whether a neutral cover hides the app: while App lock or Hide balances is on and the app is not the active, visible one
     * (app switcher, control centre, background), so a snapshot of it never shows balances.
     */
    fun privacyCover(lockOn: Boolean, hideAmounts: Boolean, appActive: Boolean): Boolean = (lockOn || hideAmounts) && !appActive
}
