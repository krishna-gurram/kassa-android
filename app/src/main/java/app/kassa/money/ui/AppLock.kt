package app.kassa.money.ui

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators

/**
 * Whether this phone can lock Kassa: App lock asks for a fingerprint, face or the screen lock (PIN, pattern, password), so it
 * needs the phone to have one. Used when the switch is turned on, and again whenever the lock screen is about to ask.
 */
object AppLock {
    enum class Status {
        /** A screen lock or biometric is set up: the prompt will work. */
        READY,
        /** The phone has no screen lock (or no way to ask for one): App lock can't work. */
        NO_SCREEN_LOCK,
        /** Something else, usually passing (a sensor busy, a security update pending): not a reason to turn the lock off. */
        UNAVAILABLE,
    }

    /** What the person is told when App lock can't be turned on. */
    const val SET_A_SCREEN_LOCK = "Set a screen lock on this phone first."

    /** What the person is told when Kassa turned App lock off because the screen lock is gone. */
    const val TURNED_OFF = "App lock was turned off because this phone has no screen lock. Set one, then turn App lock on again in Settings."

    fun check(context: Context): Status {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            // Before Android 11 the prompt can't be asked about "biometric or screen lock" together; a secure screen lock is the test.
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return if (keyguard?.isDeviceSecure == true) Status.READY else Status.NO_SCREEN_LOCK
        }
        return when (BiometricManager.from(context).canAuthenticate(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status.NO_SCREEN_LOCK
            else -> Status.UNAVAILABLE
        }
    }
}
