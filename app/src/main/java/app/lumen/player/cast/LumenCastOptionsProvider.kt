package app.lumen.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * 0.5.0: the Cast framework's options, named in the manifest
 * (`com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`). Media3 creates the
 * CastContext from this provider the first time RemoteCastPlayer or the MediaRouteButton needs it
 * (`Cast.ensureInitialized`, which calls `CastContext.getSharedInstance(context, executor)`).
 *
 * The Default Media Receiver plays plain http(s) audio and video, which is all Lumen sends: the
 * phone serves the file itself over the local network ([CastMediaServer]). A saved session is not
 * resumed on the next launch, and ending the session stops the receiver. Remote to local transfer
 * from the system output switcher is off: ending a cast always lands on the phone paused, at the
 * cast position.
 */
class LumenCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setRemoteToLocalEnabled(false)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()
}
