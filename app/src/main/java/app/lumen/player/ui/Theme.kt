package app.lumen.player.ui

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
 * The design tokens from DESIGN.md, Variant A. Every colour used by a composable comes from
 * [Lumen.colours]; the only hex literals in the UI live in this file.
 */
@Immutable
data class LumenColours(
    val base: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val silver: Color,
    val line: Color,
    val danger: Color,
    val inverseBg: Color,
    val inverseFg: Color,
    val accent: Color,
    val onAccent: Color,
    val isDark: Boolean,
)

val LightColours = LumenColours(
    base = Color(0xFFF3F4F6),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    text = Color(0xFF111111),
    text2 = Color(0xFF5F646A),
    text3 = Color(0xFF9A9EA4),
    silver = Color(0xFF5F646A),
    line = Color(0xFFDDDFE2),
    danger = Color(0xFFB42318),
    inverseBg = Color(0xFF111111),
    inverseFg = Color(0xFFFFFFFF),
    accent = Color(0xFFC2410C),
    onAccent = Color(0xFFFFFFFF),
    isDark = false,
)

val DarkColours = LumenColours(
    base = Color(0xFF000000),
    surface = Color(0xFF111111),
    elevated = Color(0xFF1A1A1A),
    text = Color(0xFFF5F5F5),
    text2 = Color(0xFF8C8C8C),
    text3 = Color(0xFF5C5C5C),
    silver = Color(0xFFC7CBD1),
    line = Color(0xFF262626),
    danger = Color(0xFFE25C5C),
    inverseBg = Color(0xFFFFFFFF),
    inverseFg = Color(0xFF111111),
    accent = Color(0xFFE8862B),
    onAccent = Color(0xFF111111),
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
fun hueColour(index: Int): Color {
    val hue = LabelHues.getOrElse(index) { LabelHues.last() }
    return if (Lumen.colours.isDark) hue.onDark else hue.onLight
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
        primary = c.accent, onPrimary = c.onAccent, secondary = c.silver, onSecondary = c.base,
        background = c.base, onBackground = c.text, surface = c.base, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
        surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.elevated, surfaceContainerLow = c.surface,
        outline = c.line, outlineVariant = c.line, error = c.danger, onError = c.base, scrim = c.base,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.silver, onSecondary = c.surface,
        background = c.base, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.base, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceContainerLow = c.surface,
        outline = c.line, outlineVariant = c.line, error = c.danger, onError = c.surface, scrim = c.text,
    )
}

/** Dynamic colour is never used; the two palettes are fixed so no Material purple can appear. */
@Composable
fun LumenTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colours = if (dark) DarkColours else LightColours
    CompositionLocalProvider(LocalLumenColours provides colours) {
        MaterialTheme(colorScheme = material(colours), content = content)
    }
}
