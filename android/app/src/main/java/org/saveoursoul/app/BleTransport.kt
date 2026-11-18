package org.saveoursoul.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.saveoursoul.core.Ack
import org.saveoursoul.core.BleProtocol
import org.saveoursoul.core.ContactPolicy
import org.saveoursoul.core.FrameCodec
import org.saveoursoul.core.Receipts
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the BLE layer needs from the SOS engine. */
interface BleHost {
    fun beacon(): BleProtocol.Beacon
    fun localState(): ContactPolicy.Local
    /** Frames served on OUTBOX reads (already rotated, at most 6). */
    fun framesToServe(): List<ByteArray>
    /** Frames to push to a phone that has internet (own first, at most 6). */
    fun framesToPush(): List<ByteArray>
    fun recentAcks(): List<Ack>
    fun onFramesReceived(frames: List<ByteArray>, source: String)
    fun onAcksReceived(acks: List<Ack>)
    /** Upload freshly pulled frames right away if online; returns receipts to hand back. */
    suspend fun uploadPulled(frames: List<ByteArray>): List<Ack>
}

/**
 * SOS-BLE v1 on Android (see docs/BLE.md and core/BleProtocol.kt). Every phone is at once:
 *  - an advertiser with a 5-byte beacon (own SOS / holds frames / has internet + digest);
 *  - a GATT server exposing INBOX / OUTBOX / ACKS / CONFIRMED;
 *  - a scanner that, per [ContactPolicy], connects to nearby phones to push or pull frames.
 *
 * Interoperates with the iOS app. Unlike Nearby Connections, it needs no pairing or
 * handshake beyond a short GATT connection, which keeps it working in very dense crowds.
 */
@SuppressLint("MissingPermission") // callers check permissions; SecurityException is caught in start()
class BleTransport(private val context: Context, private val host: BleHost) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val policy = ContactPolicy()
    /** Phones keep only a few GATT links; more just fail in a crowd. */
    private val sessions = Semaphore(MAX_SESSIONS)
    private val serviceParcel = ParcelUuid(BleProtocol.SERVICE_UUID)

    private var server: BluetoothGattServer? = null
    private var advertising: Pair<BleProtocol.Beacon, Boolean>? = null
    private var scanningUrgent: Boolean? = null

    @Volatile
    var running = false
        private set

    fun start(): Boolean {
        if (running) return true
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        return try {
            openServer()
            running = true
            refresh()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE permission missing: ${e.message}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { server?.close() }
        server = null
        advertising = null
        scanningUrgent = null
    }

    /** Re-advertises when the beacon changed and adjusts scan intensity. Call every few seconds. */
    fun refresh() {
        if (!running) return
        try {
            val beacon = host.beacon()
            val urgent = beacon.ownSos
            if (advertising != beacon to urgent) {
                val adv = adapter?.bluetoothLeAdvertiser ?: return
                runCatching { adv.stopAdvertising(advertiseCallback) }
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(if (urgent) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(if (urgent) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .build()
                // Main packet: flags + 128-bit service UUID (what iOS and Android scanners filter on).
                val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(serviceParcel).build()
                // Scan response: the beacon as service data.
                val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
                    .addServiceData(serviceParcel, beacon.toServiceData()).build()
                adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
                advertising = beacon to urgent
            }
            if (scanningUrgent != urgent) {
                val scanner = adapter?.bluetoothLeScanner ?: return
                runCatching { scanner.stopScan(scanCallback) }
                val filters = listOf(ScanFilter.Builder().setServiceUuid(serviceParcel).build())
                val settings = ScanSettings.Builder()
                    .setScanMode(if (urgent) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
                    .build()
                scanner.startScan(filters, settings, scanCallback)
                scanningUrgent = urgent
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE refresh failed: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            advertising = null // retry on next refresh
        }
    }

    // --- central: scan and connect ------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = consider(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::consider)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanningUrgent = null
        }
    }

    private fun consider(result: ScanResult) {
        if (sessions.availablePermits == 0) return
        val record = result.scanRecord
        val beacon = BleProtocol.Beacon.fromServiceData(record?.getServiceData(serviceParcel))
            ?: BleProtocol.Beacon.fromLocalName(record?.deviceName)
        val key = result.device.address
        val action = policy.decide(key, beacon, result.rssi, host.localState(), System.currentTimeMillis())
        if (action == ContactPolicy.Action.NONE) return
        if (!sessions.tryAcquire()) return
        scope.launch {
            try {
                withTimeoutOrNull(SESSION_TIMEOUT_MS) { runSession(result.device, action, key) }
            } catch (e: Exception) {
                Log.d(TAG, "session with $key failed: ${e.message}")
            } finally {
                sessions.release()
            }
        }
    }

    private suspend fun runSession(device: BluetoothDevice, action: ContactPolicy.Action, key: String) {
        GattSession(context, device).use { s ->
            s.connect()
            withTimeoutOrNull(3_000) { runCatching { s.requestMtu(247) } }
            s.discover()
            val push = action == ContactPolicy.Action.PUSH || action == ContactPolicy.Action.EXCHANGE
            val pull = action == ContactPolicy.Action.PULL || action == ContactPolicy.Action.EXCHANGE
            if (push) {
                val frames = host.framesToPush()
                if (frames.isNotEmpty()) s.write(BleProtocol.INBOX_UUID, FrameCodec.join(frames))
            }
            if (pull) {
                val frames = FrameCodec.split(s.read(BleProtocol.OUTBOX_UUID))
                if (frames.isNotEmpty()) {
                    host.onFramesReceived(frames, "ble:$key")
                    val acks = withTimeoutOrNull(UPLOAD_WAIT_MS) { host.uploadPulled(frames) }.orEmpty()
                    if (acks.isNotEmpty()) s.write(BleProtocol.ACKS_UUID, Receipts.encode(acks.take(BleProtocol.MAX_ACKS_PER_VALUE)))
                }
            }
            // Give an online peer a moment to upload what we pushed, then collect its receipts.
            if (push) delay(PUSH_SETTLE_MS)
            host.onAcksReceived(Receipts.decode(s.read(BleProtocol.CONFIRMED_UUID)))
        }
    }

    // --- peripheral: GATT server --------------------------------------------------------------

    private val readCache = ConcurrentHashMap<String, ByteArray>()
    private val writeBuffers = ConcurrentHashMap<String, ByteArray>()

    private fun openServer() {
        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    readCache.keys.removeAll { it.startsWith(device.address) }
                    writeBuffers.keys.removeAll { it.startsWith(device.address) }
                }
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
                val cacheKey = device.address + c.uuid
                val value = if (offset == 0 || readCache[cacheKey] == null) {
                    when (c.uuid) {
                        BleProtocol.OUTBOX_UUID -> FrameCodec.join(host.framesToServe())
                        BleProtocol.CONFIRMED_UUID -> Receipts.encode(host.recentAcks().take(BleProtocol.MAX_ACKS_PER_VALUE))
                        else -> ByteArray(0)
                    }.also { readCache[cacheKey] = it }
                } else readCache.getValue(cacheKey)
                val slice = value.copyOfRange(offset.coerceAtMost(value.size), value.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
            ) {
                val v = value ?: ByteArray(0)
                val ok = offset + v.size <= BleProtocol.MAX_VALUE
                if (ok) {
                    if (preparedWrite) {
                        val key = device.address + "|" + c.uuid
                        val buf = writeBuffers[key] ?: ByteArray(0)
                        val merged = if (buf.size < offset + v.size) buf.copyOf(offset + v.size) else buf
                        v.copyInto(merged, offset)
                        writeBuffers[key] = merged
                    } else {
                        handleWrite(c.uuid, v, device.address)
                    }
                }
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, v)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
                val prefix = device.address + "|"
                writeBuffers.keys.filter { it.startsWith(prefix) }.forEach { key ->
                    val data = writeBuffers.remove(key) ?: return@forEach
                    if (execute) handleWrite(UUID.fromString(key.removePrefix(prefix)), data, device.address)
                }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
        val s = manager?.openGattServer(context, callback) ?: throw IOException("no GATT server")
        val service = BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fun char(uuid: UUID, write: Boolean) = BluetoothGattCharacteristic(
            uuid,
            if (write) BluetoothGattCharacteristic.PROPERTY_WRITE else BluetoothGattCharacteristic.PROPERTY_READ,
            if (write) BluetoothGattCharacteristic.PERMISSION_WRITE else BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(char(BleProtocol.INBOX_UUID, write = true))
        service.addCharacteristic(char(BleProtocol.OUTBOX_UUID, write = false))
        service.addCharacteristic(char(BleProtocol.ACKS_UUID, write = true))
        service.addCharacteristic(char(BleProtocol.CONFIRMED_UUID, write = false))
        s.addService(service)
        server = s
    }

    private fun handleWrite(uuid: UUID, value: ByteArray, from: String) {
        when (uuid) {
            BleProtocol.INBOX_UUID -> FrameCodec.split(value).takeIf { it.isNotEmpty() }?.let { host.onFramesReceived(it, "ble:$from") }
            BleProtocol.ACKS_UUID -> Receipts.decode(value).takeIf { it.isNotEmpty() }?.let(host::onAcksReceived)
        }
    }

    companion object {
        private const val TAG = "SosBle"
        private const val MAX_SESSIONS = 3
        private const val SESSION_TIMEOUT_MS = 20_000L
        private const val UPLOAD_WAIT_MS = 8_000L
        private const val PUSH_SETTLE_MS = 4_000L
    }
}

/** One short GATT client connection. Android allows one outstanding GATT operation at a time. */
@SuppressLint("MissingPermission")
private class GattSession(private val context: Context, private val device: BluetoothDevice) : BluetoothGattCallback(), Closeable {
    private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Any?>? = null

    private suspend fun <T> op(start: () -> Boolean): T {
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start()) throw IOException("GATT operation rejected")
        @Suppress("UNCHECKED_CAST")
        return d.await() as T
    }

    suspend fun connect(): Unit = op {
        gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        gatt != null
    }

    suspend fun requestMtu(mtu: Int): Int = op { gatt!!.requestMtu(mtu) }
    suspend fun discover(): Unit = op { gatt!!.discoverServices() }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(BleProtocol.SERVICE_UUID)?.getCharacteristic(uuid) ?: throw IOException("peer lacks $uuid")

    suspend fun read(uuid: UUID): ByteArray = op { gatt!!.readCharacteristic(characteristic(uuid)) }

    @Suppress("DEPRECATION")
    suspend fun write(uuid: UUID, value: ByteArray): Unit = op {
        val c = characteristic(uuid)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = value
            gatt!!.writeCharacteristic(c)
        }
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
        else pending?.completeExceptionally(IOException("disconnected (status $status)"))
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        pending?.complete(mtu)
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit) else pending?.completeExceptionally(IOException("discovery $status"))
    }

    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(value) else pending?.completeExceptionally(IOException("read $status"))
    }

    @Deprecated("Called on Android 12L and older")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(c.value ?: ByteArray(0))
        else pending?.completeExceptionally(IOException("read $status"))
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit) else pending?.completeExceptionally(IOException("write $status"))
    }

    override fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }
}
