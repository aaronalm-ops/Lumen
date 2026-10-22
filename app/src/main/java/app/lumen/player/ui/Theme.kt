package app.lumen.player.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lumen.player.R

/**
 * The design tokens from DESIGN.md section 2. The black set is Variant A (amber on black) and is
 * unchanged since 0.3.0; the light set is round 4, Option C "Graphite and light" (ruled
 * 2026-10-21): charcoal controls with cyan as their light, deep cyan ink for anything thin or
 * textual. Every colour used by a composable comes from [Lumen.colours]; the only hex literals in
 * the UI live in this file.
 *
 * The accent is split in two, as DESIGN.md requires for the light set:
 * - [accentFill] / [onAccentFill]: a filled control and what sits on it (Resume pill, play
 *   button, toggle on, slider active track, label tick). Charcoal with cyan on light; amber with
 *   #111111 on black.
 * - [accentInk]: thin or textual accent on the ground or a card (text buttons, progress and
 *   scrubber fill, current dot, playing timecode, cursor, focus border). Deep cyan on light;
 *   amber on black.
 */
@Immutable
data class LumenColours(
    val base: Color,
    val surface: Color,
    val elevated: Color,
    /** The square behind a row's type glyph. */
    val thumb: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val silver: Color,
    /** Card edge, chip and field borders, the bottom bar rule, the scrubber track. */
    val line: Color,
    /** Hairlines between rows inside a card, and progress tracks. Equal to [line] on black. */
    val divider: Color,
    val danger: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val accentInk: Color,
    /** Selected chip: fill, border and text. Transparent fill with silver on black. */
    val chipSelected: Color,
    val chipSelectedEdge: Color,
    val onChipSelected: Color,
    /** Selected bottom-bar tab: capsule, icon, label. The capsule is transparent on black. */
    val navSelected: Color,
    val onNavSelected: Color,
    val navSelectedLabel: Color,
    /** Toggle off: thumb and outline. */
    val switchOffThumb: Color,
    val switchOffEdge: Color,
    /** Mini bar above the bottom bar: strip, its edge, title, meta, timecode, play disc. */
    val miniBar: Color,
    val miniBarEdge: Color,
    val onMiniBar: Color,
    val onMiniBar2: Color,
    val miniBarTime: Color,
    val miniPlay: Color,
    val onMiniPlay: Color,
    val isDark: Boolean,
)

// Option C, Graphite and light. Contrast (WCAG): text on base 11.11:1, text2 on base 5.93:1,
// text2 on surface 8.67:1, ink on base 4.54:1, ink on surface 6.63:1, cyan on charcoal 10.20:1,
// off-white on charcoal 16.24:1, danger on base 5.24:1, text3 on surface 4.43:1.
private val Charcoal = Color(0xFF18191B)
private val Cyan = Color(0xFF28D9E8)
private val OffWhite = Color(0xFFF4F6F8)

val LightColours = LumenColours(
    base = Color(0xFFC9CED4),
    surface = OffWhite,
    elevated = OffWhite,
    thumb = Color(0xFFE2E6EA),
    text = Charcoal,
    text2 = Color(0xFF41474E),
    text3 = Color(0xFF6C737B),
    silver = Color(0xFF41474E),
    line = Color(0xFFB9BFC6),
    divider = Color(0xFFDCE0E5),
    danger = Color(0xFF9A1A12),
    accentFill = Charcoal,
    onAccentFill = Cyan,
    accentInk = Color(0xFF05616A),
    chipSelected = Charcoal,
    chipSelectedEdge = Charcoal,
    onChipSelected = Cyan,
    navSelected = Charcoal,
    onNavSelected = Cyan,
    navSelectedLabel = Charcoal,
    switchOffThumb = Color(0xFF6C737B),
    switchOffEdge = Color(0xFF6C737B),
    miniBar = Charcoal,
    miniBarEdge = Charcoal,
    onMiniBar = OffWhite,
    onMiniBar2 = Color(0xFF9AA0A6),
    miniBarTime = Cyan,
    miniPlay = Cyan,
    onMiniPlay = Charcoal,
    isDark = false,
)

// Variant A on black, exactly as 0.3.0 drew it.
private val Amber = Color(0xFFE8862B)

val DarkColours = LumenColours(
    base = Color(0xFF000000),
    surface = Color(0xFF111111),
    elevated = Color(0xFF1A1A1A),
    thumb = Color(0xFF1A1A1A),
    text = Color(0xFFF5F5F5),
    text2 = Color(0xFF8C8C8C),
    text3 = Color(0xFF5C5C5C),
    silver = Color(0xFFC7CBD1),
    line = Color(0xFF262626),
    divider = Color(0xFF262626),
    danger = Color(0xFFE25C5C),
    accentFill = Amber,
    onAccentFill = Color(0xFF111111),
    accentInk = Amber,
    chipSelected = Color.Transparent,
    chipSelectedEdge = Color(0xFFC7CBD1),
    onChipSelected = Color(0xFFC7CBD1),
    navSelected = Color.Transparent,
    onNavSelected = Color(0xFFF5F5F5),
    navSelectedLabel = Color(0xFFF5F5F5),
    switchOffThumb = Color(0xFF8C8C8C),
    switchOffEdge = Color(0xFF262626),
    miniBar = Color(0xFF1A1A1A),
    miniBarEdge = Color(0xFF1A1A1A),
    onMiniBar = Color(0xFFF5F5F5),
    onMiniBar2 = Color(0xFF8C8C8C),
    miniBarTime = Color(0xFF8C8C8C),
    miniPlay = Color(0xFFFFFFFF),
    onMiniPlay = Color(0xFF111111),
    isDark = true,
)

/** Variant B hues, kept as 6 dp dots on labels. Index matches LabelEntity.hue. */
@Immutable
data class LabelHue(val name: String, val onLight: Color, val onDark: Color)

val LabelHues = listOf(
    LabelHue("Blue-slate", Color(0xFF3D5A80), Color(0xFF8FB0D9)),
    LabelHue("Violet", Color(0xFF6B4FA0), Color(0xFFB9A6E0)),
    LabelHue("Coral", Color(0xFFB5473C), Color(0xFFF09A8E)),
    LabelHue("Amber", Color(0xFFA65C0A), Color(0xFFF0B25A)),
    LabelHue("Teal", Color(0xFF1F7A78), Color(0xFF7FC9C5)),
    LabelHue("Neutral", Color(0xFF5F646A), Color(0xFF8C8C8C)),
)

@Composable
fun hueColour(index: Int): Color = hueColour(index, onDarkGround = Lumen.colours.isDark)

/**
 * The hue for a dot on a given ground. [onDarkGround] is true on black and also on a charcoal
 * fill on the light screens (a selected chip), where the light tint is the one that reads.
 * On #F4F6F8 cards every light hue clears 3:1 (lowest: amber 4.66:1).
 */
fun hueColour(index: Int, onDarkGround: Boolean): Color {
    val hue = LabelHues.getOrElse(index) { LabelHues.last() }
    return if (onDarkGround) hue.onDark else hue.onLight
}

// The three bundled files are variable fonts; the weight passed here becomes the wght axis.
val Manrope = FontFamily(
    Font(R.font.manrope, weight = FontWeight.W500),
    Font(R.font.manrope, weight = FontWeight.W600),
)
val Inter = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal),
    Font(R.font.inter, weight = FontWeight.W500),
    Font(R.font.inter, weight = FontWeight.W600),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.Normal),
    Font(R.font.jetbrains_mono, weight = FontWeight.W500),
)

/** Type scale from DESIGN.md section 3. Colourless; colour is applied where used. */
object LumenType {
    val displayL = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em)
    val displayM = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em)
    val displayS = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val wordmark = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W600, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 20.sp)
    val bodyS = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em)
    val labelS = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.14.em)
    val timeL = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    val timeS = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
}

val LocalLumenColours = staticCompositionLocalOf { LightColours }

object Lumen {
    val colours: LumenColours
        @Composable get() = LocalLumenColours.current
    val type: LumenType get() = LumenType
}

private fun material(c: LumenColours) = if (c.isDark) {
    darkColorScheme(
        primary = c.accentFill, onPrimary = c.onAccentFill, secondary = c.silver, onSecondary = c.base,
        background = c.base, onBackground = c.text, surface = c.base, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
        surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.elevated, surfaceContainerLow = c.surface,
        outline = c.line, outlineVariant = c.line, error = c.danger, onError = c.base, scrim = c.base,
    )
} else {
    lightColorScheme(
        // Option C: every stock control is charcoal with cyan on it, ink for the tertiary role,
        // and no tonal tint, so a stock component can never show baseline purple.
        primary = c.accentFill, onPrimary = c.onAccentFill,
        primaryContainer = c.accentFill, onPrimaryContainer = c.onAccentFill,
        secondary = c.silver, onSecondary = c.surface,
        secondaryContainer = c.navSelected, onSecondaryContainer = c.onNavSelected,
        tertiary = c.accentInk, onTertiary = c.surface,
        background = c.base, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.base, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceContainerLow = c.surface,
        surfaceTint = c.surface,
        outline = c.line, outlineVariant = c.divider, error = c.danger, onError = c.surface, scrim = c.text,
    )
}

/**
 * Dynamic colour is never used; the two palettes are fixed so no Material purple can appear.
 * Text selection follows the ink (the cursor rule), not the primary fill: on light the primary
 * is charcoal, on black ink and fill are the same amber, so the black screens are unchanged.
 */
@Composable
fun LumenTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colours = if (dark) DarkColours else LightColours
    val selection = TextSelectionColors(handleColor = colours.accentInk, backgroundColor = colours.accentInk.copy(alpha = 0.4f))
    CompositionLocalProvider(LocalLumenColours provides colours) {
        MaterialTheme(colorScheme = material(colours)) {
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}
