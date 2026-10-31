package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
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
import app.kassa.money.domain.Money
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledSp

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

/**
 * A small uppercase label over a list, a hairline under it, and an optional [trailing] note on the right. With
 * [onClick] the whole line is a 48dp target and the note carries a chevron ("Next 30 days ›").
 */
@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = if (onClick != null) 48.dp else 32.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                title.uppercase(java.util.Locale.getDefault()), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = KassaType.label, color = Kassa.colors.ink2, modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(trailing, style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (onClick != null) Kassa.colors.ink else Kassa.colors.ink2, maxLines = 1)
                if (onClick != null) androidx.compose.material3.Icon(app.kassa.money.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink, modifier = Modifier.size(16.dp))
            }
        }
        HorizontalDivider(color = Kassa.colors.line, thickness = 1.dp)
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
    /** A small label after the title, like "interest" on a loan payment's entry. */
    tag: String? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            badge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (tag != null) Text(
                        tag, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp).border(1.dp, Kassa.colors.line, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
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
        is CategoryIcon.Emoji -> Text(icon.value, fontSize = scaledSp((size * 0.46).toFloat(), maxScale = 1f))
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

/**
 * The one rule for coloring money everywhere: green when it's in your favour, orange when it's against you, ink for
 * ordinary amounts like spending and transfers. [favour] is positive in your favour, negative against, zero neither.
 */
@Composable
fun favourColor(favour: Long, plain: Color = Kassa.colors.ink): Color = when {
    Money.isHidden -> plain
    favour > 0 -> Kassa.colors.positive
    favour < 0 -> Kassa.colors.attention
    else -> plain
}

/** An account's balance: orange only when money you own (a bank, cash, investments) goes below zero. */
@Composable
fun balanceColor(type: app.kassa.money.domain.AccountType, balance: Long): Color =
    if (!Money.isHidden && !type.isDebt && balance < 0) Kassa.colors.attention else Kassa.colors.ink

/**
 * [color] for an amount whose color says something about the figure (over budget, a loss, a gain). While amounts are
 * hidden it's plain ink, so color can't give the figure away. Colors that follow a kind of entry, not its size
 * (income is always green), don't need this.
 */
@Composable
fun figureColor(color: Color): Color = if (Money.isHidden) Kassa.colors.ink else color
