package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Money
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * The tint of a hero block. GREEN is neutral or in your favour, ORANGE is owed or due, GREY is for lists. While
 * balances are hidden every hero turns GREY, so a tint can never give the figure away.
 */
enum class HeroTone { GREEN, ORANGE, GREY }

private val LocalHeroInk = staticCompositionLocalOf { Color.Unspecified }

/**
 * The one summary at the top of a screen: a pale tinted block with generous corners. Labels inside use [HeroLabel],
 * figures use the usual money helpers (their own ink stays readable on every tint, light and dark).
 */
@Composable
fun TonalHero(tone: HeroTone, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shown = if (Money.isHidden) HeroTone.GREY else tone
    val k = Kassa.colors
    val bg = when (shown) { HeroTone.GREEN -> k.heroGreen; HeroTone.ORANGE -> k.heroOrange; HeroTone.GREY -> k.heroGrey }
    val ink = when (shown) { HeroTone.GREEN -> k.heroGreenInk; HeroTone.ORANGE -> k.heroOrangeInk; HeroTone.GREY -> k.heroGreyInk }
    CompositionLocalProvider(LocalHeroInk provides ink) {
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(horizontal = 18.dp, vertical = 14.dp), content = content)
    }
}

/** The ink a hero's small labels use. */
@Composable
fun heroInk(): Color = LocalHeroInk.current.let { if (it == Color.Unspecified) Kassa.colors.ink2 else it }

/** A small label inside a hero ("Net worth", "Balance payable"). */
@Composable
fun HeroLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold), color = heroInk(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A hairline across a hero, before its footer figures. */
@Composable
fun HeroRule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 10.dp), color = heroInk().copy(alpha = 0.2f))
}

/** Two labelled figures along the bottom of a hero ("In $4,971.00", "Out $4,118.52"); they wrap under each other at large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroFoot(leftLabel: String, leftValue: String, rightLabel: String? = null, rightValue: String? = null) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HeroPair(leftLabel, leftValue)
        if (rightLabel != null && rightValue != null) HeroPair(rightLabel, rightValue)
    }
}

@Composable
private fun HeroPair(label: String, value: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        Text(label, style = KassaType.small, color = heroInk(), maxLines = 1)
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}
