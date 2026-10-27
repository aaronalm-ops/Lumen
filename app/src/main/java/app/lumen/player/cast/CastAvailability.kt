package app.lumen.player.cast

import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.common.util.BackgroundExecutor
import androidx.media3.common.util.UnstableApi
import app.lumen.player.diag.RingLog
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.5.0: whether a Cast device is on the network, for the cast button on Now Playing (shown only
 * then, or while casting). Reads the Cast framework's CastState: NO_DEVICES_AVAILABLE hides the
 * button; NOT_CONNECTED, CONNECTING and CONNECTED show it. The framework runs discovery itself while
 * the app is in the foreground. The CastContext is the one Media3 uses, created from
 * [LumenCastOptionsProvider] in the manifest. A phone without the Cast framework (no Google Play
 * services) never shows the button.
 */
@UnstableApi
object CastAvailability {

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    private var started = false

    private val listener = CastStateListener { state ->
        _available.value = state != CastState.NO_DEVICES_AVAILABLE
    }

    @MainThread
    fun start(context: Context) {
        if (started) return
        started = true
        try {
            CastContext.getSharedInstance(context.applicationContext, BackgroundExecutor.get())
                .addOnSuccessListener { castContext ->
                    castContext.addCastStateListener(listener)
                    _available.value = castContext.castState != CastState.NO_DEVICES_AVAILABLE
                    RingLog.i("cast", "cast state ${castContext.castState}")
                }
                .addOnFailureListener { e ->
                    RingLog.w("cast", "Cast framework unavailable: $e")
                }
        } catch (t: Throwable) {
            // No Google Play services, or the options provider could not be loaded.
            RingLog.w("cast", "Cast framework unavailable: $t")
        }
    }
}
