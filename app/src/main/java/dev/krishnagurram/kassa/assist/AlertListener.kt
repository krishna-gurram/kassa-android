package dev.krishnagurram.kassa.assist

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.domain.AlertParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads notifications only from the bank and card apps the user turned on in Settings. A notification that
 * looks like a purchase becomes a pending card on Today; everything else is ignored and nothing is stored.
 * Apps that post dollar amounts are remembered (by package name only) so Settings can offer them.
 */
class AlertListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val parsed = AlertParser.parse(title, text) ?: return
        val app = application as KassaApplication
        app.settings.rememberAlertApp(sbn.packageName)
        if (sbn.packageName !in app.settings.alertApps.value) return
        scope.launch { app.assist.capture(parsed, sbn.packageName, sbn.postTime) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
