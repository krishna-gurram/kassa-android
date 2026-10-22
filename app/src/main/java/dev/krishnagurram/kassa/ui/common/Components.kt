package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryIcon
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Category icon in a circle tinted with the category's own color. */
@Composable
fun CategoryBadge(category: Category?, size: Int = 40) {
    val tint = category?.let { Color(it.color) } ?: Kassa.colors.ink3
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.17f)),
        contentAlignment = Alignment.Center,
    ) {
        when (val icon = category?.icon) {
            is CategoryIcon.Emoji -> Text(icon.value, fontSize = (size * 0.46).sp)
            // Symbol and picture icons arrive with the category editor in milestone 2.
            else -> Text(category?.name?.take(1) ?: "?", style = KassaType.bodyStrong, color = tint)
        }
    }
}

/** Neutral circle with a letter or glyph, for transfers, accounts and people. */
@Composable
fun GlyphBadge(text: String, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Kassa.colors.chip),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = KassaType.bodyStrong, color = Kassa.colors.ink2) }
}

@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = KassaType.section, color = Kassa.colors.ink)
        if (trailing != null) Text(trailing, style = KassaType.small, color = Kassa.colors.ink2)
    }
}

/** The standard list row: badge, title over subtitle, amount over a note. */
@Composable
fun EntryRow(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String,
    amount: String,
    amountColor: Color = Kassa.colors.ink,
    note: String? = null,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            badge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, style = KassaType.bodyStrong, color = amountColor, maxLines = 1)
                if (note != null) Text(note, style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1)
            }
        }
        if (showDivider) HorizontalDivider(color = Kassa.colors.line, thickness = 1.dp)
    }
}

/** A value with a small label above it, used in stat rows. */
@Composable
fun Stat(label: String, value: String, color: Color = Kassa.colors.ink, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = KassaType.tiny, color = Kassa.colors.ink2)
        Text(value, style = KassaType.stat, color = color, maxLines = 1)
    }
}

/** A bordered panel for things that need attention, like import review items. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Kassa.colors.raised)
            .border(1.dp, Kassa.colors.line, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
fun Gap(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))
