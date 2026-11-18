package org.saveoursoul.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * A backend delivery receipt: HMAC(device secret, "SOSACK1" || frameId)[:8].
 *
 * Relays carry receipts back to the victim over Bluetooth. The victim's phone stops
 * re-broadcasting its own frame only on a receipt it can verify with its own key, so a
 * malicious phone in the crowd cannot silence an SOS by faking "delivered".
 */
data class Ack(val frameId: UUID, val receipt: ByteArray) {
    override fun equals(other: Any?) = other is Ack && other.frameId == frameId && other.receipt.contentEquals(receipt)
    override fun hashCode() = frameId.hashCode()
}

object Receipts {
    const val LENGTH = 8
    const val ACK_LENGTH = 16 + LENGTH
    private val DOMAIN = "SOSACK1".toByteArray()

    fun compute(frameId: UUID, signer: FrameSigner): ByteArray =
        signer.hmacSha256(DOMAIN + uuidBytes(frameId)).copyOf(LENGTH)

    fun verify(ack: Ack, signer: FrameSigner): Boolean =
        ack.receipt.size == LENGTH && MessageDigest.isEqual(compute(ack.frameId, signer), ack.receipt)

    fun encode(acks: List<Ack>): ByteArray {
        val buf = ByteBuffer.allocate(acks.size * ACK_LENGTH)
        acks.forEach { buf.putLong(it.frameId.mostSignificantBits).putLong(it.frameId.leastSignificantBits).put(it.receipt.copyOf(LENGTH)) }
        return buf.array()
    }

    fun decode(b: ByteArray): List<Ack> = (0 until b.size / ACK_LENGTH).map { i ->
        val buf = ByteBuffer.wrap(b, i * ACK_LENGTH, ACK_LENGTH)
        val id = UUID(buf.getLong(), buf.getLong())
        Ack(id, ByteArray(LENGTH).also { buf.get(it) })
    }

    internal fun uuidBytes(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
}

/** Recently confirmed frames this phone knows about, served to peers so they can stop spreading them. */
class AckLedger(private val capacity: Int = 64) {
    private val acks = LinkedHashMap<UUID, Ack>()

    @Synchronized
    fun add(ack: Ack) {
        acks.remove(ack.frameId)
        acks[ack.frameId] = ack
        while (acks.size > capacity) acks.remove(acks.keys.first())
    }

    /** Newest first. */
    @Synchronized
    fun recent(max: Int): List<Ack> = acks.values.reversed().take(max)
}
