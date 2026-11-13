package app.hylapocket.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Says when the phone's network changes: Wi-Fi to mobile data and back. Sockets on the network that
 * went away look open until a ping goes unanswered, half a minute later, so the app redials at once
 * instead of waiting through that. The callback runs on a binder thread; it only posts.
 */
internal class NetworkWatcher(context: Context, private val onChanged: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChanged()
            override fun onLost(network: Network) = onChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChanged()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager?.registerNetworkCallback(request, cb) }
        callback = cb
    }

    fun stop() {
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null
    }
}
