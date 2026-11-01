package app.kassa.money.data.jobs

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kassa.money.KassaApplication
import app.kassa.money.R
import app.kassa.money.domain.DueItem
import app.kassa.money.domain.DueItems
import app.kassa.money.domain.DueNotices
import app.kassa.money.domain.QueryWindows
import app.kassa.money.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phone notifications for payments that come due. It reads the data, finds what wants a notification now and posts it,
 * then sets ONE inexact alarm (allow-while-idle, like the daily reminder) for the earliest moment anything wants one
 * next, so a notification may arrive a few minutes late. Every trigger just calls [refresh], which does both and is safe to
 * repeat: what was posted for a due date is remembered, so nothing rings twice.
 *
 * Triggers: the alarm, boot, an app update, a time or time zone change, the app
 * starting, a change in Settings, and any saved change to cards, loans, bills or payments. The rules (what is due,
 * which day, which text) are in `domain/DueNotices.kt`, the same ones the Notifications page uses.
 * A notification only tells; it never pays or adds anything.
 */
object DueNotifier {
    /** Different from the reminder's. */
    const val REQUEST = 103
    const val ACTION_OPEN_DUE = "app.kassa.money.OPEN_DUE"
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"

    private const val CHANNEL = "payments_due"
    private const val GROUP = "app.kassa.money.PAYMENTS_DUE"
    private const val SUMMARY_ID = 9_999
    private const val PREFS = "kassa_due"
    private const val POSTED = "posted"
    /** Posted keys are kept this long past their due date, then forgotten. */
    private const val KEEP_DAYS = 60

    /** Forgets which due dates were already notified, for when the accounts they name are replaced. */
    fun forgetPosted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(POSTED).apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Posts what is due and sets the next alarm, in the background. [done] runs when finished (a receiver's goAsync). */
    fun refresh(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                runCatching { sync(app) }
            } finally {
                done()
            }
        }
    }

    private suspend fun sync(context: Context) = lock.withLock {
        val app = context as KassaApplication
        val settings = app.settings
        val rules = settings.dueRules()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!rules.anyEnabled) {
            cancel(context)
            return@withLock
        }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val accounts = app.accounts.state.first()
        // The same window the Ledger reads: enough for card statements.
        val history = app.entries.between(QueryWindows.recentFrom(today, zone), Long.MAX_VALUE).first()
        val bills = app.bills.bills.first()
        val loans = app.loans.state.first()
        val items = DueItems.build(
            accounts.accounts, accounts.balances, history, bills, loans.terms, loans.changes, loans.payments, today, zone, forPhone = true,
        )
        val here = DueNotices.Moment(today.toEpochDay().toInt(), now.hour * 60 + now.minute)
        val cleared = settings.cleared.value
        var posted = prefs.getStringSet(POSTED, emptySet())!!.toSet()

        tidy(context, items)
        val ringing = DueNotices.ringing(items, rules, here, posted, cleared)
        // Without permission nothing is marked posted: it can still ring if the permission comes back within the grace.
        if (ringing.isNotEmpty() && canNotify(context)) {
            post(context, ringing, here.day)
            posted = (posted + ringing.map { it.key }).filter { key -> (key.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE) >= here.day - KEEP_DAYS }.toSet()
            prefs.edit().putStringSet(POSTED, posted).apply()
        }
        book(context, DueNotices.next(items, rules, here, posted, cleared), zone)
    }

    private fun canNotify(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /** One inexact alarm for the earliest moment. */
    private fun book(context: Context, next: DueNotices.Next, zone: ZoneId) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val nowMillis = System.currentTimeMillis()
        var at = DailyTime.at(next.moment.day.toLong(), next.moment.minute, zone).toInstant().toEpochMilli()
        // A clock that repeats an hour can put the time behind us; look again in a minute rather than ring in a loop.
        if (at <= nowMillis) at = nowMillis + 60_000L
        val pi = alarmIntent(context)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, DueReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationId(item: DueItem): Int = 10_000 + (item.key.hashCode() and 0xFFFFF)

    /** Takes down notifications for payments that are no longer due (paid, added or cleared), and the summary when fewer than two are left. */
    private fun tidy(context: Context, items: List<DueItem>) = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
        val live = items.map(::notificationId).toSet()
        val mine = nm.activeNotifications.filter { it.notification.group == GROUP && it.id != SUMMARY_ID }
        mine.filter { it.id !in live }.forEach { nm.cancel(it.id) }
        if (mine.count { it.id in live } < 2) nm.cancel(SUMMARY_ID)
    }

    /**
     * What the lock screen shows when the phone is locked and notifications are private: a generic title, with no
     * amount, no card, loan or bill name and no count.
     */
    private fun publicVersion(context: Context, tap: PendingIntent): Notification =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kassa)
            .setContentTitle(context.getString(R.string.jobs_public_title))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

    /** One notification per item, under one summary when two or more are showing. */
    private fun post(context: Context, items: List<DueItem>, today: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.jobs_channel_due), NotificationManager.IMPORTANCE_DEFAULT))
        val showing = runCatching {
            nm.activeNotifications.filter { it.notification.group == GROUP && it.id != SUMMARY_ID }.map { it.id }.toSet()
        }.getOrDefault(emptySet()) + items.map(::notificationId)
        val grouped = showing.size >= 2
        items.forEach { item ->
            val id = notificationId(item)
            val words = DueNotices.text(item, (item.dueDay - today), showAmount = !app.kassa.money.domain.Money.isHidden)
            val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_DUE)
                .putExtra(EXTRA_KIND, item.kind.name).putExtra(EXTRA_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // The request code is the notification's id: extras don't make intents differ, so each needs its own.
            val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_kassa)
                .setContentTitle(words.title)
                .setContentText(words.body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, tap))
                .setGroup(GROUP)
                .apply { if (grouped) setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }
                .build()
            nm.notify(id, n)
        }
        if (grouped) {
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val tap = PendingIntent.getActivity(context, SUMMARY_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                SUMMARY_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_kassa)
                    .setContentTitle(context.getString(R.string.jobs_summary_title))
                    .setContentText(context.resources.getQuantityString(R.plurals.jobs_summary_text, showing.size, showing.size))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion(context, tap))
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .build(),
            )
        }
    }
}

/** The due alarm went off: posts what is due and sets the next alarm. */
class DueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        DueNotifier.refresh(context) { pending.finish() }
    }
}
