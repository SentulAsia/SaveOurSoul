package org.saveoursoul.app

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.saveoursoul.core.FrameCodec
import org.saveoursoul.core.FrameFlags
import org.saveoursoul.core.Outbox
import org.saveoursoul.core.PanicFrame
import org.saveoursoul.core.RelayPolicy
import org.saveoursoul.core.SeenCache
import org.saveoursoul.core.SosKind
import java.util.UUID

/**
 * Orchestrates an SOS across every channel at once:
 *
 *  1. The panic frame is written to a durable outbox before anything else.
 *  2. Internet (Wi-Fi, cellular, forced cellular) and the Bluetooth/Wi-Fi mesh fire immediately.
 *  3. If the backend has not confirmed within the SMS delay, the frame goes to the SMS gateway.
 *  4. While the SOS is active, fresh location frames go out every minute and everything still
 *     unconfirmed is retried — until a responder resolves the alert or the user cancels.
 *
 * The same engine relays other people's frames picked up over the mesh.
 */
class SosEngine(
    private val context: Context,
    val settings: Settings,
    val credentials: DeviceCredentials,
    private val backend: BackendClient,
    private val internet: InternetChannel,
    private val sms: SmsChannel,
    private val location: LocationProvider,
    private val outbox: Outbox,
    private val seen: SeenCache,
) {
    enum class Health { IDLE, WORKING, OK, FAILED }

    data class ChannelState(val health: Health = Health.IDLE, val detail: String = "")

    data class State(
        val activeAlertId: UUID? = null,
        val kind: SosKind = SosKind.GENERAL,
        val activeSince: Long = 0,
        val internet: ChannelState = ChannelState(),
        val sms: ChannelState = ChannelState(),
        val mesh: ChannelState = ChannelState(),
        /** Backend alert status: open / acknowledged / resolved / cancelled. */
        val responderStatus: String? = null,
        val lastFix: Location? = null,
        val relayedForOthers: Int = 0,
        val pendingFrames: Int = 0,
        val error: String? = null,
    ) {
        val isActive get() = activeAlertId != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relayPolicy = RelayPolicy(seen)
    private val flushLock = Mutex()
    private var activeLoop: Job? = null
    private var lastSmsAt = 0L

    val mesh = MeshTransport(context, ::onPeerPayload) { outbox.unconfirmed().map { it.frame } }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        // Retry the moment any network comes up.
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { flush() }
                }
            },
        )
        scope.launch {
            mesh.peers.collect { n -> _state.update { it.copy(mesh = meshState(n)) } }
        }
        // Resume an SOS that was active when the process died.
        settings.activeAlertId?.let { id ->
            _state.update { it.copy(activeAlertId = id, kind = settings.activeKind, activeSince = settings.activeSince) }
            startActiveLoop(id)
        }
        refreshPending()
    }

    private fun now() = System.currentTimeMillis() / 1000

    // --- user actions -------------------------------------------------------------------------

    fun trigger(kind: SosKind, test: Boolean = false) {
        if (settings.activeAlertId != null) return
        if (!credentials.isEnrolled) {
            _state.update { it.copy(error = "Device not registered with the SOS server yet — sending to contacts by SMS only.") }
        }
        val alertId = UUID.randomUUID()
        settings.activeAlertId = alertId
        settings.activeKind = kind
        settings.activeSince = now()
        _state.update {
            it.copy(activeAlertId = alertId, kind = kind, activeSince = settings.activeSince, responderStatus = null,
                internet = ChannelState(Health.WORKING, "sending…"), sms = ChannelState(), error = null)
        }
        updateMeshRunning()
        scope.launch {
            // First frame goes out with whatever location the OS already has — never wait for GPS.
            val first = emitFrame(alertId, kind, location.quickFix(), cancel = false, test = test)
            notifyContacts(first, alertId, kind, cancel = false)
            startActiveLoop(alertId)
        }
    }

    fun cancel() {
        val alertId = settings.activeAlertId ?: return
        val kind = settings.activeKind
        settings.activeAlertId = null
        activeLoop?.cancel()
        _state.update { it.copy(activeAlertId = null, responderStatus = "cancelled") }
        scope.launch {
            val frame = emitFrame(alertId, kind, null, cancel = true)
            notifyContacts(frame, alertId, kind, cancel = true)
            updateMeshRunning()
        }
    }

    // --- frames -------------------------------------------------------------------------------

    private suspend fun emitFrame(alertId: UUID, kind: SosKind, fix: Location?, cancel: Boolean, test: Boolean = false): PanicFrame? {
        val deviceId = credentials.deviceId ?: return null
        var flags = 0
        if (fix != null) flags = flags or FrameFlags.HAS_LOCATION
        if (cancel) flags = flags or FrameFlags.CANCEL
        if (test) flags = flags or FrameFlags.TEST
        val frame = PanicFrame(
            flags = flags,
            kind = kind.ordinal,
            frameId = UUID.randomUUID(),
            alertId = alertId,
            deviceId = deviceId,
            timestamp = now(),
            latE7 = fix?.let { (it.latitude * 1e7).toInt() } ?: 0,
            lonE7 = fix?.let { (it.longitude * 1e7).toInt() } ?: 0,
            accuracyM = fix?.takeIf { it.hasAccuracy() }?.accuracy?.toInt() ?: PanicFrame.UNKNOWN_ACCURACY,
            batteryPct = location.batteryPct() ?: PanicFrame.UNKNOWN_BATTERY,
        )
        val bytes = FrameCodec.encode(frame, credentials.signer)
        seen.firstSeen(frame.frameId) // ignore our own frame when peers echo it back
        outbox.add(bytes, own = true)
        if (fix != null) _state.update { it.copy(lastFix = fix) }
        mesh.broadcast(listOf(bytes))
        refreshPending()
        scope.launch { flush() }
        return frame
    }

    private suspend fun notifyContacts(frame: PanicFrame?, alertId: UUID, kind: SosKind, cancel: Boolean) {
        val contacts = settings.emergencyContacts
        if (contacts.isEmpty() || !sms.canSend()) return
        // Without registration we cannot sign a frame, but contacts must still be told.
        val f = frame ?: PanicFrame(flags = if (cancel) FrameFlags.CANCEL else 0, kind = kind.ordinal, frameId = alertId,
            alertId = alertId, deviceId = alertId, timestamp = now())
        val withFix = _state.value.lastFix?.takeIf { !f.isCancel && !f.hasLocation }?.let {
            f.copy(flags = f.flags or FrameFlags.HAS_LOCATION, latE7 = (it.latitude * 1e7).toInt(), lonE7 = (it.longitude * 1e7).toInt())
        } ?: f
        val sent = sms.notifyContacts(contacts, withFix, settings.userName.ifBlank { "a SaveOurSoul user" })
        _state.update { it.copy(sms = ChannelState(if (sent > 0) Health.OK else Health.FAILED, "$sent/${contacts.size} contacts texted")) }
    }

    private fun startActiveLoop(alertId: UUID) {
        activeLoop?.cancel()
        activeLoop = scope.launch {
            var lastLocationFrame = 0L
            var tick = 0
            while (isActive && settings.activeAlertId == alertId) {
                if (now() - lastLocationFrame >= LOCATION_INTERVAL_SEC) {
                    lastLocationFrame = now()
                    emitFrame(alertId, settings.activeKind, location.bestFix(), cancel = false)
                }
                flush()
                if (tick++ % 2 == 0) pollResponder(alertId)
                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollResponder(alertId: UUID) {
        if (!internet.hasAnyInternet()) return
        val status = backend.alertStatus(alertId) ?: return
        _state.update { it.copy(responderStatus = status.status) }
        if (status.status == "resolved" && settings.activeAlertId == alertId) {
            settings.activeAlertId = null
            activeLoop?.cancel()
            _state.update { it.copy(activeAlertId = null) }
            updateMeshRunning()
        }
    }

    // --- delivery -----------------------------------------------------------------------------

    /** Uploads everything unconfirmed; falls back to SMS for own frames. Safe to call from anywhere, any time. */
    suspend fun flush() = flushLock.withLock {
        outbox.prune()
        val pending = outbox.unconfirmed()
        if (pending.isNotEmpty()) {
            val (own, relayed) = pending.partition { it.own }
            if (own.isNotEmpty()) upload(own, "internet", null)
            if (relayed.isNotEmpty()) upload(relayed, "mesh", credentials.deviceId?.toString())
            smsFallback()
        }
        refreshPending()
    }

    private suspend fun upload(entries: List<Outbox.Entry>, channel: String, relayId: String?) {
        val ownFrames = channel == "internet"
        if (ownFrames) _state.update { it.copy(internet = ChannelState(Health.WORKING, "sending…")) }
        val delivery = internet.upload(entries.map { it.frame }, channel, relayId)
        if (delivery == null) {
            if (ownFrames) _state.update { it.copy(internet = ChannelState(Health.FAILED, "no internet — retrying")) }
            return
        }
        val done = entries.zip(delivery.results).filter { (_, r) -> r.terminal }.map { (e, _) -> e.frameId }
        outbox.markConfirmed(done)
        if (relayId != null) return
        val rejected = delivery.results.firstOrNull { it.status == "rejected" }
        val alertStatus = delivery.results.lastOrNull { it.alertStatus != null }?.alertStatus
        _state.update {
            it.copy(
                internet = if (rejected == null) ChannelState(Health.OK, "delivered via ${delivery.via}")
                else ChannelState(Health.FAILED, "server rejected: ${rejected.reason}"),
                responderStatus = alertStatus ?: it.responderStatus,
                error = if (rejected != null) "Server rejected the signal (${rejected.reason}). Re-register this device." else it.error,
            )
        }
    }

    private suspend fun smsFallback() {
        val gateway = settings.smsGatewayNumber
        if (gateway.isBlank() || !sms.canSend()) return
        val now = now()
        val due = outbox.unconfirmed().filter { e ->
            !e.smsSent && (e.own || (settings.relayViaSms && !internet.hasAnyInternet())) &&
                (FrameCodec.decode(e.frame).isCancel || now - e.addedAt >= settings.smsFallbackDelaySec)
        }
        if (due.isEmpty()) return
        val cancels = due.filter { FrameCodec.decode(it.frame).isCancel }
        val rateOk = now - lastSmsAt >= SMS_MIN_INTERVAL_SEC
        // One SMS per alert per interval is plenty: the newest frame supersedes older location updates.
        val newestPerAlert = due.groupBy { FrameCodec.decode(it.frame).alertId }.values.map { g -> g.maxBy { it.addedAt } }
        val toSend = (cancels + if (rateOk) newestPerAlert else emptyList()).distinctBy { it.frameId }
        if (toSend.isEmpty()) return
        _state.update { it.copy(sms = ChannelState(Health.WORKING, "texting SMS gateway…")) }
        val ok = toSend.count { sms.sendFrame(gateway, it.frame) }
        if (ok > 0) {
            lastSmsAt = now
            val sentAlerts = toSend.map { FrameCodec.decode(it.frame).alertId }.toSet()
            outbox.markSmsSent(due.filter { FrameCodec.decode(it.frame).alertId in sentAlerts }.map { it.frameId })
        }
        _state.update {
            it.copy(sms = if (ok > 0) ChannelState(Health.OK, "sent to SMS gateway") else ChannelState(Health.FAILED, "SMS failed — no signal?"))
        }
    }

    // --- relay --------------------------------------------------------------------------------

    private fun onPeerPayload(endpointId: String, payload: ByteArray) {
        val fresh = FrameCodec.split(payload).mapNotNull { raw ->
            when (val d = relayPolicy.onFrameFromPeer(raw)) {
                is RelayPolicy.Decision.Relay -> d.frame.takeIf { outbox.add(it, own = false) }
                is RelayPolicy.Decision.Drop -> null
            }
        }
        if (fresh.isEmpty()) return
        Log.i(TAG, "relaying ${fresh.size} frame(s) from $endpointId")
        _state.update { it.copy(relayedForOthers = it.relayedForOthers + fresh.size) }
        mesh.broadcast(fresh, exceptEndpoint = endpointId)
        refreshPending()
        scope.launch { flush() }
    }

    fun updateMeshRunning() {
        val want = settings.activeAlertId != null || settings.relayEnabled
        try {
            if (want) mesh.start() else mesh.stop()
        } catch (e: SecurityException) {
            _state.update { it.copy(mesh = ChannelState(Health.FAILED, "Bluetooth/Wi-Fi permission missing")) }
        }
        _state.update { it.copy(mesh = meshState(mesh.peers.value)) }
    }

    private fun meshState(peers: Int) = when {
        !mesh.running -> ChannelState(Health.IDLE, "off")
        peers > 0 -> ChannelState(Health.OK, "$peers phone(s) nearby")
        else -> ChannelState(Health.WORKING, "searching for nearby phones…")
    }

    private fun refreshPending() = _state.update { it.copy(pendingFrames = outbox.unconfirmed().size) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val TAG = "SosEngine"
        const val LOOP_INTERVAL_MS = 10_000L
        const val LOCATION_INTERVAL_SEC = 60L
        const val SMS_MIN_INTERVAL_SEC = 120L
    }
}
