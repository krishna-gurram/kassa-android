package app.kassa.money.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.R

/**
 * Kassa's palette. No brand accent: color is reserved for categories and for state (income, attention).
 * Primary actions are ink on ground, the same approach in light and dark.
 */
@Immutable
data class KassaColors(
    val ground: Color,
    val raised: Color,
    val chip: Color,
    val line: Color,
    val track: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val onInk: Color,
    val positive: Color,
    val attention: Color,
    /** Tonal hero blocks: a pale tint for the one summary at the top of a screen, and the ink of its small labels. */
    val heroGreen: Color,
    val heroGreenInk: Color,
    val heroOrange: Color,
    val heroOrangeInk: Color,
    val heroGrey: Color,
    val heroGreyInk: Color,
)

private val Light = KassaColors(
    ground = Color(0xFFF3F4F1), raised = Color(0xFFFFFFFF), chip = Color(0xFFE8EAE5), line = Color(0xFFE0E2DC),
    track = Color(0xFFE4E6E1), ink = Color(0xFF1A1C1F), ink2 = Color(0xFF5E636A), ink3 = Color(0xFF959AA1),
    onInk = Color(0xFFF7F7F4), positive = Color(0xFF0E7A52), attention = Color(0xFFB4531A),
    heroGreen = Color(0xFFE1EDE4), heroGreenInk = Color(0xFF3C6A4B),
    heroOrange = Color(0xFFF6E5D8), heroOrangeInk = Color(0xFF8A4310),
    heroGrey = Color(0xFFE8EAE5), heroGreyInk = Color(0xFF5E636A),
)

private val Dark = KassaColors(
    ground = Color(0xFF111214), raised = Color(0xFF1B1C1F), chip = Color(0xFF25272A), line = Color(0xFF2A2C30),
    track = Color(0xFF2B2D31), ink = Color(0xFFECEDEA), ink2 = Color(0xFFA3A7AD), ink3 = Color(0xFF6E7279),
    onInk = Color(0xFF141517), positive = Color(0xFF48C294), attention = Color(0xFFEF9459),
    heroGreen = Color(0xFF1B2D24), heroGreenInk = Color(0xFF8CC3A2),
    heroOrange = Color(0xFF33221A), heroOrangeInk = Color(0xFFE6A878),
    heroGrey = Color(0xFF25272A), heroGreyInk = Color(0xFFA3A7AD),
)

val LocalKassaColors = staticCompositionLocalOf { Light }

val Hanken = FontFamily(
    Font(R.font.hanken_regular, FontWeight.Normal),
    Font(R.font.hanken_medium, FontWeight.Medium),
    Font(R.font.hanken_semibold, FontWeight.SemiBold),
    Font(R.font.hanken_bold, FontWeight.Bold),
)

/** Tabular figures so amounts line up in columns. */
private const val TNUM = "tnum"

object KassaType {
    val hero = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-2).sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.6).sp)
    val section = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val body = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, fontFeatureSettings = TNUM)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.sp, fontFeatureSettings = TNUM)
    val tiny = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 12.sp, fontFeatureSettings = TNUM)
    /** A small uppercase label over a list (section headers). */
    val label = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp)
    val stat = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFeatureSettings = TNUM)
}

/**
 * A text size that follows the phone's font setting only up to [maxScale] (130% unless told otherwise): for big numerals
 * and keys, which sit in boxes that can't grow with the text. Everything else uses [KassaType] and scales fully.
 */
@Composable
fun scaledSp(sp: Float, maxScale: Float = 1.3f): TextUnit {
    val density = LocalDensity.current
    return with(density) { (sp * minOf(density.fontScale, maxScale)).dp.toSp() }
}

/** [base] grown with the font setting (never below it), for a chart or bar whose labels grow with the text. */
@Composable
fun scaledDp(base: Dp): Dp = base * LocalDensity.current.fontScale.coerceAtLeast(1f)

@Composable
fun KassaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.ink, onPrimary = c.onInk, background = c.ground, onBackground = c.ink,
            surface = c.raised, onSurface = c.ink, surfaceVariant = c.chip, onSurfaceVariant = c.ink2,
            outline = c.line, secondaryContainer = c.chip, onSecondaryContainer = c.ink, surfaceContainerLow = c.raised,
            surfaceContainer = c.ground, error = c.attention,
        )
    } else {
        lightColorScheme(
            primary = c.ink, onPrimary = c.onInk, background = c.ground, onBackground = c.ink,
            surface = c.raised, onSurface = c.ink, surfaceVariant = c.chip, onSurfaceVariant = c.ink2,
            outline = c.line, secondaryContainer = c.chip, onSecondaryContainer = c.ink, surfaceContainerLow = c.raised,
            surfaceContainer = c.ground, error = c.attention,
        )
    }
    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = Hanken),
        bodyMedium = base.bodyMedium.copy(fontFamily = Hanken),
        bodySmall = base.bodySmall.copy(fontFamily = Hanken),
        labelLarge = base.labelLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = Hanken),
        titleLarge = base.titleLarge.copy(fontFamily = Hanken, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Hanken, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Hanken),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalKassaColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

object Kassa {
    val colors: KassaColors @Composable get() = LocalKassaColors.current
}
