package dev.krishnagurram.kassa.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small preferences. Stored in app-private SharedPreferences, which are excluded from every backup. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kassa_settings", Context.MODE_PRIVATE)

    private val _monthStartDay = MutableStateFlow(prefs.getInt(MONTH_START, 1))
    val monthStartDay: StateFlow<Int> = _monthStartDay

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
    }
}
