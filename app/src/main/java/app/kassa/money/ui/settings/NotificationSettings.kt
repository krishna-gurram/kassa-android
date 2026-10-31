package app.kassa.money.ui.settings

import android.content.Intent
import android.app.NotificationManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kassa.money.domain.DueKind
import app.kassa.money.domain.DueNotices
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.common.DigitsField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** How many of the four notifications are on, for the row in Settings. */
@Composable
internal fun notificationsSummary(): String {
    val settings = LocalSettings.current
    val daily by settings.reminder.collectAsState()
    val card by settings.dueRule(DueKind.CARD).collectAsState()
    val loan by settings.dueRule(DueKind.LOAN).collectAsState()
    val bill by settings.dueRule(DueKind.BILL).collectAsState()
    val on = listOf(daily, card.enabled, loan.enabled, bill.enabled).count { it }
    return if (on == 0) "Off" else "$on on"
}

/**
 * Everything Kassa can notify about: the daily reminder, and card, loan and bill payments coming due, each with its own
 * switch, how many days before, and time. If Android has notifications off for Kassa, one row at the top says so and
 * opens the phone's settings for the app. A notification only tells; it never pays or adds anything.
 */
@Composable
internal fun NotificationSettingsPage(actions: SettingsActions) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val daily by settings.reminder.collectAsState()
    val card by settings.dueRule(DueKind.CARD).collectAsState()
    val loan by settings.dueRule(DueKind.LOAN).collectAsState()
    val bill by settings.dueRule(DueKind.BILL).collectAsState()

    // Checked again on return from the phone's settings.
    val allowed = { context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false }
    var notifyAllowed by remember { mutableStateOf(allowed()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = allowed()
                if (now != notifyAllowed) { notifyAllowed = now; actions.refreshDailyJobs() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!notifyAllowed) NavRow("Notifications are off for Kassa", "Open settings") {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }

    Gap(16)
    DailyReminder(actions)

    Gap(16)
    DueSection(DueKind.CARD, "Credit card payment due", "A notification for each card with a balance payable", actions)
    Gap(16)
    DueSection(DueKind.LOAN, "Loan payment due", "A notification for each loan", actions)
    Gap(16)
    DueSection(DueKind.BILL, "Bills and repeating payments", "A notification for each bill", actions)

    RemindOnTimeRow(show = daily || card.enabled || loan.enabled || bill.enabled, actions = actions)
    Gap(12)
    Text(
        "A notification shows the amount due and opens the card, the loan payment or the bill. It never pays or adds anything. " +
            "A card needs a payment due day, and a loan needs its terms, to notify.",
        style = KassaType.small, color = Kassa.colors.ink2,
    )
    Gap(24)
}

/** One kind's switch, and when it is on, how many days before the due day and at what time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueSection(kind: DueKind, title: String, detail: String, actions: SettingsActions) {
    val settings = LocalSettings.current
    val rule by settings.dueRule(kind).collectAsState()
    var pickTime by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    val change = { next: app.kassa.money.domain.DueRule -> settings.setDueRule(kind, next); actions.refreshDailyJobs() }

    SwitchRow(title, detail, rule.enabled) { on -> change(rule.copy(enabled = on)) }
    if (!rule.enabled) return
    Text("Remind me", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp))
    FlowRow {
        Chip("On the due day", selected = rule.daysBefore == 0) { change(rule.copy(daysBefore = 0)) }
        Chip("1 day before", selected = rule.daysBefore == 1) { change(rule.copy(daysBefore = 1)) }
        Chip("3 days before", selected = rule.daysBefore == 3) { change(rule.copy(daysBefore = 3)) }
        val custom = rule.daysBefore !in listOf(0, 1, 3)
        Chip(if (custom) "${rule.daysBefore} days before" else "Other", selected = custom) { other = true }
    }
    NavRow("Time", timeLabel(rule.minute)) { pickTime = true }
    if (pickTime) TimeDialog(rule.minute, onDismiss = { pickTime = false }) { m -> change(rule.copy(minute = m)); pickTime = false }
    if (other) DaysDialog(rule.daysBefore, onDismiss = { other = false }) { d -> change(rule.copy(daysBefore = d)); other = false }
}

/** A number of days before the due day, 0 to 14. */
@Composable
private fun DaysDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val days = text.toIntOrNull()
    val problem = DueNotices.daysProblem(days)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kassa.colors.raised,
        title = { Text("Days before the due day", style = KassaType.section, color = Kassa.colors.ink) },
        text = { DigitsField(text, { text = it }, label = "Days, 0 to ${DueNotices.MAX_DAYS_BEFORE}", digits = 2, problem = if (text.isEmpty()) null else problem) },
        confirmButton = {
            TextButton(onClick = { if (days != null && problem == null) onPick(days) }, enabled = problem == null) { Text("Set", color = Kassa.colors.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Kassa.colors.ink2) } },
    )
}
