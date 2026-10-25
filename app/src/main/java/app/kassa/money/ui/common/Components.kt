package app.kassa.money.ui.common

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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Category icon in a circle tinted with the category's own color. */
@Composable
fun CategoryBadge(category: Category?, size: Int = 40) {
    val tint = category?.let { Color(it.color) } ?: Kassa.colors.ink3
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.17f)),
        contentAlignment = Alignment.Center,
    ) {
        CategoryIconView(category?.icon, tint, size, fallback = category?.name?.take(1) ?: "?")
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
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) Text(trailing, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
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
    /** Shows a chevron when the row opens a page. */
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
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
                trailing?.invoke()
            }
            if (chevron) androidx.compose.material3.Icon(
                app.kassa.money.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3,
                modifier = Modifier.padding(start = 6.dp).size(20.dp),
            )
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

/** Draws any category icon: an emoji, a bundled symbol tinted with the category color, or the user's picture. */
@Composable
fun CategoryIconView(icon: CategoryIcon?, tint: Color, size: Int, fallback: String = "?") {
    when (icon) {
        is CategoryIcon.Emoji -> Text(icon.value, fontSize = (size * 0.46).sp)
        is CategoryIcon.Symbol -> {
            val v = app.kassa.money.ui.categories.IconCatalog.vector(icon.name)
            if (v != null) androidx.compose.material3.Icon(v, null, tint = tint, modifier = Modifier.size((size * 0.55).dp))
            else Text(fallback, style = KassaType.bodyStrong, color = tint)
        }
        is CategoryIcon.Image -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val bmp = androidx.compose.runtime.remember(icon.fileName) { app.kassa.money.data.IconImages.load(context, icon.fileName) }
            if (bmp != null) androidx.compose.foundation.Image(
                bmp.asImageBitmap(), null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(CircleShape),
            ) else Text(fallback, style = KassaType.bodyStrong, color = tint)
        }
        null -> Text(fallback, style = KassaType.bodyStrong, color = tint)
    }
}

/** A filled search field, for searching within a page (icons, say). Stops at 60 characters. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, hint: String) {
    TextField(
        value = value, onValueChange = { onChange(InputRules.typed(it, 60)) }, singleLine = true,
        placeholder = { Text(hint, style = KassaType.body) }, textStyle = KassaType.body,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Kassa.colors.chip, unfocusedContainerColor = Kassa.colors.chip,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
