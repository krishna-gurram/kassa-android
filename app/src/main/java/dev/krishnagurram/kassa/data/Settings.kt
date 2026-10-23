package dev.krishnagurram.kassa.data

import android.content.Context
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
        dev.krishnagurram.kassa.domain.Money.style = dev.krishnagurram.kassa.domain.Currencies.byCode(it.value)
    }
    /** The one currency this phone shows. Amounts are never converted when it changes. */
    val currency: StateFlow<String> = _currency
    fun setCurrency(code: String) {
        prefs.edit().putString(CURRENCY, code).apply()
        dev.krishnagurram.kassa.domain.Money.style = dev.krishnagurram.kassa.domain.Currencies.byCode(code)
        _currency.value = code
    }

    private val _accountSort = MutableStateFlow(prefs.getString(ACCOUNT_SORT, "NAME")!!)
    /** How the Accounts tab orders every section: NAME, BALANCE or DUE (cards by due date, the rest by name). */
    val accountSort: StateFlow<String> = _accountSort
    private val _accountSortDesc = MutableStateFlow(prefs.getBoolean(ACCOUNT_SORT_DESC, false))
    /** Reverses [accountSort]: Z to A, smallest first, latest due first. */
    val accountSortDesc: StateFlow<Boolean> = _accountSortDesc
    fun setAccountSort(v: String) { prefs.edit().putString(ACCOUNT_SORT, v).putBoolean(ACCOUNT_SORT_DESC, false).apply(); _accountSort.value = v; _accountSortDesc.value = false }
    fun setAccountSortDesc(v: Boolean) { prefs.edit().putBoolean(ACCOUNT_SORT_DESC, v).apply(); _accountSortDesc.value = v }

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
        const val ACCOUNT_SORT = "account_sort"
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
    }
}
