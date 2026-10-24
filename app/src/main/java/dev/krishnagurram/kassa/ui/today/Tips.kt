package dev.krishnagurram.kassa.ui.today

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
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.ui.LocalSettings
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** The first-run tips, shown one at a time on Overview. */
private val tips = listOf(
    "Add your first entry" to "Tap + for an expense, income or transfer. Pick the account each time; Kassa never guesses.",
    "Notifications" to "Bills that are due, card payments and anything else waiting show up at the top of Overview. Nothing is added until you say so.",
    "Insights" to "See where your money goes, what repeats every month and what's creeping up.",
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
            Text("Tip ${n + 1} of ${tips.size}", style = KassaType.tiny, color = Kassa.colors.onInk.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            IconButton(onClick = { settings.setTip(tips.size) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, "Close tips", tint = Kassa.colors.onInk, modifier = Modifier.size(18.dp))
            }
        }
        Text(title, style = KassaType.bodyStrong, color = Kassa.colors.onInk, modifier = Modifier.offset(y = (-6).dp))
        Text(body, style = KassaType.small, color = Kassa.colors.onInk.copy(alpha = 0.85f), modifier = Modifier.padding(end = 12.dp))
        TextButton(onClick = { settings.setTip(n + 1) }, modifier = Modifier.align(Alignment.End)) {
            Text(if (n == tips.lastIndex) "Done" else "Next tip", style = KassaType.bodyStrong, color = Kassa.colors.onInk)
        }
    }
}
