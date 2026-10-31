package app.kassa.money.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * What shows while the database opens (and upgrades) on a cold start: the app's name on the ground colour with a small
 * progress ring, so the first frames are calm rather than blank. Uses the theme's colours, so it is right in light and dark.
 */
@Composable
fun StartingScreen() {
    Box(
        Modifier.fillMaxSize().background(Kassa.colors.ground).semantics(mergeDescendants = true) { contentDescription = "Opening Kassa" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kassa", style = KassaType.title, color = Kassa.colors.ink)
            Gap(16)
            CircularProgressIndicator(Modifier.size(24.dp), color = Kassa.colors.ink2, strokeWidth = 2.dp)
        }
    }
}
