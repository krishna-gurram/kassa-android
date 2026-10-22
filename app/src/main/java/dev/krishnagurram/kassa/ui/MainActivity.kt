package dev.krishnagurram.kassa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.ui.accounts.AccountsScreen
import dev.krishnagurram.kassa.ui.activity.ActivityScreen
import dev.krishnagurram.kassa.ui.importer.ImportScreen
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as KassaApplication).repository
        setContent {
            KassaTheme {
                val count by repo.entryCount.collectAsState(initial = -1)
                KassaRoot(entryCount = count)
            }
        }
    }
}

private enum class Tab(val label: String) { ACTIVITY("Activity"), ACCOUNTS("Accounts") }

@Composable
private fun KassaRoot(entryCount: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.ACTIVITY) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var skippedFirstRun by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Kassa.colors.ground)) {
        when {
            entryCount < 0 -> Unit // database still opening
            entryCount == 0 && !skippedFirstRun -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                ImportScreen(firstRun = true, onFinished = { skippedFirstRun = true })
            }
            importing -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                BackHandler { importing = false }
                ImportScreen(firstRun = false, onFinished = { importing = false }, onCancel = { importing = false })
            }
            else -> Shell(tab, onTab = { tab = it }) { padding ->
                when (tab) {
                    Tab.ACTIVITY -> ActivityScreen(padding)
                    Tab.ACCOUNTS -> AccountsScreen(padding, onImportAgain = { importing = true })
                }
            }
        }
    }
}

/**
 * Bottom navigation on the cover screen and phones; a navigation rail once the window is 600dp or wider,
 * which is what the Fold's inner screen reports. Recomposes on its own when the phone folds or unfolds.
 */
@Composable
private fun Shell(tab: Tab, onTab: (Tab) -> Unit, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(containerColor = Kassa.colors.ground) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t, onClick = { onTab(t) },
                            icon = { Icon(iconFor(t), null) }, label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                                indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                            ),
                        )
                    }
                }
                Box(Modifier.weight(1f)) { content(PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp)) }
            }
        } else {
            Scaffold(
                containerColor = Kassa.colors.ground,
                bottomBar = {
                    NavigationBar(containerColor = Kassa.colors.ground) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { onTab(t) },
                                icon = { Icon(iconFor(t), null) }, label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                                    indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                                ),
                            )
                        }
                    }
                },
            ) { inner ->
                content(
                    PaddingValues(
                        start = 20.dp, end = 20.dp,
                        top = inner.calculateTopPadding() + 16.dp,
                        bottom = inner.calculateBottomPadding() + 24.dp,
                    )
                )
            }
        }
    }
}

private fun iconFor(t: Tab): ImageVector = ImageVector.Builder(t.name, 24.dp, 24.dp, 24f, 24f).path(
    stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
) {
    when (t) {
        Tab.ACTIVITY -> { moveTo(5f, 7f); lineTo(19f, 7f); moveTo(5f, 12f); lineTo(19f, 12f); moveTo(5f, 17f); lineTo(14f, 17f) }
        Tab.ACCOUNTS -> {
            moveTo(4f, 9.5f); lineTo(12f, 5f); lineTo(20f, 9.5f)
            moveTo(6f, 11f); lineTo(6f, 18f); moveTo(10f, 11f); lineTo(10f, 18f)
            moveTo(14f, 11f); lineTo(14f, 18f); moveTo(18f, 11f); lineTo(18f, 18f); moveTo(4f, 20f); lineTo(20f, 20f)
        }
    }
}.build()
