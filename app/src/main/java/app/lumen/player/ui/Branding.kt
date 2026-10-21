package app.lumen.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lumen.player.R
import kotlinx.coroutines.delay

/**
 * The LUMEN lockup: the vector light trail (res/drawable/lumen_lockup.xml) and the wordmark in
 * Manrope 500 with 0.18 em tracking. The mark carries its own colours (it is the brand), the
 * wordmark takes the surface's text colour. [height] sets the mark; the type follows.
 */
@Composable
fun LumenLockup(modifier: Modifier = Modifier, height: Dp = 32.dp) {
    val c = Lumen.colours
    val wordmark = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W500,
        fontSize = (height.value * 0.62f).sp,
        letterSpacing = 0.18.em,
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.lumen_lockup),
            contentDescription = null,
            modifier = Modifier.size(width = height * 40f / 32f, height = height),
        )
        Spacer(Modifier.width(height * 0.4f))
        Text("LUMEN", style = wordmark, color = c.text, maxLines = 1)
    }
}

/**
 * The maker credit for the bottom of Settings and nowhere else: the Aaron Instinct mark at
 * 20 dp (charcoal body with the Lumen amber accent on light surfaces, off-white body on black)
 * and "by Aaron Almeida" in Inter 13 sp secondary, centred, 24 dp padding.
 */
@Composable
fun MakerCredit(modifier: Modifier = Modifier) {
    val c = Lumen.colours
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(if (c.isDark) R.drawable.aaron_instinct_lumen_reverse else R.drawable.aaron_instinct_lumen),
            contentDescription = "Aaron Instinct",
            modifier = Modifier.size(20.dp),
        )
        Text("by Aaron Almeida", style = Lumen.type.bodyS, color = c.text2)
    }
}

/**
 * The branded screen after the system splash, shown only on a cold start: black, the lockup
 * centred, held for at most 600 ms, then a 200 ms fade. Nothing else moves. [onDone] fires
 * once the fade has finished so the caller can drop it from the tree.
 */
@Composable
fun BrandedSplash(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(600)
        visible = false
        delay(220)
        onDone()
    }
    LumenTheme(dark = true) {
        val c = Lumen.colours
        AnimatedVisibility(visible = visible, exit = fadeOut(animationSpec = tween(200))) {
            Box(Modifier.fillMaxSize().background(c.base), contentAlignment = Alignment.Center) {
                LumenLockup(height = 40.dp)
            }
        }
    }
}
