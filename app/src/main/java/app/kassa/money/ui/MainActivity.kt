package app.kassa.money.ui

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.kassa.money.KassaApplication
import app.kassa.money.data.jobs.DailyJobs
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaTheme
import app.kassa.money.ui.theme.KassaType

/**
 * The only activity: theme, app lock and the reminder's "open Add" intent. Everything else is [AppRoot].
 * A FragmentActivity because the biometric prompt needs one.
 */
class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    /** Set when the daily reminder is tapped: open Add once the app is showing. */
    private val openAdd = mutableStateOf(false)
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.action == DailyJobs.ACTION_ADD) openAdd.value = true
        val app = application as KassaApplication
        if (app.settings.lockEnabled.value) locked.value = true
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
            val count by app.entries.count.collectAsState(initial = -1)
            LaunchedEffect(lockOn) { if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lockOn) }

            // key(currency): everything redraws in the new currency when it changes.
            CompositionLocalProvider(LocalSettings provides app.settings) {
                key(currency) {
                    KassaTheme(dark = dark) {
                        if (locked.value && lockOn) {
                            LockScreen(onUnlock = ::unlock)
                            LaunchedEffect(Unit) { unlock() }
                        } else {
                            AppRoot(
                                entryCount = count, startedFresh = app.settings.startedFresh, onStartFresh = { app.settings.startedFresh = true },
                                openAdd = openAdd.value, onAddOpened = { openAdd.value = false },
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
    }

    override fun onStop() {
        super.onStop()
        hiddenAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val app = application as KassaApplication
        // Lock again after a minute away.
        if (app.settings.lockEnabled.value && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 60_000) locked.value = true
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
