package app.kassa.money.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * One account or person: name (and last 4), then the main figure. A long name ellipsizes; the figure and last 4 always
 * stay whole. With [note] or [dueNote] a second line follows (what to pay and when it's due, or when a deposit
 * matures). [grouped] is a row inside a group card: no box of its own, 52dp tall, the group draws the rest.
 */
@Composable
internal fun OneLineRow(
    name: String,
    last4: String?,
    amount: String,
    selected: Boolean = false,
    suffix: String? = null,
    amountColor: Color = Kassa.colors.ink,
    note: String? = null,
    noteColor: Color = Kassa.colors.ink2,
    dueNote: String? = null,
    dueColor: Color = Kassa.colors.ink2,
    grouped: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (grouped) 0.dp else 18.dp)
    val box = if (grouped) Modifier.heightIn(min = 52.dp) else Modifier.clip(shape).background(Kassa.colors.raised)
    Row(
        Modifier.fillMaxWidth().then(box)
            .then(if (selected && grouped) Modifier.background(Kassa.colors.chip) else if (selected) Modifier.border(1.5.dp, Kassa.colors.ink, shape) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.small, color = Kassa.colors.ink3, maxLines = 1, softWrap = false) }
                    suffix?.let { Text("  $it", style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, softWrap = false) }
                }
                Text(amount, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = amountColor, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 10.dp))
            }
            if (note != null || dueNote != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.orEmpty(), style = KassaType.small, color = noteColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                dueNote?.let { Text(it, style = KassaType.small, color = dueColor, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 10.dp)) }
            }
        }
        trailing?.invoke()
    }
}

/**
 * Labelled figures side by side in boxes of their own, like Assets, Liabilities and Net worth at the top of Accounts.
 * A figure that doesn't fit ends in an ellipsis rather than wrapping, so the boxes stay one height.
 */
@Composable
internal fun FigureStrip(figures: List<Triple<String, String, Color>>, large: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        figures.forEach { (label, value, color) ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = KassaType.stat.copy(fontSize = if (large) 20.sp else 15.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two equal labelled figures inside a summary panel, like the card page. */
@Composable
internal fun TwoBoxes(
    a: Pair<String, String>,
    b: Pair<String, String>,
    aColor: Color = Kassa.colors.ink,
    bColor: Color = Kassa.colors.ink,
    /** A small line under a figure, like the due date under a payment. */
    aNote: String? = null,
    bNote: String? = null,
) {
    Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(BoxFigure(a.first, a.second, aColor, aNote), BoxFigure(b.first, b.second, bColor, bNote)).forEach { f ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp)) {
                Text(f.label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(f.value, style = KassaType.stat, color = f.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                f.note?.let { Text(it, style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private data class BoxFigure(val label: String, val value: String, val color: Color, val note: String?)
