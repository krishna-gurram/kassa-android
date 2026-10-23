package dev.krishnagurram.kassa.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/* The building blocks of the bottom panel on the entry and bill pages, and the app-wide Chip. */

/** The bottom panel: a raised sheet with rounded top corners. */
@Composable
internal fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Kassa.colors.raised).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** The panel's title row: an optional back arrow, the title, and Done when [onDone] is given. */
@Composable
internal fun PanelTitle(title: String, back: (() -> Unit)? = null, onDone: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Text("‹", style = KassaType.body.copy(fontSize = 22.sp), color = Kassa.colors.ink,
            modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(horizontal = 8.dp))
        Text(title, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (onDone != null) DoneButton(onDone)
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    Text("Done", style = KassaType.bodyStrong, color = Kassa.colors.ink,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

/** The panel's title row as a search box, with Done on the right, so searching adds no row of its own. */
@Composable
internal fun PanelSearch(query: String, onQuery: (String) -> Unit, hint: String, onFocus: (Boolean) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query, onValueChange = { onQuery(InputRules.typed(it, InputRules.NAME_MAX)) }, singleLine = true,
            textStyle = KassaType.body.copy(color = Kassa.colors.ink),
            cursorBrush = SolidColor(Kassa.colors.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Kassa.colors.ground).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (query.isEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (query.isNotEmpty()) Text("✕", style = KassaType.body, color = Kassa.colors.ink3,
                        modifier = Modifier.clip(CircleShape).clickable { onQuery("") }.padding(horizontal = 6.dp))
                }
            },
        )
        DoneButton(onDone)
    }
}

/** One choice in a panel grid (a category, an account), outlined when picked. */
@Composable
internal fun PickTile(on: Boolean, minHeight: Dp = 54.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(Kassa.colors.ground)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** A one-tap answer above the pad or calendar, like Today or a card's payable amount. */
@Composable
internal fun QuickButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(44.dp).clip(shape).background(Kassa.colors.ground)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Keys that fill whatever height the panel has, so the pad is as big as the screen allows. Sends "0"-"9", "." or "del". */
@Composable
internal fun NumberPad(modifier: Modifier, onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).heightIn(max = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Kassa.colors.ground).clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == "del") "⌫" else k, style = KassaType.body.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                            color = Kassa.colors.ink, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** A rounded choice used across the app. Long labels ellipsize at 200dp so a chip never fills a row. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    small: Boolean = false,
    outlined: Boolean = false,
    dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val bg = when { selected -> Kassa.colors.ink; outlined || dashed -> Color.Transparent; else -> Kassa.colors.chip }
    var m = Modifier.padding(end = 8.dp, bottom = 8.dp).clip(shape).background(bg)
    if (outlined || dashed) m = m.border(1.dp, if (dashed) Kassa.colors.ink3 else Kassa.colors.line, shape)
    Box(m.clickable(onClick = onClick).padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 7.dp else 9.dp)) {
        Text(
            label, style = if (small) KassaType.small else KassaType.body,
            color = if (selected) Kassa.colors.onInk else if (dashed) Kassa.colors.ink2 else Kassa.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}
