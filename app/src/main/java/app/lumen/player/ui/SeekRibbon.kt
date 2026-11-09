package app.lumen.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A decorative light ribbon, not an audio waveform. Material Slider retains touch, keys and TalkBack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    thin: Boolean,
    modifier: Modifier = Modifier,
    holdingPlace: Boolean = false,
    isPlaying: Boolean = false,
    mediaKey: String = "",
    chapterPositions: List<Long> = emptyList(),
    onSeek: (Long) -> Unit,
) {
    val c = Lumen.colours
    val duration = durationMs.coerceAtLeast(0L)
    val usable = enabled && duration > 0
    var dragging by remember(mediaKey) { mutableStateOf(false) }
    var dragValue by remember(mediaKey) { mutableFloatStateOf(0f) }
    val smoothPosition = remember(mediaKey) { Animatable(positionMs.toFloat()) }
    LaunchedEffect(positionMs, duration, isPlaying, mediaKey) {
        val target = positionMs.coerceIn(0L, duration).toFloat()
        if (isPlaying && target >= smoothPosition.value && abs(target - smoothPosition.value) < 2_000f) {
            smoothPosition.animateTo(target, tween(480, easing = LinearEasing))
        } else smoothPosition.snapTo(target)
    }
    LaunchedEffect(mediaKey, duration, enabled) { dragging = false }
    val shown = (if (dragging) dragValue else smoothPosition.value).coerceIn(0f, duration.toFloat())
    val interaction = remember(mediaKey) { MutableInteractionSource() }
    val pressed by interaction.collectIsDraggedAsState()
    val held = usable && holdingPlace
    val head by animateColorAsState(if (held) c.ember else c.accentInk, tween(180), label = "seekHead")
    val radius by animateFloatAsState(if (pressed) 9f else if (held) 7f else 5f, tween(160), label = "seekSize")
    val energy by animateFloatAsState(if (isPlaying && usable && !dragging) 1f else 0f, tween(260), label = "seekEnergy")
    // Removing the transition when paused avoids a perpetual idle frame loop. Compose honours animator scale.
    val phase = if (isPlaying && usable) {
        val transition = rememberInfiniteTransition(label = "lightRibbon")
        val value by transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "ribbonPhase")
        value
    } else 0f

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                if (dragging) {
                    onSeek(dragValue.toLong().coerceIn(0L, duration))
                    dragging = false
                }
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = usable,
            interactionSource = interaction,
            thumb = {
                Canvas(Modifier.size(28.dp)) {
                    val tint = if (usable) head else c.line
                    drawCircle(tint.copy(alpha = if (pressed) 0.18f else 0.08f), radius = (radius + 5).dp.toPx())
                    drawCircle(c.base, radius = (radius + 2).dp.toPx())
                    drawCircle(tint, radius = radius.dp.toPx())
                    drawCircle(c.text.copy(alpha = 0.9f), radius = 1.8.dp.toPx())
                }
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    val fraction = (state.value / duration.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val end = size.width * fraction
                    val mid = size.height / 2
                    drawLine(c.line, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(), StrokeCap.Round)
                    chapterPositions.filter { it > 0 && it < duration }.forEach { mark ->
                        val x = size.width * (mark.toFloat() / duration)
                        drawLine(c.text3.copy(alpha = 0.7f), Offset(x, mid - 4.dp.toPx()), Offset(x, mid + 4.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                    if (end > 0f && usable) {
                        val path = Path()
                        val amplitude = (if (thin) 4.5.dp else 1.5.dp).toPx() * energy
                        val wavelength = 42.dp.toPx()
                        var x = 0f
                        while (x < end) {
                            val envelope = sin(PI * x / end).toFloat()
                            val wave = sin(x / wavelength * 2 * PI - phase).toFloat()
                            val detail = sin(x / wavelength * 3 * PI + phase).toFloat() * 0.22f
                            val y = mid + (wave + detail) * amplitude * envelope
                            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                            x += 2.dp.toPx()
                        }
                        path.lineTo(end, mid)
                        drawPath(path, head.copy(alpha = 0.09f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                        drawPath(path, Brush.horizontalGradient(listOf(head.copy(alpha = 0.65f), head, c.text)), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(TouchTarget).semantics { contentDescription = "Playback position" },
        )
        Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown.toLong()), style = if (dragging || !thin) Lumen.type.timeL else Lumen.type.timeS, color = if (dragging) c.accentInk else c.text)
            Text(if (dragging) "SEEKING" else if (duration > 0) formatTime(duration) else "--:--", style = Lumen.type.timeS, color = c.text3)
            Text(if (duration > 0) "-" + formatTime((duration - shown.toLong()).coerceAtLeast(0L)) else "--:--", style = Lumen.type.timeS, color = c.text2)
        }
    }
}
