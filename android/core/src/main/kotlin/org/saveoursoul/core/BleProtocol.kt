package org.saveoursoul.core

import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * SOS-BLE v1: a GATT service that every SaveOurSoul phone (Android and iOS) exposes and scans
 * for. It is built for very dense crowds (Hajj: Arafat, Mina, Muzdalifah) where cellular
 * networks are saturated and Nearby-style connection meshes break down. See docs/BLE.md.
 *
 * Advertisement: service UUID + a 5-byte "beacon" telling scanners whether this phone has an
 * SOS of its own, holds frames to deliver, or has working internet, plus a short digest of
 * what it holds so scanners skip phones they have already synced with.
 *
 * Characteristics (all values at most 512 bytes):
 *  - INBOX     (write): a central pushes frames, n × 83 bytes.
 *  - OUTBOX    (read):  up to 6 frames the peripheral wants delivered, rotated between readers.
 *  - ACKS      (write): a central reports frames it delivered, n × (frameId 16 + receipt 8).
 *  - CONFIRMED (read):  receipts this phone has collected, n × 24 bytes.
 */
/**
 * Each environment gets its own radio namespace, so phones running a dev or testing build never
 * exchange frames with production phones (a test phone would otherwise pick up a real pilgrim's
 * SOS and hand it to the wrong backend, which rejects it).
 */
enum class MeshNamespace(val bleServiceUuid: UUID, val nearbyServiceId: String) {
    PRODUCTION(UUID.fromString("5a0e5011-0000-4c8a-9f3e-5a0e50115a0e"), "org.saveoursoul.sos.v1"),
    TESTING(UUID.fromString("5a0e5011-0000-4c8a-9f3e-5a0e50115a7e"), "org.saveoursoul.sos.v1.testing"),
    DEV(UUID.fromString("5a0e5011-0000-4c8a-9f3e-5a0e50115ade"), "org.saveoursoul.sos.v1.dev"),
}

object BleProtocol {
    /** Production service UUID; see [MeshNamespace] for the other environments. */
    val SERVICE_UUID: UUID = MeshNamespace.PRODUCTION.bleServiceUuid
    val INBOX_UUID: UUID = UUID.fromString("5a0e5011-0001-4c8a-9f3e-5a0e50115a0e")
    val OUTBOX_UUID: UUID = UUID.fromString("5a0e5011-0002-4c8a-9f3e-5a0e50115a0e")
    val ACKS_UUID: UUID = UUID.fromString("5a0e5011-0003-4c8a-9f3e-5a0e50115a0e")
    val CONFIRMED_UUID: UUID = UUID.fromString("5a0e5011-0004-4c8a-9f3e-5a0e50115a0e")

    const val MAX_VALUE = 512
    const val MAX_FRAMES_PER_VALUE = MAX_VALUE / FrameCodec.FRAME_LENGTH // 6
    const val MAX_ACKS_PER_VALUE = MAX_VALUE / Receipts.ACK_LENGTH // 21

    const val BEACON_VERSION = 1
    const val FLAG_OWN_SOS = 0x01
    const val FLAG_HAS_INTERNET = 0x02
    const val FLAG_HOLDS_FRAMES = 0x04

    /**
     * What a phone advertises. [digest] is 24 bits of SHA-256 over the sorted ids of the frames it
     * holds; it changes whenever its inventory changes.
     */
    data class Beacon(val flags: Int, val digest: Int) {
        val ownSos get() = flags and FLAG_OWN_SOS != 0
        val hasInternet get() = flags and FLAG_HAS_INTERNET != 0
        val holdsFrames get() = flags and FLAG_HOLDS_FRAMES != 0

        /** Android: service data bytes. */
        fun toServiceData(): ByteArray = byteArrayOf(
            BEACON_VERSION.toByte(), flags.toByte(),
            (digest shr 16).toByte(), (digest shr 8).toByte(), digest.toByte(),
        )

        /** iOS cannot advertise service data, only a local name: "S1" + flags + 6 hex digits = 9 chars. */
        fun toLocalName(): String = String.format(java.util.Locale.ROOT, "S1%01x%06x", flags and 0xF, digest and 0xFFFFFF)

        companion object {
            fun fromServiceData(b: ByteArray?): Beacon? {
                if (b == null || b.size < 5 || b[0].toInt() != BEACON_VERSION) return null
                val d = ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF)
                return Beacon(b[1].toInt() and 0xFF, d)
            }

            fun fromLocalName(name: String?): Beacon? {
                if (name == null || name.length != 9 || !name.startsWith("S1")) return null
                val flags = name.substring(2, 3).toIntOrNull(16) ?: return null
                val digest = name.substring(3).toIntOrNull(16) ?: return null
                return Beacon(flags, digest)
            }
        }
    }

    fun digest(frameIds: Collection<UUID>): Int {
        if (frameIds.isEmpty()) return 0
        val md = MessageDigest.getInstance("SHA-256")
        frameIds.map(Receipts::uuidBytes).sortedWith { a, b -> a.toUByteArrayCompare(b) }.forEach(md::update)
        val h = md.digest()
        return ((h[0].toInt() and 0xFF) shl 16) or ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
    }

    private fun ByteArray.toUByteArrayCompare(o: ByteArray): Int {
        for (i in indices) {
            val c = (this[i].toInt() and 0xFF) - (o[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return 0
    }
}

/**
 * Decides, for each phone seen in a scan, whether to connect and what to do. Encodes the
 * crowd rules that keep a sea of phones from drowning the radio:
 *
 *  - Holding an undelivered SOS and seeing a phone with internet: push to it (fast path).
 *  - Having internet and seeing a phone that holds frames: pull them and upload.
 *  - Without internet: always pull from a victim (it carries the SOS out of a dead zone),
 *    pull from mere relays only occasionally (probabilistic flooding).
 *  - Never re-sync the same (peer, inventory digest) within a cooldown; ignore weak signals.
 */
class ContactPolicy(
    private val random: Random = Random(),
    private val cooldownMs: Long = 60_000,
    private val relayPullProbability: Double = 0.15,
    private val minRssi: Int = -92,
) {
    enum class Action { NONE, PUSH, PULL, EXCHANGE }

    data class Local(val holdsUndelivered: Boolean, val hasInternet: Boolean)

    private val lastContact = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2048
    }

    @Synchronized
    fun decide(peerKey: String, beacon: BleProtocol.Beacon?, rssi: Int, local: Local, nowMs: Long): Action {
        if (rssi < minRssi) return Action.NONE
        // An unknown beacon (e.g. a backgrounded iPhone, which cannot advertise flags) might hold anything.
        val peerHolds = beacon?.holdsFrames ?: true
        val peerVictim = beacon?.ownSos ?: false
        val peerOnline = beacon?.hasInternet ?: false

        val push = local.holdsUndelivered && peerOnline
        val pull = peerHolds && when {
            local.hasInternet -> true
            peerVictim -> true
            else -> random.nextDouble() < relayPullProbability
        }
        val action = when {
            push && pull -> Action.EXCHANGE
            push -> Action.PUSH
            pull -> Action.PULL
            else -> Action.NONE
        }
        if (action == Action.NONE) return action

        val key = "$peerKey/${beacon?.digest ?: -1}/${local.holdsUndelivered}"
        val last = lastContact[key]
        if (last != null && nowMs - last < cooldownMs) return Action.NONE
        lastContact[key] = nowMs
        return action
    }
}

/**
 * Chooses which frames a peripheral serves on OUTBOX reads. Own frames first, then frames
 * served the fewest times, then fewest hops, so successive readers get different frames and
 * everything a relay carries eventually goes out even though one read holds only 6.
 */
class OutboxRotation {
    private val served = HashMap<UUID, Int>()

    @Synchronized
    fun pick(entries: List<Outbox.Entry>, max: Int = BleProtocol.MAX_FRAMES_PER_VALUE): List<ByteArray> {
        val chosen = entries.sortedWith(
            compareByDescending<Outbox.Entry> { it.own }
                .thenBy { served[it.frameId] ?: 0 }
                .thenBy { FrameCodec.hops(it.frame) }
                .thenByDescending { it.addedAt },
        ).take(max)
        chosen.forEach { served[it.frameId] = (served[it.frameId] ?: 0) + 1 }
        if (served.size > 4096) served.keys.retainAll(entries.map { it.frameId }.toSet())
        return chosen.map { it.frame }
    }
}
