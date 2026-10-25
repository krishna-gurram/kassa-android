package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.clipToBounds
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
        title = { Text(title, style = KassaType.section, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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

/** A label and a value on one line, like Settings rows. With [onClick] it shows a chevron and opens a page. */
@Composable
fun ValueRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = Kassa.colors.ink, onClick: (() -> Unit)? = null, last: Boolean = false) {
    Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Text(value, style = KassaType.bodyStrong, color = color)
            if (onClick != null) Icon(Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.padding(start = 6.dp))
        }
        if (!last) androidx.compose.material3.HorizontalDivider(color = Kassa.colors.line)
    }
}

/** ‹ Label › with an optional line under it. Used for months on Activity and accounts, and statements on cards. */
@Composable
fun PeriodSwitcher(label: String, sub: String?, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundArrow(left = true, enabled = canBack, onClick = onBack)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
            if (sub != null) Text(sub, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1)
        }
        RoundArrow(left = false, enabled = canForward, onClick = onForward)
    }
}

@Composable
private fun RoundArrow(left: Boolean, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Kassa.colors.chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Chevron(left = left), if (left) "Previous" else "Next", tint = if (enabled) Kassa.colors.ink else Kassa.colors.ink3, modifier = Modifier.size(20.dp)) }
}

/** Swipe left or right across the content to step [onShift] by -1 (back in time) or +1. */
fun Modifier.swipeShift(onShift: (Int) -> Unit): Modifier = this.pointerInput(onShift) {
    var dx = 0f
    detectHorizontalDragGestures(
        onDragStart = { dx = 0f },
        onDragEnd = { if (dx > 120f) onShift(-1) else if (dx < -120f) onShift(1) },
        onHorizontalDrag = { _, d -> dx += d },
    )
}

/**
 * Items top to bottom, as many as fit whole in the height it's given; the rest are left out rather than cut
 * or scrolled. Gives Overview and Insights a screen that fills without scrolling on any screen size.
 */
@Composable
fun FitColumn(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placed = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val need = used + (if (placed.isEmpty()) 0 else gap) + p.height
            if (constraints.hasBoundedHeight && need > constraints.maxHeight) break
            used = need; placed += p
        }
        val h = if (constraints.hasBoundedHeight) maxOf(constraints.minHeight, used) else used
        layout(constraints.maxWidth, h) {
            var y = 0
            placed.forEachIndexed { i, p -> if (i > 0) y += gap; p.place(0, y); y += p.height }
        }
    }
}

/** Tall enough to lay a tab out to fit the screen instead of scrolling it (the cover screen in landscape isn't). */
val FIT_MIN_HEIGHT = 560.dp

/**
 * ‹ label › for stepping through months or weeks. [onBack] goes earlier; [onForward] is null at the current one.
 * The category page and the spending page both use it, with a swipe on the page doing the same.
 */
@androidx.compose.runtime.Composable
fun PeriodSwitcher(label: String, onBack: () -> Unit, onForward: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundArrow(left = true, enabled = true, onClick = onBack)
        Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RoundArrow(left = false, enabled = onForward != null) { onForward?.invoke() }
    }
}

@androidx.compose.runtime.Composable
private fun RoundArrow(left: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Kassa.colors.chip), contentAlignment = Alignment.Center) {
            Icon(Chevron(left = left), if (left) "Earlier" else "Later", tint = if (enabled) Kassa.colors.ink else Kassa.colors.ink3, modifier = Modifier.size(20.dp))
        }
    }
}
