package app.kassa.money.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** The first-run tips, shown one at a time on Overview. */
private val tips = listOf(
    R.string.today_tip_first_title to R.string.today_tip_first_body,
    R.string.today_tip_notifications_title to R.string.today_tip_notifications_body,
    R.string.today_tip_insights_title to R.string.today_tip_insights_body,
)

/** The next tip, until the user goes through them or closes them. Settings › Show tips again starts over. */
@Composable
fun TipCard(modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    val n by settings.tip.collectAsState()
    if (n !in tips.indices) return
    val (title, body) = tips[n]
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kassa.colors.ink).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_tip_count, n + 1, tips.size), style = KassaType.tiny, color = Kassa.colors.onInk.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            IconButton(onClick = { settings.setTip(tips.size) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.today_tip_close), tint = Kassa.colors.onInk, modifier = Modifier.size(18.dp))
            }
        }
        Text(stringResource(title), style = KassaType.bodyStrong, color = Kassa.colors.onInk, modifier = Modifier.offset(y = (-6).dp))
        Text(stringResource(body), style = KassaType.small, color = Kassa.colors.onInk.copy(alpha = 0.85f), modifier = Modifier.padding(end = 12.dp))
        TextButton(onClick = { settings.setTip(n + 1) }, modifier = Modifier.align(Alignment.End)) {
            Text(if (n == tips.lastIndex) stringResource(R.string.today_tip_done) else stringResource(R.string.today_tip_next), style = KassaType.bodyStrong, color = Kassa.colors.onInk)
        }
    }
}
