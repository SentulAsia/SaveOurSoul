package org.saveoursoul.app

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.saveoursoul.core.FrameCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-to-phone mesh over Google Nearby Connections. With the P2P_CLUSTER strategy Nearby
 * picks and upgrades the medium on its own: Bluetooth Classic, BLE, Wi-Fi hotspot,
 * Wi-Fi Direct / Wi-Fi Aware, or the shared LAN. No internet or pairing needed.
 *
 * Every phone running SaveOurSoul both advertises and discovers. When two meet they connect and
 * swap every frame they still have to deliver; each frame then moves on through the crowd
 * (store-and-forward) until some phone reaches the internet or SMS.
 */
class MeshTransport(
    context: Context,
    /** Called with a raw payload (concatenated frames) received from a peer. */
    private val onPayload: (endpointId: String, payload: ByteArray) -> Unit,
    /** Frames to hand to a newly connected peer. */
    private val framesToShare: () -> List<ByteArray>,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val localName = "sos-" + UUID.randomUUID().toString().take(8)
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val _peers = MutableStateFlow(0)
    val peers: StateFlow<Int> = _peers

    @Volatile
    var running = false
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { onPayload(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Accept everyone: frames are signed end-to-end, so a malicious peer can only drop or
            // replay them (the backend de-duplicates), never forge or alter them.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pending.remove(endpointId)
            if (resolution.status.isSuccess) {
                connected.add(endpointId)
                _peers.value = connected.size
                send(endpointId, framesToShare())
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            _peers.value = connected.size
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || endpointId in connected || !pending.add(endpointId)) return
            // Both sides discover each other; only the lexicographically smaller name dials, to avoid duplicate links.
            if (localName > info.endpointName) {
                pending.remove(endpointId)
                return
            }
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener { e ->
                    pending.remove(endpointId)
                    Log.d(TAG, "connect to $endpointId failed: ${e.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            pending.remove(endpointId)
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> Log.w(TAG, "advertising failed: ${e.message}") }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> Log.w(TAG, "discovery failed: ${e.message}") }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        connected.clear()
        pending.clear()
        _peers.value = 0
    }

    /** Sends frames to every connected peer except [exceptEndpoint] (the one we got them from). */
    fun broadcast(frames: List<ByteArray>, exceptEndpoint: String? = null) {
        if (frames.isEmpty()) return
        connected.filter { it != exceptEndpoint }.forEach { send(it, frames) }
    }

    private fun send(endpointId: String, frames: List<ByteArray>) {
        frames.chunked(MAX_FRAMES_PER_PAYLOAD).forEach { chunk ->
            client.sendPayload(endpointId, Payload.fromBytes(FrameCodec.join(chunk)))
                .addOnFailureListener { e -> Log.d(TAG, "send to $endpointId failed: ${e.message}") }
        }
    }

    companion object {
        const val SERVICE_ID = "org.saveoursoul.sos.v1"
        private const val TAG = "SosMesh"
        /** BYTES payloads are capped at 32 KiB by Nearby. */
        private const val MAX_FRAMES_PER_PAYLOAD = ConnectionsClient.MAX_BYTES_DATA_SIZE / FrameCodec.FRAME_LENGTH
    }
}
