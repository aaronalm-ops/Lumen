package app.lumen.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Observable UI state. Owned by MainActivity, updated from the MediaController. */
@Stable
class PlayerUiState {
    var hasItem by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var controllerConnected by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var lastSaved by mutableStateOf("nothing saved yet")
    var updateTag by mutableStateOf<String?>(null)
    var updateUrl by mutableStateOf<String?>(null)
}

class PlayerActions(
    val pickFile: () -> Unit,
    val playPause: () -> Unit,
    val seekBack: () -> Unit,
    val seekForward: () -> Unit,
    val seekTo: (Long) -> Unit,
    val shareDiagnostics: () -> Unit,
    val openUpdate: () -> Unit,
)

@Composable
fun PlayerScreen(state: PlayerUiState, actions: PlayerActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (state.updateTag != null) {
            UpdateBanner(tag = state.updateTag.orEmpty(), onUpdate = actions.openUpdate)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Button(
                onClick = actions.pickFile,
                colors = ButtonDefaults.buttonColors(containerColor = Hairline, contentColor = White),
            ) {
                Text("Pick a file", style = ButtonLabelStyle)
            }
        }

        HorizontalDivider(color = Hairline, thickness = 1.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (state.hasItem) state.title else "No file loaded",
                style = TitleStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.hasItem) state.artist else "Pick an audio or video file to begin",
                style = ArtistStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(32.dp))

            Scrubber(state = state, onSeekTo = actions.seekTo)

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(label = "10", forward = false, enabled = state.hasItem, description = "Back 10 seconds", onClick = actions.seekBack)
                // Enabled without an item too: play on an empty player triggers playback
                // resumption of the last stored item through the session.
                PlayPauseButton(isPlaying = state.isPlaying, enabled = state.hasItem || state.controllerConnected, onClick = actions.playPause)
                SkipButton(label = "30", forward = true, enabled = state.hasItem, description = "Forward 30 seconds", onClick = actions.seekForward)
            }
        }

        HorizontalDivider(color = Hairline, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Service running: ${if (state.serviceRunning) "yes" else "no"} · " +
                    "controller: ${if (state.controllerConnected) "connected" else "connecting"} · " +
                    "last saved: ${state.lastSaved}",
                style = StatusStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = actions.shareDiagnostics, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text("Share diagnostics", style = StatusStyle.copy(color = Amber, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun UpdateBanner(tag: String, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hairline)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Update $tag available",
            style = StatusStyle.copy(color = White),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Update",
            style = StatusStyle.copy(color = Amber),
            modifier = Modifier
                .clickable(onClick = onUpdate)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Scrubber(state: PlayerUiState, onSeekTo: (Long) -> Unit) {
    // While the thumb is being dragged the slider shows the drag position, not the player's.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val shown = if (dragging) dragValue else state.positionMs.toFloat()
    val range = 0f..(if (duration > 0) duration.toFloat() else 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(range),
            onValueChange = { v ->
                dragging = true
                dragValue = v
            },
            onValueChangeFinished = {
                dragging = false
                onSeekTo(dragValue.toLong())
            },
            valueRange = range,
            enabled = state.hasItem && duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Hairline,
                disabledThumbColor = Hairline,
                disabledActiveTrackColor = Hairline,
                disabledInactiveTrackColor = Hairline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(shown.toLong()), style = TimecodeStyle)
            Text(
                if (duration > 0) "-" + formatTime((duration - shown.toLong()).coerceAtLeast(0L)) else "--:--",
                style = TimecodeStyle,
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = if (isPlaying) "Pause" else "Play"
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(if (enabled) Amber else Hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val w = size.width
            val h = size.height
            if (isPlaying) {
                val barWidth = w * 0.3f
                drawRect(color = Black, topLeft = Offset(w * 0.1f, 0f), size = Size(barWidth, h))
                drawRect(color = Black, topLeft = Offset(w * 0.6f, 0f), size = Size(barWidth, h))
            } else {
                val path = Path().apply {
                    moveTo(w * 0.15f, 0f)
                    lineTo(w, h / 2f)
                    lineTo(w * 0.15f, h)
                    close()
                }
                drawPath(path = path, color = Black)
            }
        }
    }
}

/** A circular arrow with the number of seconds in the middle. Drawn, so no icon library. */
@Composable
private fun SkipButton(label: String, forward: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val tint = if (enabled) White else Hairline
    Box(
        modifier = Modifier
            .size(64.dp)
            .border(1.dp, Hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val strokeWidth = 2.dp.toPx()
            val inset = strokeWidth
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            // Gap at the top, sweep direction depends on the button.
            val sweep = 300f
            val start = if (forward) 300f else 240f
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = if (forward) sweep else -sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            // Arrow head at the end of the arc, pointing along the sweep direction.
            val endAngle = if (forward) start + sweep else start - sweep
            val rad = Math.toRadians(endAngle.toDouble())
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = arcSize.width / 2f
            val tip = Offset(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
            val headLen = 6.dp.toPx()
            val dir = if (forward) 1f else -1f
            // Tangent direction at the arc end.
            val tx = -sin(rad).toFloat() * dir
            val ty = cos(rad).toFloat() * dir
            val head = Path().apply {
                moveTo(tip.x + tx * headLen, tip.y + ty * headLen)
                lineTo(tip.x - ty * headLen * 0.8f, tip.y + tx * headLen * 0.8f)
                lineTo(tip.x + ty * headLen * 0.8f, tip.y - tx * headLen * 0.8f)
                close()
            }
            drawPath(head, color = tint)
        }
        Text(text = label, style = TimecodeStyle.copy(color = tint, fontSize = 13.sp))
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.UK, "%02d:%02d", m, s)
}
