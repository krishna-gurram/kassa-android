package app.kassa.money.ui

import androidx.compose.ui.res.stringResource
import app.kassa.money.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.scaledSp

/** The cover screen's bottom bar (Material's navigation bar height, before the system's own inset). */
val NAV_BAR_HEIGHT = 80.dp

/** Bottom padding that keeps a list's last row clear of the floating Add button. */
val ADD_BUTTON_CLEARANCE = 88.dp

/**
 * The tabs. Cover screen: a bottom bar with Add floating above its right end. A page covers the tabs there, so card,
 * account and category pages show their own Add (see AppRoot). Unfolded (600dp and wider): a rail with Add at its top.
 */
@Composable
fun Shell(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit, showAdd: Boolean, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val addButton: @Composable () -> Unit = { AddButton(onAdd) }
        if (maxWidth >= 600.dp) {
            Scaffold(containerColor = Kassa.colors.ground) { inner ->
                Row(Modifier.fillMaxSize().padding(inner)) {
                    NavigationRail(containerColor = Kassa.colors.ground, header = { Spacer(Modifier.height(8.dp)); addButton(); Spacer(Modifier.height(24.dp)) }) {
                        val colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink, indicatorColor = Kassa.colors.chip,
                            unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                        )
                        Tab.entries.forEach { t ->
                            NavigationRailItem(selected = tab == t, onClick = { onTab(t) }, icon = { Icon(iconFor(t), null) }, label = { Text(t.label) }, colors = colors)
                        }
                    }
                    Box(Modifier.weight(1f)) { content(PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp)) }
                }
            }
        } else {
            Scaffold(
                containerColor = Kassa.colors.ground,
                floatingActionButton = { if (showAdd) addButton() },
                bottomBar = {
                    NavigationBar(containerColor = Kassa.colors.raised) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink, indicatorColor = Kassa.colors.chip,
                            unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                        )
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { onTab(t) },
                                icon = { Icon(iconFor(t), null) },
                                // Labels always show, so selecting a tab changes only the highlight.
                                label = { Text(t.label, fontSize = scaledSp(11f), maxLines = 1, softWrap = false) },
                                alwaysShowLabel = true, colors = colors,
                            )
                        }
                    }
                },
            ) { inner ->
                val bottom = inner.calculateBottomPadding() + if (showAdd) ADD_BUTTON_CLEARANCE else 16.dp
                content(PaddingValues(start = 20.dp, end = 20.dp, top = inner.calculateTopPadding() + 12.dp, bottom = bottom))
            }
        }
    }
}

/** The Add button: ink on ground, the same on the tabs, the rail and pages. */
@Composable
fun AddButton(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick, containerColor = Kassa.colors.ink, contentColor = Kassa.colors.onInk, shape = RoundedCornerShape(18.dp)) {
        Icon(Icons.Outlined.Add, stringResource(R.string.shell_add_entry))
    }
}

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.TODAY -> Icons.Outlined.Dashboard
    Tab.ACTIVITY -> Icons.Outlined.ReceiptLong
    Tab.INSIGHTS -> Icons.Outlined.Insights
    Tab.ACCOUNTS -> Icons.Outlined.AccountBalance
    Tab.SETTINGS -> Icons.Outlined.Settings
}
