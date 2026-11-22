package app.lumen.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.10.1: tap to skip. Double-tap the right of the cover (or a sermon's title, or a video) to go
 * forward by the tap step, the left to go back; keep tapping the same side and each tap adds
 * another step. The total and where it lands show on that side and on the seeker; one seek is
 * made when the tapping stops, so a Drive stream buffers once. One tap alone does what it did
 * before (nothing on a cover, full screen on a video, the controls in full screen).
 */
object TapSkip {
    /** Taps on the same side this close together keep a skip going. */
    const val STREAK_WINDOW_MS = 650L

    /** Two taps this close together start a skip (the platform's double-tap timeout is similar). */
    const val DOUBLE_TAP_MS = 300L

    /** A forward skip stops this far before the end, so it never finishes the item by accident. */
    const val END_GUARD_MS = 1_000L

    val stepChoices = listOf(5, 10, 15, 30)

    /**
     * Where a skip of [steps] steps of [stepSeconds] lands from [positionMs], kept inside the item,
     * and inside [loop] (start to end) while an A to B loop runs, so tapping never breaks the loop.
     */
    fun target(positionMs: Long, durationMs: Long, side: Int, steps: Int, stepSeconds: Int, loop: LongRange? = null): Long {
        val moved = positionMs + side.toLong() * steps * stepSeconds * 1000L
        val last = if (durationMs > 0) (durationMs - END_GUARD_MS).coerceAtLeast(0L) else Long.MAX_VALUE
        val kept = moved.coerceIn(0L, last)
        return if (loop != null && !loop.isEmpty()) kept.coerceIn(loop.first, loop.last) else kept
    }

    /** "+30 s", "-1 min 10 s". */
    fun amount(side: Int, steps: Int, stepSeconds: Int): String {
        val total = steps * stepSeconds
        val words = if (total >= 60) "${total / 60} min" + (if (total % 60 > 0) " ${total % 60} s" else "") else "$total s"
        return (if (side < 0) "-" else "+") + words
    }

    /** Which half of a zone [x] of [width] falls in: -1 back (left), 1 forward (right). */
    fun sideOf(x: Float, width: Int): Int = if (x < width / 2f) -1 else 1
}

/** The skip being tapped right now, shared by the zone and the seeker on the same screen. */
class TapSkipState {
    var side by mutableIntStateOf(0)
    var steps by mutableIntStateOf(0)
    var target by mutableStateOf<Long?>(null)
}

/** The seeker reads this to draw where a running skip will land. */
val LocalTapSkip = compositionLocalOf<TapSkipState?> { null }

@Composable
fun ProvideTapSkip(content: @Composable () -> Unit) {
    val state = remember { TapSkipState() }
    CompositionLocalProvider(LocalTapSkip provides state, content = content)
}

/**
 * Wraps [content] (a cover, a title, a video) with tap to skip. [onSingleTap] is what one tap does
 * there, null for nothing. [onFirstUse] is told once a skip has been made, to retire the hint.
 */
@Composable
fun TapSkipZone(
    enabled: Boolean,
    stepSeconds: Int,
    /** Read at each tap, so a playing item's skip counts from where it is now. */
    position: () -> Long,
    duration: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 0.10.1: a running A to B loop (start to end); skips stay inside it. */
    loop: LongRange? = null,
    onSingleTap: (() -> Unit)? = null,
    onFirstUse: () -> Unit = {},
    overlayOnDark: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shared = LocalTapSkip.current
    val state = shared ?: remember { TapSkipState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val slop = LocalViewConfiguration.current.touchSlop
    val readPosition by rememberUpdatedState(position)
    val readDuration by rememberUpdatedState(duration)
    val step by rememberUpdatedState(stepSeconds)
    val single by rememberUpdatedState(onSingleTap)
    val seek by rememberUpdatedState(onSeek)
    val firstUse by rememberUpdatedState(onFirstUse)
    val bounds by rememberUpdatedState(loop)
    val jobs = remember { arrayOfNulls<Job>(2) } // 0: waiting single tap, 1: commit
    val lastTap = remember { longArrayOf(0L, 0L) } // time, side

    fun commitLater() {
        jobs[1]?.cancel()
        jobs[1] = scope.launch {
            delay(TapSkip.STREAK_WINDOW_MS)
            state.target?.let { seek(it) }
            state.side = 0
            state.steps = 0
            state.target = null
            firstUse()
        }
    }

    fun addStep(side: Int) {
        if (state.side != side) {
            // A tap on the other side ends the running skip there and starts a new one.
            if (state.side != 0) state.target?.let { seek(it) }
            state.side = side
            state.steps = 0
        }
        state.steps++
        state.target = TapSkip.target(readPosition(), readDuration(), side, state.steps, step, bounds)
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        commitLater()
    }

    fun onTap(x: Float, width: Int, now: Long) {
        val side = TapSkip.sideOf(x, width)
        val running = state.side == side && now - lastTap[0] <= TapSkip.STREAK_WINDOW_MS
        val second = jobs[0]?.isActive == true && lastTap[1].toInt() == side && now - lastTap[0] <= TapSkip.DOUBLE_TAP_MS
        lastTap[0] = now
        lastTap[1] = side.toLong()
        when {
            running -> addStep(side)
            second -> {
                jobs[0]?.cancel()
                addStep(side)
            }
            else -> {
                jobs[0]?.cancel()
                jobs[0] = scope.launch {
                    delay(TapSkip.DOUBLE_TAP_MS)
                    single?.invoke()
                }
            }
        }
    }

    Box(
        modifier
            .semantics {
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Back $stepSeconds seconds") { seek(TapSkip.target(readPosition(), readDuration(), -1, 1, step, bounds)); true },
                    CustomAccessibilityAction("Forward $stepSeconds seconds") { seek(TapSkip.target(readPosition(), readDuration(), 1, 1, step, bounds)); true },
                )
            }
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var multi = false
                    var upAt: androidx.compose.ui.geometry.Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) multi = true
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > slop) moved = true
                        if (!change.pressed) {
                            upAt = change.position
                            break
                        }
                    }
                    val at = upAt
                    if (at != null && !moved && !multi) {
                        val now = System.currentTimeMillis()
                        if (enabled) onTap(at.x, size.width, now) else single?.invoke()
                    }
                }
            },
    ) {
        content()
        TapSkipBadge(state, stepSeconds, overlayOnDark)
    }
}

/** The running total on the side being tapped, with where it lands. */
@Composable
private fun BoxScope.TapSkipBadge(state: TapSkipState, stepSeconds: Int, onDark: Boolean) {
    val c = Lumen.colours
    val side = state.side
    AnimatedVisibility(
        visible = side != 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f),
    ) {
        val shape = if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        Box(
            Modifier.fillMaxHeight().fillMaxWidth().clip(shape).background((if (onDark) Color.White else c.text).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (state.steps > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(if (side < 0) LumenIcons.SkipBack else LumenIcons.SkipForward, contentDescription = null, tint = Color.White)
                    Text(TapSkip.amount(side, state.steps, stepSeconds), style = Lumen.type.bodyStrong, color = Color.White)
                    state.target?.let { Text("to ${formatTime(it)}", style = Lumen.type.labelS, color = Color.White.copy(alpha = 0.85f)) }
                }
            }
        }
    }
}
