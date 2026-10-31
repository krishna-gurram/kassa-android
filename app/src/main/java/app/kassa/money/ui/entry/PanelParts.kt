package app.kassa.money.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Spacer
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.domain.NumberText
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
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledSp

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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back, modifier = Modifier.size(48.dp)) { Icon(Chevron(left = true), "Back", tint = Kassa.colors.ink) }
        Text(title, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (onDone != null) DoneButton(onDone)
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 6.dp).heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text("Done", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
}

/** The panel's title row as a search box, with Done on the right, so searching adds no row of its own. */
@Composable
internal fun PanelSearch(query: String, onQuery: (String) -> Unit, hint: String, onFocus: (Boolean) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query, onValueChange = { onQuery(InputRules.typed(it, InputRules.NAME_MAX)) }, singleLine = true,
            textStyle = KassaType.body.copy(color = Kassa.colors.ink),
            cursorBrush = SolidColor(Kassa.colors.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Kassa.colors.ground).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (query.isEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, "Clear search", tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
                    }
                    else Spacer(Modifier.size(10.dp))
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
        modifier.heightIn(min = 48.dp).clip(shape).background(Kassa.colors.ground)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
}

/**
 * Keys that fill whatever height the panel has, so the pad is as big as the screen allows. Sends "0"-"9", "." or "del"
 * (the point key shows the phone's decimal mark). The digits follow the font size up to 130%, since the keys don't grow.
 */
@Composable
internal fun NumberPad(modifier: Modifier, onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    val keySize = scaledSp(24f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).heightIn(max = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Kassa.colors.ground).clickable(role = Role.Button) { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (k) { "del" -> "⌫"; "." -> NumberText.decimalMark().toString(); else -> k },
                            style = KassaType.body.copy(fontSize = keySize, fontWeight = FontWeight.Medium),
                            color = Kassa.colors.ink, textAlign = TextAlign.Center,
                            modifier = if (k == "del") Modifier.clearAndSetSemantics { contentDescription = "Delete" } else Modifier,
                        )
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
    // The pill stays its size, but taps land up to 8dp above and below it, so even a small chip has a 48dp touch
    // area. The press ripple stays on the pill.
    val press = remember { MutableInteractionSource() }
    var m = Modifier.clip(shape).background(bg)
    if (outlined || dashed) m = m.border(1.dp, if (dashed) Kassa.colors.ink3 else Kassa.colors.line, shape)
    Box(
        Modifier.padding(end = 8.dp).clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(m.indication(press, ripple()).padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 7.dp else 9.dp)) {
        Text(
            label, style = if (small) KassaType.small else KassaType.body,
            color = if (selected) Kassa.colors.onInk else if (dashed) Kassa.colors.ink2 else Kassa.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
        )
        }
    }
}
