package app.kassa.money.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * What day it is, and in which time zone, for everything that reads "today": the ledger, its query windows, Activity's current
 * month, the entry page. It is read when the app starts and again whenever the phone says the date, the time or the time
 * zone changed, and whenever the app comes back to the screen ([refresh], from the activity's onStart), so a phone left open
 * past midnight or taken across a time zone never keeps showing yesterday. The windows themselves are worked out from [day]
 * by `QueryWindows` (plain Kotlin, with tests).
 *
 * One instance for the whole process, with a receiver registered for its lifetime: an application-wide singleton leaks nothing,
 * and no screen has to remember to listen.
 */
class DayClock(private val context: Context) {
    /** A day in a time zone: the pair every "today" is read from. */
    data class Day(val today: LocalDate, val zone: ZoneId)

    private val _day = MutableStateFlow(read())
    val day: StateFlow<Day> = _day

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The runtime keeps the zone it first read; a zone change means read it again.
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
            refresh()
        }
    }

    /** Starts listening for date, time and time zone changes. Call once, from the application. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Reads the phone's day and zone again; observers hear about it only if either changed. */
    fun refresh() { _day.value = read() }

    private fun read(): Day {
        val zone = ZoneId.systemDefault()
        return Day(LocalDate.now(zone), zone)
    }
}
