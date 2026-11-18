package org.saveoursoul.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Uploads frames over whatever IP path works: the default network first, then every other
 * network with internet (e.g. cellular while Wi-Fi is up), and finally by explicitly asking
 * Android to bring up cellular data — this rescues the common "connected to Wi-Fi that has no
 * internet / captive portal" case.
 */
class InternetChannel(context: Context, private val backend: BackendClient) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    data class Delivery(val via: String, val results: List<BackendClient.FrameResult>)

    fun hasAnyInternet(): Boolean = candidates().isNotEmpty()

    suspend fun upload(frames: List<ByteArray>, channel: String, relayDeviceId: String?): Delivery? {
        for (n in candidates()) {
            try {
                return Delivery(label(n), backend.postSignals(frames, channel, relayDeviceId, n))
            } catch (e: Exception) {
                Log.w(TAG, "upload via ${label(n)} failed: ${e.message}")
            }
        }
        val cellular = requestCellular() ?: return null
        return try {
            Delivery("cellular (forced)", backend.postSignals(frames, channel, relayDeviceId, cellular.first))
        } catch (e: Exception) {
            Log.w(TAG, "upload via forced cellular failed: ${e.message}")
            null
        } finally {
            cm.unregisterNetworkCallback(cellular.second)
        }
    }

    /** The default network first, then any other network that claims internet access. */
    @Suppress("DEPRECATION") // allNetworks is the only way to enumerate non-default networks without callbacks
    private fun candidates(): List<Network> {
        val all = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        val active = cm.activeNetwork
        return all.sortedBy { if (it == active) 0 else 1 }
    }

    private suspend fun requestCellular(): Pair<Network, ConnectivityManager.NetworkCallback>? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        val network = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Network?> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: SecurityException) {
                    Log.w(TAG, "cannot request cellular: ${e.message}")
                    callback = null
                    cont.resume(null)
                }
            }
        }
        val cb = callback ?: return null
        if (network == null) {
            cm.unregisterNetworkCallback(cb)
            return null
        }
        return network to cb
    }

    private fun label(n: Network): String {
        val caps = cm.getNetworkCapabilities(n) ?: return "network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> "satellite"
            else -> "network"
        }
    }

    private companion object {
        const val TAG = "SosInternet"
    }
}
