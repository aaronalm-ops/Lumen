package app.lumen.player.data.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the phone can reach the internet, and whether the connection is unmetered (Wi-Fi in
 * practice). Read on demand before a download, and observed while the app is in the foreground
 * so the Library can grey Drive rows that are out of reach.
 */
class Connectivity(private val context: Context) {

    private val manager: ConnectivityManager? get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _online = MutableStateFlow(isOnline())
    val online: StateFlow<Boolean> = _online

    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun capabilities(): NetworkCapabilities? {
        val cm = manager ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    fun isOnline(): Boolean {
        val caps = capabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isUnmetered(): Boolean {
        val caps = capabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts following the default network; safe to call twice. */
    fun start() {
        if (callback != null) return
        val cm = manager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = isOnline()
            }

            override fun onLost(network: Network) {
                _online.value = isOnline()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _online.value = isOnline()
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
            callback = cb
        } catch (t: Throwable) {
            RingLog.w("net", "network callback failed: $t")
        }
        _online.value = isOnline()
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
            RingLog.w("net", "network callback unregister failed: $t")
        }
    }
}
