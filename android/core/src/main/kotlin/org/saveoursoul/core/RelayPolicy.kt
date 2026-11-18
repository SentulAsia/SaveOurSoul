package org.saveoursoul.core

/**
 * Decides what a phone does with a frame received from a nearby peer (Bluetooth / Wi-Fi mesh).
 * Relays cannot verify the MAC (only the sender and backend hold the key); the backend does that.
 */
class RelayPolicy(private val seen: SeenCache, private val maxHops: Int = FrameCodec.MAX_HOPS) {

    sealed interface Decision {
        /** Queue for upload and re-broadcast. [frame] has the hop counter already incremented. */
        data class Relay(val frame: ByteArray) : Decision
        data class Drop(val reason: String) : Decision
    }

    fun onFrameFromPeer(raw: ByteArray): Decision {
        val frame = try {
            FrameCodec.decode(raw)
        } catch (e: FrameFormatException) {
            return Decision.Drop("malformed: ${e.message}")
        }
        if (!seen.firstSeen(frame.frameId)) return Decision.Drop("already seen")
        val nextHops = frame.hops + 1
        if (nextHops > maxHops) return Decision.Drop("hop limit")
        return Decision.Relay(FrameCodec.withHops(raw, nextHops))
    }
}
