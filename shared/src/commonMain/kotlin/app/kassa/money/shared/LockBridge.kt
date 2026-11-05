package app.kassa.money.shared

import app.kassa.money.domain.AppLockRules

/**
 * App lock and the privacy cover for Swift: flat text and Booleans over `AppLockRules`. Availability, turn-on results and unlock
 * steps cross as the enum names ("READY", "NO_SCREEN_LOCK", "UNAVAILABLE"; "ALLOW", "NEEDS_SCREEN_LOCK", "TRY_LATER"; "ASK",
 * "TURN_OFF"); Swift compares the text. Swift only asks LocalAuthentication and draws.
 */
object LockBridge {

    /** The minute rule, in milliseconds. */
    fun relockAfterMillis(): Long = AppLockRules.RELOCK_AFTER_MILLIS

    /** What the phone says: [canAuthenticate] when a prompt would work, [noScreenLock] when it has no passcode set. */
    fun availability(canAuthenticate: Boolean, noScreenLock: Boolean): String =
        AppLockRules.availability(canAuthenticate, noScreenLock).name

    /** What the App lock switch does when turned on, given [availability]. */
    fun turnOnResult(availability: String): String = AppLockRules.turnOn(availabilityOf(availability)).name

    fun startsLocked(lockOn: Boolean): Boolean = AppLockRules.startsLocked(lockOn)

    fun lockShown(locked: Boolean, lockOn: Boolean): Boolean = AppLockRules.lockShown(locked, lockOn)

    /** Whether the app is locked after being hidden since [hiddenAtMillis] (0 for never). */
    fun lockedAfterAway(locked: Boolean, lockOn: Boolean, hiddenAtMillis: Long, nowMillis: Long): Boolean =
        AppLockRules.lockedAfterAway(locked, lockOn, hiddenAtMillis, nowMillis)

    /** "ASK" to show the system prompt, "TURN_OFF" when the phone has no screen lock any more. */
    fun unlockStep(availability: String): String = AppLockRules.unlockStep(availabilityOf(availability)).name

    /** Whether the neutral cover hides the app (switcher snapshots, background). */
    fun privacyCover(lockOn: Boolean, hideAmounts: Boolean, appActive: Boolean): Boolean =
        AppLockRules.privacyCover(lockOn, hideAmounts, appActive)

    // An unknown name counts as a passing problem, which never turns the lock off.
    private fun availabilityOf(name: String): AppLockRules.Availability =
        AppLockRules.Availability.entries.firstOrNull { it.name == name } ?: AppLockRules.Availability.UNAVAILABLE
}
