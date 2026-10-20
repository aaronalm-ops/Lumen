package app.lumen.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lumen.player.R

// Palette. Dark only in Phase 0.
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val Amber = Color(0xFFE8862B)
val Secondary = Color(0xFF8C8C8C)
val Hairline = Color(0xFF262626)

// The three bundled files are variable fonts; the weight passed here becomes the wght axis.
val Manrope = FontFamily(Font(R.font.manrope, weight = FontWeight.W500))
val Inter = FontFamily(Font(R.font.inter, weight = FontWeight.Normal))
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, weight = FontWeight.Normal))

val TitleStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 26.sp, lineHeight = 32.sp, color = White)
val ArtistStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, color = Secondary)
val TimecodeStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    color = Secondary,
    fontFeatureSettings = "tnum",
)
val StatusStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Secondary)
val ButtonLabelStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)

private val LumenColours = darkColorScheme(
    primary = Amber,
    onPrimary = Black,
    secondary = Secondary,
    onSecondary = Black,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = Black,
    onSurfaceVariant = Secondary,
    outline = Hairline,
    outlineVariant = Hairline,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumenColours, content = content)
}
