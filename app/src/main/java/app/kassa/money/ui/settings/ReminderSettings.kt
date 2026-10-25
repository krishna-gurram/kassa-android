package app.kassa.money.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * One notification a day at your time, asking whether today's transactions are in. Android asks for permission to
 * notify only when this is turned on.
 */
@Composable
internal fun DailyReminder(actions: SettingsActions) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val on by settings.reminder.collectAsState()
    val minute by settings.reminderMinute.collectAsState()
    var pickTime by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val turnOn = { denied = false; settings.setReminder(true); actions.refreshDailyJobs() }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turnOn() else denied = true
    }
    val mayNotify = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    SwitchRow("Daily reminder", "A nudge to record the day's transactions", on) { want ->
        when {
            !want -> { settings.setReminder(false); actions.refreshDailyJobs() }
            mayNotify -> turnOn()
            else -> askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (on) NavRow("Time", timeLabel(minute)) { pickTime = true }
    if (denied) Text(
        "Notifications are off for Kassa. Allow them in the phone's Settings > Apps > Kassa > Notifications, then turn this on again.",
        style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 6.dp),
    )
    if (pickTime) TimeDialog(minute, onDismiss = { pickTime = false }) { m -> settings.setReminderMinute(m); actions.refreshDailyJobs(); pickTime = false }
}
