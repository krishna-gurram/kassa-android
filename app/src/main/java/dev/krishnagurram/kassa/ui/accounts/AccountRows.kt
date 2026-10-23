package dev.krishnagurram.kassa.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** A rounded square holding an account type's icon. */
internal fun iconTile(icon: ImageVector): @Composable () -> Unit = {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Kassa.colors.ground), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Kassa.colors.ink2, modifier = Modifier.size(20.dp))
    }
}

/** The same square with a person's initial. */
internal fun letterTile(name: String): @Composable () -> Unit = {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Kassa.colors.ground), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = Kassa.colors.ink2)
    }
}

/**
 * One account or person on a single line: tile, name (and last 4), amount. A long name ellipsizes; the amount and
 * last 4 always stay whole.
 */
@Composable
internal fun OneLineRow(
    tile: @Composable () -> Unit,
    name: String,
    last4: String?,
    amount: String,
    selected: Boolean = false,
    suffix: String? = null,
    amountColor: Color = Kassa.colors.ink,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised)
            .then(if (selected) Modifier.border(1.5.dp, Kassa.colors.ink, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Row(Modifier.weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.small, color = Kassa.colors.ink3, maxLines = 1, softWrap = false) }
            suffix?.let { Text("  $it", style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, softWrap = false) }
        }
        Text(amount, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = amountColor, maxLines = 1, softWrap = false)
    }
}

/** Two equal labelled figures inside a summary panel, like the card page. */
@Composable
internal fun TwoBoxes(
    a: Pair<String, String>,
    b: Pair<String, String>,
    aColor: Color = Kassa.colors.ink,
    bColor: Color = Kassa.colors.ink,
) {
    Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple(a.first, a.second, aColor), Triple(b.first, b.second, bColor)).forEach { (label, value, color) ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp)) {
                Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = KassaType.stat, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
