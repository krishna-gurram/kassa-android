package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** The bar at the top of every page: back, then the page's name. Without [onBack] the space stays empty. */
@Composable
fun PageTop(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { Icon(Chevron(left = true), "Back", tint = Kassa.colors.ink) }
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(title, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** The one raised surface on a screen, for its summary. */
@Composable
fun RaisedPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Kassa.colors.raised).padding(18.dp), content = content)
}

enum class Tone { NEUTRAL, ATTENTION, POSITIVE }

/** A small rounded label for state: due soon, paid in full and similar. */
@Composable
fun StatusChip(text: String, tone: Tone = Tone.NEUTRAL) {
    val fg = when (tone) { Tone.NEUTRAL -> Kassa.colors.ink2; Tone.ATTENTION -> Kassa.colors.attention; Tone.POSITIVE -> Kassa.colors.positive }
    val bg = when (tone) { Tone.NEUTRAL -> Kassa.colors.chip; else -> fg.copy(alpha = 0.14f) }
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = KassaType.tiny, color = fg, maxLines = 1)
    }
}

/**
 * "Are you sure?" before anything that removes or replaces data. The confirm button names the action,
 * the dismiss button keeps things as they are.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kassa.colors.raised,
        title = { Text(title, style = KassaType.section, color = Kassa.colors.ink) },
        text = { Text(body, style = KassaType.body, color = Kassa.colors.ink2) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, style = KassaType.bodyStrong, color = if (destructive) Kassa.colors.attention else Kassa.colors.ink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, style = KassaType.bodyStrong, color = Kassa.colors.ink) } },
    )
}

/** What the right pane shows on the unfolded screen before anything is picked. */
@Composable
fun PanePlaceholder(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = KassaType.body, color = Kassa.colors.ink3)
    }
}

fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"
