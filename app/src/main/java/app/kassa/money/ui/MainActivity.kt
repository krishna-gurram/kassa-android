package app.kassa.money.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.kassa.money.BuildConfig
import app.kassa.money.KassaApplication
import app.kassa.money.data.db.DatabaseOpen
import app.kassa.money.data.jobs.DailyJobs
import app.kassa.money.data.jobs.DueNotifier
import app.kassa.money.domain.DueKind
import app.kassa.money.ui.start.CantOpenScreen
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaTheme
import app.kassa.money.ui.theme.KassaType

/**
 * The only activity: theme, app lock and the intents that open Add (daily reminder) or a due payment (its notification). Everything else is [AppRoot].
 * A FragmentActivity because the biometric prompt needs one.
 */
class MainActivity : FragmentActivity() {
    /**
     * Whether the lock screen is up. Kept across recreation (folding, rotating, a theme change) in the saved state, so
     * only a cold start or a minute away locks; see [onStart].
     */
    private val locked = mutableStateOf(false)
    /** Set when the daily reminder is tapped: open Add once the app is showing. */
    private val openAdd = mutableStateOf(false)
    /** Set when a payment-due notification is tapped: open that card, loan or bill once the app is showing. */
    private val openDue = mutableStateOf<DueTarget?>(null)
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.action == DailyJobs.ACTION_ADD) openAdd.value = true
        if (savedInstanceState == null) openDue.value = dueTarget(intent)
        // Debug builds only: starting with `--ez demo true` loads the demo household, for CI screenshots.
        if (BuildConfig.DEBUG && savedInstanceState == null && intent?.getBooleanExtra("demo", false) == true)
            androidx.lifecycle.ViewModelProvider(this)[LedgerViewModel::class.java].loadDemo()
        // Debug builds only: `--es importFile name` opens that file from the app's files folder on the Import page.
        if (BuildConfig.DEBUG && savedInstanceState == null)
            intent?.getStringExtra("importFile")?.let { app.kassa.money.ui.importer.DebugImport.pending = android.net.Uri.fromFile(java.io.File(filesDir, it)) }
        val app = application as KassaApplication
        // A cold start locks when App lock is on. After recreation the state it had comes back, and so does the time the
        // app was last hidden, which matters if the process was killed meanwhile: a minute away still locks.
        locked.value = savedInstanceState?.getBoolean(STATE_LOCKED) ?: app.settings.lockEnabled.value
        hiddenAt = savedInstanceState?.getLong(STATE_HIDDEN_AT) ?: 0L
        // Before the first frame, so a thumbnail of the opening screen is never taken; the effect below keeps it in step.
        if (app.settings.lockEnabled.value || app.settings.hideAmounts) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val theme by app.settings.theme.collectAsState()
            val dark = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val currency by app.settings.currency.collectAsState()
            val lockOn by app.settings.lockEnabled.collectAsState()
            // Recents thumbnails and screenshots would show balances: keep them out while App lock or Hide balances is on.
            // FLAG_SECURE works on every Android version, unlike setRecentsScreenshotEnabled (33 and later).
            // Debug builds only: `--ez noSecure true` lets CI screenshots capture the Hide balances view.
            val noSecure = BuildConfig.DEBUG && intent?.getBooleanExtra("noSecure", false) == true
            val secure = (lockOn || app.settings.hideAmounts) && !noSecure
            LaunchedEffect(secure) {
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            // null while the database opens (off the main thread); a failure shows the can't-open page, not a crash.
            var database by remember { mutableStateOf<DatabaseOpen?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { database = withContext(Dispatchers.IO) { app.openDatabase() } }
            val reopen: (() -> DatabaseOpen) -> Unit = { action ->
                database = null
                scope.launch { database = withContext(Dispatchers.IO) { action() } }
            }

            // key(currency): everything redraws in the new currency when it changes.
            CompositionLocalProvider(LocalSettings provides app.settings) {
                key(currency) {
                    KassaTheme(dark = dark) {
                        if (locked.value && lockOn) {
                            LockScreen(onUnlock = ::unlock)
                            LaunchedEffect(Unit) { unlock() }
                        } else when (val db = database) {
                            null, is DatabaseOpen.Ready -> {
                                if (db != null) {
                                    val count by app.entries.count.collectAsState(initial = -1)
                                    AppRoot(
                                        entryCount = count, startedFresh = app.settings.startedFresh, onStartFresh = { app.settings.startedFresh = true },
                                        openAdd = openAdd.value, onAddOpened = { openAdd.value = false },
                                        openDue = openDue.value, onDueOpened = { openDue.value = null },
                                    )
                                }
                            }
                            is DatabaseOpen.Failed -> CantOpenScreen(
                                db.problem,
                                onRetry = { reopen { app.retryOpenDatabase() } },
                                onErase = { reopen { app.eraseAndStartOver() } },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == DailyJobs.ACTION_ADD) openAdd.value = true
        dueTarget(intent)?.let { openDue.value = it }
    }

    /** The card, loan or bill a payment-due notification asks to open, if this intent is one. */
    private fun dueTarget(intent: Intent?): DueTarget? {
        if (intent?.action != DueNotifier.ACTION_OPEN_DUE) return null
        val kind = DueKind.entries.firstOrNull { it.name == intent.getStringExtra(DueNotifier.EXTRA_KIND) } ?: return null
        val id = intent.getLongExtra(DueNotifier.EXTRA_ID, 0L)
        return if (id != 0L) DueTarget(kind, id) else null
    }

    override fun onStop() {
        super.onStop()
        hiddenAt = System.currentTimeMillis()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOCKED, locked.value)
        outState.putLong(STATE_HIDDEN_AT, hiddenAt)
    }

    override fun onStart() {
        super.onStart()
        val app = application as KassaApplication
        // Lock again after a minute away.
        if (app.settings.lockEnabled.value && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 60_000) locked.value = true
    }

    private companion object {
        const val STATE_LOCKED = "locked"
        const val STATE_HIDDEN_AT = "hiddenAt"
    }

    private fun unlock() {
        val auth = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kassa")
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(auth)
                else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
            }
            .build()
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // No screen lock set up at all: don't trap the user out of their own data.
                    if (code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == BiometricPrompt.ERROR_HW_NOT_PRESENT) locked.value = false
                }
            })
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kassa.colors.ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kassa is locked", style = KassaType.section, color = Kassa.colors.ink)
            TextButton(onClick = onUnlock) { Text("Unlock", color = Kassa.colors.ink) }
        }
    }
}
