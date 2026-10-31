package app.kassa.money.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small preferences. Stored in app-private SharedPreferences, which are excluded from every backup. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kassa_settings", Context.MODE_PRIVATE)

    private val _monthStartDay = MutableStateFlow(prefs.getInt(MONTH_START, 1))
    val monthStartDay: StateFlow<Int> = _monthStartDay

    private val _lock = MutableStateFlow(prefs.getBoolean(LOCK, false))
    val lockEnabled: StateFlow<Boolean> = _lock
    fun setLock(on: Boolean) { prefs.edit().putBoolean(LOCK, on).apply(); _lock.value = on }

    private val _theme = MutableStateFlow(prefs.getString(THEME, "SYSTEM")!!)
    /** SYSTEM follows the phone; LIGHT or DARK fix it. */
    val theme: StateFlow<String> = _theme
    fun setTheme(v: String) { prefs.edit().putString(THEME, v).apply(); _theme.value = v }

    private val _currency = MutableStateFlow(prefs.getString(CURRENCY, "USD")!!).also {
        app.kassa.money.domain.Money.style = app.kassa.money.domain.Currencies.byCode(it.value)
    }
    /** The one currency this phone shows. Amounts are never converted when it changes. */
    val currency: StateFlow<String> = _currency
    fun setCurrency(code: String) {
        prefs.edit().putString(CURRENCY, code).apply()
        app.kassa.money.domain.Money.style = app.kassa.money.domain.Currencies.byCode(code)
        _currency.value = code
    }

    /**
     * Hide balances: while true every amount shows as dots (see Money.hideAmounts). A Compose state, not a flow, so
     * each composable that formats money subscribes by formatting it and redraws when this flips, with no screen
     * recreated and no form, scroll position or navigation lost. Stays on until switched back, across restarts.
     */
    var hideAmounts: Boolean by mutableStateOf(prefs.getBoolean(HIDE_AMOUNTS, false))
        private set

    init {
        app.kassa.money.domain.Money.hideAmounts = { hideAmounts }
    }

    fun chooseHideAmounts(on: Boolean) { prefs.edit().putBoolean(HIDE_AMOUNTS, on).apply(); hideAmounts = on }

    private val _accountSort = MutableStateFlow(prefs.getString(ACCOUNT_SORT, "NAME")!!)
    /** How the Accounts tab orders every section: NAME, BALANCE, DUE (cards by due date, the rest by name) or CUSTOM (the user's arrangement). */
    val accountSort: StateFlow<String> = _accountSort
    private val _accountSortDesc = MutableStateFlow(prefs.getBoolean(ACCOUNT_SORT_DESC, false))
    /** Reverses [accountSort]: Z to A, smallest first, latest due first. */
    val accountSortDesc: StateFlow<Boolean> = _accountSortDesc
    /** Picking any sort other than CUSTOM drops the user's arrangement. */
    fun setAccountSort(v: String) {
        val e = prefs.edit().putString(ACCOUNT_SORT, v).putBoolean(ACCOUNT_SORT_DESC, false)
        if (v != "CUSTOM") { e.remove(ACCOUNT_ARRANGEMENT); _arrangement.value = null }
        e.apply(); _accountSort.value = v; _accountSortDesc.value = false
    }
    fun setAccountSortDesc(v: Boolean) { prefs.edit().putBoolean(ACCOUNT_SORT_DESC, v).apply(); _accountSortDesc.value = v }

    private val _arrangement = MutableStateFlow(app.kassa.money.domain.AccountArrangement.decode(prefs.getString(ACCOUNT_ARRANGEMENT, null)))
    /** The user's own order of account groups and accounts; null until they arrange (then sort is CUSTOM). */
    val accountArrangement: StateFlow<app.kassa.money.domain.AccountArrangement?> = _arrangement
    fun setAccountArrangement(a: app.kassa.money.domain.AccountArrangement) {
        prefs.edit().putString(ACCOUNT_ARRANGEMENT, a.encode()).putString(ACCOUNT_SORT, "CUSTOM").putBoolean(ACCOUNT_SORT_DESC, false).apply()
        _arrangement.value = a; _accountSort.value = "CUSTOM"; _accountSortDesc.value = false
    }
    /** Reset to A to Z: no arrangement, sort by name. */
    fun resetAccountArrangement() = setAccountSort("NAME")

    // Automatic backups: on or off, the folder picked once (a persisted document tree), the time of day in minutes.
    private val _autoBackup = MutableStateFlow(prefs.getBoolean(AUTO_BACKUP, false))
    val autoBackup: StateFlow<Boolean> = _autoBackup
    fun setAutoBackup(on: Boolean) { prefs.edit().putBoolean(AUTO_BACKUP, on).apply(); _autoBackup.value = on }
    private val _backupFolder = MutableStateFlow(prefs.getString(BACKUP_FOLDER, null))
    val backupFolder: StateFlow<String?> = _backupFolder
    fun setBackupFolder(uri: String?) { prefs.edit().putString(BACKUP_FOLDER, uri).apply(); _backupFolder.value = uri }
    private val _backupMinute = MutableStateFlow(prefs.getInt(BACKUP_MINUTE, 23 * 60))
    val backupMinute: StateFlow<Int> = _backupMinute
    fun setBackupMinute(m: Int) { prefs.edit().putInt(BACKUP_MINUTE, m).apply(); _backupMinute.value = m }
    private val _lastBackup = MutableStateFlow(prefs.getLong(LAST_BACKUP, 0L) to prefs.getString(BACKUP_PROBLEM, null))
    /** When the last backup was saved (0 for never), and what went wrong with the latest try, if anything. */
    val lastBackup: StateFlow<Pair<Long, String?>> = _lastBackup
    fun backupDone(millis: Long) { prefs.edit().putLong(LAST_BACKUP, millis).remove(BACKUP_PROBLEM).apply(); _lastBackup.value = millis to null }
    fun backupFailed(problem: String) { prefs.edit().putString(BACKUP_PROBLEM, problem).apply(); _lastBackup.value = _lastBackup.value.first to problem }

    // The daily reminder: on or off and its time of day in minutes.
    private val _reminder = MutableStateFlow(prefs.getBoolean(REMINDER, false))
    val reminder: StateFlow<Boolean> = _reminder
    fun setReminder(on: Boolean) { prefs.edit().putBoolean(REMINDER, on).apply(); _reminder.value = on }
    private val _reminderMinute = MutableStateFlow(prefs.getInt(REMINDER_MINUTE, 21 * 60))
    val reminderMinute: StateFlow<Int> = _reminderMinute
    fun setReminderMinute(m: Int) { prefs.edit().putInt(REMINDER_MINUTE, m).apply(); _reminderMinute.value = m }

    // Phone notifications for payments due, one rule per kind: on or off, days before the due day, time of day.
    private val dueFlows = app.kassa.money.domain.DueKind.entries.associateWith { MutableStateFlow(readDue(it)) }
    fun dueRule(kind: app.kassa.money.domain.DueKind): StateFlow<app.kassa.money.domain.DueRule> = dueFlows.getValue(kind)
    fun setDueRule(kind: app.kassa.money.domain.DueKind, rule: app.kassa.money.domain.DueRule) {
        val clean = rule.copy(daysBefore = rule.daysBefore.coerceIn(0, app.kassa.money.domain.DueNotices.MAX_DAYS_BEFORE), minute = rule.minute.coerceIn(0, 1_439))
        val p = "due_${kind.name.lowercase()}"
        prefs.edit().putBoolean(p + "_on", clean.enabled).putInt(p + "_days", clean.daysBefore).putInt(p + "_minute", clean.minute).apply()
        dueFlows.getValue(kind).value = clean
    }
    /** All three rules, as the notifier reads them. */
    fun dueRules() = app.kassa.money.domain.DueRules(
        dueRule(app.kassa.money.domain.DueKind.CARD).value, dueRule(app.kassa.money.domain.DueKind.LOAN).value, dueRule(app.kassa.money.domain.DueKind.BILL).value,
    )
    private fun readDue(kind: app.kassa.money.domain.DueKind): app.kassa.money.domain.DueRule {
        val d = app.kassa.money.domain.DueRule.default(kind)
        val p = "due_${kind.name.lowercase()}"
        return app.kassa.money.domain.DueRule(
            prefs.getBoolean(p + "_on", d.enabled),
            prefs.getInt(p + "_days", d.daysBefore).coerceIn(0, app.kassa.money.domain.DueNotices.MAX_DAYS_BEFORE),
            prefs.getInt(p + "_minute", d.minute).coerceIn(0, 1_439),
        )
    }

    /** Set once Kassa has explained and asked for permission to notify, so it never asks twice. */
    var notifyAsked: Boolean
        get() = prefs.getBoolean(NOTIFY_ASKED, false)
        set(value) { prefs.edit().putBoolean(NOTIFY_ASKED, value).apply() }

    private val _cleared = MutableStateFlow(prefs.getStringSet(CLEARED, emptySet())!!.toSet())
    /** Notifications the user cleared, by key (a card's statement, a suggested bill). They don't come back. */
    val cleared: StateFlow<Set<String>> = _cleared
    fun clear(keys: Collection<String>) {
        val next = _cleared.value + keys
        prefs.edit().putStringSet(CLEARED, next).apply()
        _cleared.value = next
    }

    private val _tip = MutableStateFlow(prefs.getInt(TIP, 0))
    /** Which first-run tip Overview shows next; past the last one, none. */
    val tip: StateFlow<Int> = _tip
    fun setTip(n: Int) { prefs.edit().putInt(TIP, n).apply(); _tip.value = n }

    private val _openTab = MutableStateFlow(prefs.getString(OPEN_TAB, "TODAY")!!)
    /** The tab Kassa shows on a fresh start: TODAY (Overview), ACTIVITY, INSIGHTS or ACCOUNTS. */
    val openTab: StateFlow<String> = _openTab
    fun setOpenTab(v: String) { prefs.edit().putString(OPEN_TAB, v).apply(); _openTab.value = v }

    private val _pins = MutableStateFlow(prefs.getStringSet(PINS, null)?.toSet())
    /** Tiles on Overview, as keys ("acct:<id>", "people"); null until the user picks, meaning the first eight in Accounts order. */
    val pins: StateFlow<Set<String>?> = _pins
    fun setPins(keys: Set<String>) { prefs.edit().putStringSet(PINS, keys).apply(); _pins.value = keys }

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
        const val HIDE_AMOUNTS = "hide_amounts"
        const val ACCOUNT_SORT = "account_sort"
        const val ACCOUNT_ARRANGEMENT = "account_arrangement"
        const val AUTO_BACKUP = "auto_backup"
        const val BACKUP_FOLDER = "backup_folder"
        const val BACKUP_MINUTE = "backup_minute"
        const val LAST_BACKUP = "last_backup"
        const val BACKUP_PROBLEM = "backup_problem"
        const val REMINDER = "daily_reminder"
        const val REMINDER_MINUTE = "daily_reminder_minute"
        const val ACCOUNT_SORT_DESC = "account_sort_desc"
        const val STARTED_FRESH = "started_fresh"
        const val LOCK = "app_lock"
        const val THEME = "theme"
        const val CURRENCY = "currency"
        const val NOTIFY_ASKED = "notify_asked"
        const val CLEARED = "cleared_notifications"
        const val TIP = "first_run_tip"
        const val OPEN_TAB = "open_tab"
        const val PINS = "overview_pins"
    }
}
