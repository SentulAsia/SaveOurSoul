package org.saveoursoul.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Emergency category, encoded as one byte. Order is part of the wire protocol. */
enum class SosKind { GENERAL, MEDICAL, FIRE, VIOLENCE, ACCIDENT }

object FrameFlags {
    const val HAS_LOCATION = 0x01
    const val CANCEL = 0x02
    const val TEST = 0x04
}

/**
 * SOS wire protocol v1 (see docs/PROTOCOL.md). 83 bytes, big-endian:
 *
 *  0 version | 1 flags | 2 kind | 3 hops (not signed) | 4..19 frameId | 20..35 alertId
 *  36..51 deviceId | 52..55 unix seconds | 56..59 latE7 | 60..63 lonE7 | 64..65 accuracy m
 *  66 battery % | 67..82 HMAC-SHA256(bytes 0..66 with hops = 0) truncated to 16 bytes
 */
data class PanicFrame(
    val version: Int = VERSION,
    val flags: Int,
    val kind: Int,
    val hops: Int = 0,
    val frameId: UUID,
    val alertId: UUID,
    val deviceId: UUID,
    val timestamp: Long,
    val latE7: Int = 0,
    val lonE7: Int = 0,
    val accuracyM: Int = UNKNOWN_ACCURACY,
    val batteryPct: Int = UNKNOWN_BATTERY,
) {
    val isCancel get() = flags and FrameFlags.CANCEL != 0
    val hasLocation get() = flags and FrameFlags.HAS_LOCATION != 0

    companion object {
        const val VERSION = 1
        const val UNKNOWN_ACCURACY = 0xFFFF
        const val UNKNOWN_BATTERY = 0xFF
    }
}

/** Produces the frame MAC. The app backs this with an Android Keystore key so the secret never leaves secure hardware. */
fun interface FrameSigner {
    fun hmacSha256(data: ByteArray): ByteArray
}

class SecretKeySigner(secret: ByteArray) : FrameSigner {
    private val key = SecretKeySpec(secret, "HmacSHA256")
    override fun hmacSha256(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(data)
    }
}

class FrameFormatException(message: String) : Exception(message)

object FrameCodec {
    const val FRAME_LENGTH = 83
    const val SIGNED_LENGTH = 67
    const val MAC_LENGTH = 16
    const val HOPS_OFFSET = 3
    const val MAX_HOPS = 8
    const val SMS_PREFIX = "SOS1:"

    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    fun encode(f: PanicFrame, signer: FrameSigner): ByteArray {
        val buf = ByteBuffer.allocate(FRAME_LENGTH)
        buf.put(f.version.toByte()).put(f.flags.toByte()).put(f.kind.toByte()).put(f.hops.toByte())
        buf.putUuid(f.frameId).putUuid(f.alertId).putUuid(f.deviceId)
        buf.putInt(f.timestamp.toInt()) // unsigned 32-bit on the wire
        buf.putInt(f.latE7).putInt(f.lonE7)
        buf.putShort(f.accuracyM.coerceIn(0, 0xFFFF).toShort())
        buf.put(f.batteryPct.coerceIn(0, 0xFF).toByte())
        val bytes = buf.array()
        mac(bytes, signer).copyInto(bytes, SIGNED_LENGTH)
        return bytes
    }

    /** Parses a frame without verifying the MAC (only the device and backend hold the key). */
    fun decode(b: ByteArray): PanicFrame {
        if (b.size != FRAME_LENGTH) throw FrameFormatException("frame must be $FRAME_LENGTH bytes, got ${b.size}")
        val buf = ByteBuffer.wrap(b)
        val version = buf.get().toInt() and 0xFF
        if (version != PanicFrame.VERSION) throw FrameFormatException("unsupported version $version")
        return PanicFrame(
            version = version,
            flags = buf.get().toInt() and 0xFF,
            kind = buf.get().toInt() and 0xFF,
            hops = buf.get().toInt() and 0xFF,
            frameId = buf.getUuid(),
            alertId = buf.getUuid(),
            deviceId = buf.getUuid(),
            timestamp = buf.getInt().toLong() and 0xFFFFFFFFL,
            latE7 = buf.getInt(),
            lonE7 = buf.getInt(),
            accuracyM = buf.getShort().toInt() and 0xFFFF,
            batteryPct = buf.get().toInt() and 0xFF,
        )
    }

    fun verify(b: ByteArray, signer: FrameSigner): Boolean =
        b.size == FRAME_LENGTH && MessageDigest.isEqual(mac(b, signer), b.copyOfRange(SIGNED_LENGTH, FRAME_LENGTH))

    fun hops(b: ByteArray): Int = b[HOPS_OFFSET].toInt() and 0xFF

    /** Returns a copy with the hop counter changed; the MAC stays valid because hops are not signed. */
    fun withHops(b: ByteArray, hops: Int): ByteArray = b.copyOf().also { it[HOPS_OFFSET] = hops.coerceIn(0, 0xFF).toByte() }

    fun frameId(b: ByteArray): UUID = ByteBuffer.wrap(b, 4, 16).getUuid()

    fun toBase64Url(b: ByteArray): String = b64Enc.encodeToString(b)
    fun fromBase64Url(s: String): ByteArray = try {
        b64Dec.decode(s.trim())
    } catch (e: IllegalArgumentException) {
        throw FrameFormatException("frame is not base64url")
    }

    /** 116 characters of GSM-7-safe text: always fits in one SMS. */
    fun toSms(b: ByteArray): String = SMS_PREFIX + toBase64Url(b)

    private fun mac(b: ByteArray, signer: FrameSigner): ByteArray {
        val signed = b.copyOf(SIGNED_LENGTH)
        signed[HOPS_OFFSET] = 0
        return signer.hmacSha256(signed).copyOf(MAC_LENGTH)
    }

    private fun ByteBuffer.putUuid(u: UUID): ByteBuffer = putLong(u.mostSignificantBits).putLong(u.leastSignificantBits)
    private fun ByteBuffer.getUuid(): UUID = UUID(getLong(), getLong())

    /** Splits a mesh payload (concatenated frames) into individual frames, dropping any trailing garbage. */
    fun split(payload: ByteArray): List<ByteArray> =
        (0 until payload.size / FRAME_LENGTH).map { payload.copyOfRange(it * FRAME_LENGTH, (it + 1) * FRAME_LENGTH) }

    fun join(frames: List<ByteArray>): ByteArray {
        val out = ByteArray(frames.size * FRAME_LENGTH)
        frames.forEachIndexed { i, f -> f.copyInto(out, i * FRAME_LENGTH) }
        return out
    }
}
