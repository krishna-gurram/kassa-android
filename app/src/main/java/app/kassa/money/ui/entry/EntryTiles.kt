package app.kassa.money.ui.entry

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.R
import app.kassa.money.ui.common.figureColor
import app.kassa.money.domain.Account
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.Money
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.common.CategoryIconView
import app.kassa.money.ui.common.accountIcon
import app.kassa.money.ui.common.monthShort
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledSp
import java.time.LocalDate

/* The top half of the entry and bill pages: the amount, the tiles that open each input, the note and the split line. */

/**
 * The amount, big and centred, with the currency symbol in the same size, weight and color, the way banking apps
 * show it. A symbol made of letters (AED) gets a space before the number.
 */
@Composable
internal fun AmountHero(amount: String, income: Boolean, large: Boolean, onClick: () -> Unit) {
    val size = if (large) 60f else 52f
    val color = when { amount.isEmpty() -> Kassa.colors.ink3; income -> Kassa.colors.positive; else -> Kassa.colors.ink }
    val symbol = Money.symbol.let { if (it.last().isLetter()) "$it\u00A0" else it }
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            symbol + displayAmount(amount),
            style = KassaType.hero.copy(fontSize = scaledSp(size), letterSpacing = (-1.5).sp),
            color = color, maxLines = 1, softWrap = false,
        )
    }
}

/** A raised tile with a small label; shows "+ Choose" while [empty]. Outlined while its input is open. */
@Composable
internal fun Tile(label: String, on: Boolean, empty: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxHeight().heightIn(min = 88.dp).clip(shape).background(Kassa.colors.raised)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, maxLines = 1)
        if (empty) {
            Text("+", style = KassaType.body.copy(fontSize = 18.sp), color = Kassa.colors.ink2)
            Text(stringResource(R.string.entry_choose), style = KassaType.body, color = Kassa.colors.ink3, maxLines = 1)
        } else content()
    }
}

@Composable
internal fun CategoryTile(top: Category?, sub: Category?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile(stringResource(R.string.entry_category), on, empty = top == null, modifier = modifier, onClick = onClick) {
        if (top != null) {
            CategoryIconView(top.icon, Color(top.color), 28, fallback = top.name.take(1))
            Text(top.name, style = KassaType.bodyStrong.copy(fontSize = 14.sp), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub.name, style = KassaType.tiny.copy(fontSize = 11.sp), color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An account tile: icon, name, last 4, and what's payable when it's a card and [showDue]. */
@Composable
internal fun AccountTile(
    label: String, account: Account?, cards: Map<Long, CardSnapshot>, showDue: Boolean, twoLines: Boolean,
    on: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    Tile(label, on, empty = account == null, modifier = modifier, onClick = onClick) {
        if (account != null) {
            Icon(accountIcon(account.type), null, tint = Kassa.colors.ink2, modifier = Modifier.size(18.dp))
            Text(account.name, style = KassaType.bodyStrong.copy(fontSize = 14.sp, lineHeight = 17.sp), color = Kassa.colors.ink,
                maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
            account.last4?.takeIf { it.isNotBlank() }?.let { Text("··$it", style = KassaType.tiny.copy(fontSize = 11.sp), color = Kassa.colors.ink3, maxLines = 1) }
            val due = cards[account.id]?.payable ?: 0L
            if (showDue && due > 0) Text(stringResource(R.string.entry_amount_due, Money.format(due)), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = Kassa.colors.attention, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DateTile(date: LocalDate, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile(stringResource(R.string.entry_date), on, empty = false, modifier = modifier, onClick = onClick) {
        Text(
            monthShort(date).uppercase(),
            style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Kassa.colors.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(date.dayOfMonth.toString(), style = KassaType.hero.copy(fontSize = 26.sp, letterSpacing = 0.sp, lineHeight = 28.sp), color = Kassa.colors.ink)
    }
}

/** The note (or bill name) on one underlined line. The caller limits what's typed. */
@Composable
internal fun NoteRow(value: String, onChange: (String) -> Unit, focused: Boolean, hint: String, requester: FocusRequester, onFocused: () -> Unit, onDone: () -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = KassaType.body.copy(color = Kassa.colors.ink),
        cursorBrush = SolidColor(Kassa.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused() },
        decorationBox = { inner ->
            Column {
                Box(Modifier.padding(horizontal = 2.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(if (focused) Kassa.colors.ink else Kassa.colors.line))
            }
        },
    )
}

/** The Split line under the note: who shares the expense and what your share is. */
@Composable
internal fun SplitRow(who: String, mine: Long?, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).background(Kassa.colors.raised)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.entry_split), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, modifier = Modifier.widthIn(min = 44.dp))
        Column(Modifier.weight(1f)) {
            Text(who, style = KassaType.bodyStrong.copy(fontSize = 14.sp), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (mine != null) Text(stringResource(R.string.entry_your_share, Money.format(mine)), style = KassaType.tiny, color = if (mine < 0) figureColor(Kassa.colors.attention) else Kassa.colors.ink2)
        }
    }
}

/** Expense, income or transfer. */
@Composable
internal fun KindSwitch(selected: TxKind, onSelect: (TxKind) -> Unit, modifier: Modifier) {
    val options = listOf(TxKind.EXPENSE to stringResource(R.string.entry_expense), TxKind.INCOME to stringResource(R.string.entry_income), TxKind.TRANSFER to stringResource(R.string.entry_transfer))
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (kind, label) ->
            val on = kind == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Kassa.colors.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(kind) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = KassaType.bodyStrong, color = if (on) Kassa.colors.ink else Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
