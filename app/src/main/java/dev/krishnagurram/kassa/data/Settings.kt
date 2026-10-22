package dev.krishnagurram.kassa.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small preferences. Stored in app-private SharedPreferences, which are excluded from every backup. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kassa_settings", Context.MODE_PRIVATE)

    private val _monthStartDay = MutableStateFlow(prefs.getInt(MONTH_START, 1))
    val monthStartDay: StateFlow<Int> = _monthStartDay

    private val _alertApps = MutableStateFlow(prefs.getStringSet(ALERT_APPS, emptySet())!!.toSet())
    /** Apps the user chose to read bank alerts from. */
    val alertApps: StateFlow<Set<String>> = _alertApps

    private val _seenApps = MutableStateFlow(prefs.getStringSet(SEEN_APPS, emptySet())!!.toSet())
    /** Apps that have posted something that looked like a purchase. Package names only. */
    val seenApps: StateFlow<Set<String>> = _seenApps

    fun rememberAlertApp(pkg: String) {
        if (pkg in _seenApps.value) return
        val next = _seenApps.value + pkg
        prefs.edit().putStringSet(SEEN_APPS, next).apply()
        _seenApps.value = next
    }

    fun setAlertApp(pkg: String, on: Boolean) {
        val next = if (on) _alertApps.value + pkg else _alertApps.value - pkg
        prefs.edit().putStringSet(ALERT_APPS, next).apply()
        _alertApps.value = next
    }

    private val _sensitivity = MutableStateFlow(prefs.getString(SENSITIVITY, "BALANCED")!!)
    val sensitivity: StateFlow<String> = _sensitivity
    fun setSensitivity(v: String) { prefs.edit().putString(SENSITIVITY, v).apply(); _sensitivity.value = v }

    /** Flags marked "It's expected", as "categoryId:yyyyMM", so they stay quiet for the rest of that month only. */
    private val _dismissed = MutableStateFlow(prefs.getStringSet(DISMISSED, emptySet())!!.toSet())
    val dismissed: StateFlow<Set<String>> = _dismissed
    fun dismiss(key: String) { val n = _dismissed.value + key; prefs.edit().putStringSet(DISMISSED, n).apply(); _dismissed.value = n }

    private val _lock = MutableStateFlow(prefs.getBoolean(LOCK, false))
    val lockEnabled: StateFlow<Boolean> = _lock
    fun setLock(on: Boolean) { prefs.edit().putBoolean(LOCK, on).apply(); _lock.value = on }

    var startedFresh: Boolean
        get() = prefs.getBoolean(STARTED_FRESH, false)
        set(value) { prefs.edit().putBoolean(STARTED_FRESH, value).apply() }

    fun setMonthStartDay(day: Int) {
        val d = day.coerceIn(1, 28)
        prefs.edit().putInt(MONTH_START, d).apply()
        _monthStartDay.value = d
    }

    private companion object {
        const val MONTH_START = "month_start_day"
        const val STARTED_FRESH = "started_fresh"
        const val ALERT_APPS = "alert_apps"
        const val SEEN_APPS = "seen_alert_apps"
        const val SENSITIVITY = "flag_sensitivity"
        const val DISMISSED = "dismissed_flags"
        const val LOCK = "app_lock"
    }
}
