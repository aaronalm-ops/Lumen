package app.lumen.player.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** Only unconsumed downward travel counts, so lists scroll back to their top before dismissal. */
@Composable
internal fun Modifier.dismissPlayerOnPull(enabled: Boolean, onDismiss: () -> Unit): Modifier {
    if (!enabled) return this
    val currentDismiss = rememberUpdatedState(onDismiss)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y > 0) pulled += available.y
                    else if (consumed.y < 0 || available.y < 0) pulled = 0f
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val close = pulled >= threshold
                pulled = 0f
                if (close) currentDismiss.value()
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection).scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
}
