package org.saveoursoul.core

import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class PanicFrameTest {
    private val vectors = JSONObject(File(System.getProperty("sos.vectors")).readText())
    private val signer = SecretKeySigner(hex(vectors.getString("secretHex")))

    private fun frameOf(j: JSONObject) = PanicFrame(
        version = j.getInt("version"), flags = j.getInt("flags"), kind = j.getInt("kind"), hops = j.getInt("hops"),
        frameId = UUID.fromString(j.getString("frameId")), alertId = UUID.fromString(j.getString("alertId")),
        deviceId = UUID.fromString(j.getString("deviceId")), timestamp = j.getLong("timestamp"),
        latE7 = j.getInt("latE7"), lonE7 = j.getInt("lonE7"), accuracyM = j.getInt("accuracyM"), batteryPct = j.getInt("batteryPct"),
    )

    @Test
    fun `encodes byte-for-byte like the backend`() {
        val arr = vectors.getJSONArray("vectors")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val frame = frameOf(v.getJSONObject("frame"))
            val bytes = FrameCodec.encode(frame, signer)
            assertEquals(v.getString("base64url"), FrameCodec.toBase64Url(bytes), v.getString("name"))
            assertEquals(v.getString("sms"), FrameCodec.toSms(bytes))
            assertEquals(frame, FrameCodec.decode(FrameCodec.fromBase64Url(v.getString("base64url"))))
            assertTrue(FrameCodec.verify(bytes, signer))
            assertTrue(FrameCodec.toSms(bytes).length <= 160)
        }
    }

    @Test
    fun `hops can change in transit but nothing else can`() {
        val bytes = FrameCodec.fromBase64Url(vectors.getJSONArray("vectors").getJSONObject(0).getString("base64url"))
        assertTrue(FrameCodec.verify(FrameCodec.withHops(bytes, 6), signer))
        val tampered = bytes.copyOf().also { it[60] = (it[60] + 1).toByte() }
        assertFalse(FrameCodec.verify(tampered, signer))
        assertFalse(FrameCodec.verify(bytes, SecretKeySigner(ByteArray(32))))
    }

    @Test
    fun `split and join mesh payloads`() {
        val a = ByteArray(83) { 1 }
        val b = ByteArray(83) { 2 }
        val parts = FrameCodec.split(FrameCodec.join(listOf(a, b)) + byteArrayOf(9, 9))
        assertEquals(2, parts.size)
        assertContentEquals(b, parts[1])
    }
}

class RelayAndOutboxTest {
    private val signer = SecretKeySigner(ByteArray(32) { it.toByte() })
    private fun frame(hops: Int = 0) = FrameCodec.encode(
        PanicFrame(flags = FrameFlags.HAS_LOCATION, kind = 0, hops = hops, frameId = UUID.randomUUID(), alertId = UUID.randomUUID(),
            deviceId = UUID.randomUUID(), timestamp = 1_800_000_000, latE7 = 1, lonE7 = 2),
        signer,
    )

    @Test
    fun `relay policy increments hops, dedupes and enforces hop limit`() {
        val policy = RelayPolicy(SeenCache())
        val f = frame(hops = 2)
        val d = policy.onFrameFromPeer(f)
        assertIs<RelayPolicy.Decision.Relay>(d)
        assertEquals(3, FrameCodec.hops(d.frame))
        assertTrue(FrameCodec.verify(d.frame, signer))
        assertIs<RelayPolicy.Decision.Drop>(policy.onFrameFromPeer(f))
        assertIs<RelayPolicy.Decision.Drop>(policy.onFrameFromPeer(frame(hops = FrameCodec.MAX_HOPS)))
        assertIs<RelayPolicy.Decision.Drop>(policy.onFrameFromPeer(ByteArray(10)))
    }

    @Test
    fun `outbox persists across restarts and prunes`() {
        val file = File.createTempFile("outbox", ".txt").also { it.delete() }
        var now = 1_000L
        val box = Outbox(file) { now }
        val a = frame()
        val b = frame()
        assertTrue(box.add(a, own = true))
        assertFalse(box.add(a, own = true))
        box.add(b, own = false)
        box.markConfirmed(listOf(FrameCodec.frameId(a)))

        val reloaded = Outbox(file) { now }
        assertEquals(2, reloaded.size())
        assertEquals(listOf(FrameCodec.frameId(b)), reloaded.unconfirmed().map { it.frameId })
        assertFalse(reloaded.unconfirmed().single().own)

        now += 25 * 3600
        reloaded.prune()
        assertEquals(1, reloaded.size()) // confirmed one dropped, unconfirmed kept for retry
        now += 72 * 3600
        reloaded.prune()
        assertEquals(0, reloaded.size())
    }

    @Test
    fun `outbox keeps the shortest-path copy of a frame`() {
        val box = Outbox(File.createTempFile("outbox", ".txt").also { it.delete() })
        val f = frame(hops = 4)
        box.add(f, own = false)
        box.add(FrameCodec.withHops(f, 1), own = false)
        assertEquals(1, FrameCodec.hops(box.unconfirmed().single().frame))
    }
}
