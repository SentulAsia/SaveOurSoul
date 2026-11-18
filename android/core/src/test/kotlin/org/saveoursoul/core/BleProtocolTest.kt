package org.saveoursoul.core

import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptTest {
    private val vectors = JSONObject(File(System.getProperty("sos.vectors")).readText())
    private val secret = vectors.getString("secretHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val signer = SecretKeySigner(secret)

    @Test
    fun `receipts match the backend and reject forgeries`() {
        val arr = vectors.getJSONArray("vectors")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val id = UUID.fromString(v.getJSONObject("frame").getString("frameId"))
            val receipt = FrameCodec.fromBase64Url(v.getString("receipt"))
            assertTrue(Receipts.verify(Ack(id, receipt), signer), v.getString("name"))
            assertFalse(Receipts.verify(Ack(id, receipt.copyOf().also { it[0] = (it[0] + 1).toByte() }), signer))
            assertFalse(Receipts.verify(Ack(UUID.randomUUID(), receipt), signer))
        }
    }

    @Test
    fun `ack encoding round-trips and the ledger keeps the newest`() {
        val acks = List(3) { Ack(UUID.randomUUID(), ByteArray(8) { b -> (b + it).toByte() }) }
        assertEquals(acks, Receipts.decode(Receipts.encode(acks)))
        val ledger = AckLedger(capacity = 2)
        acks.forEach(ledger::add)
        assertEquals(listOf(acks[2], acks[1]), ledger.recent(10))
        assertEquals(BleProtocol.MAX_ACKS_PER_VALUE * Receipts.ACK_LENGTH <= BleProtocol.MAX_VALUE, true)
    }
}

class BleProtocolTest {
    @Test
    fun `beacon encodes for Android service data and iOS local names`() {
        val b = BleProtocol.Beacon(BleProtocol.FLAG_OWN_SOS or BleProtocol.FLAG_HOLDS_FRAMES, 0xABCDEF)
        assertEquals(b, BleProtocol.Beacon.fromServiceData(b.toServiceData()))
        assertEquals("S15abcdef", b.toLocalName())
        assertEquals(b, BleProtocol.Beacon.fromLocalName(b.toLocalName()))
        assertTrue(b.ownSos && b.holdsFrames && !b.hasInternet)
        assertNull(BleProtocol.Beacon.fromLocalName("iPhone"))
        assertNull(BleProtocol.Beacon.fromServiceData(byteArrayOf(9, 0, 0, 0, 0)))
    }

    @Test
    fun `digest is order independent and changes with inventory`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertEquals(BleProtocol.digest(listOf(a, b)), BleProtocol.digest(listOf(b, a)))
        assertTrue(BleProtocol.digest(listOf(a)) != BleProtocol.digest(listOf(a, b)))
        assertEquals(0, BleProtocol.digest(emptyList()))
    }

    private fun beacon(vararg flags: Int) = BleProtocol.Beacon(flags.fold(0) { x, y -> x or y }, 42)
    private val offline = ContactPolicy.Local(holdsUndelivered = false, hasInternet = false)

    @Test
    fun `victim without internet pushes to a phone that has internet`() {
        val p = ContactPolicy()
        val victim = ContactPolicy.Local(holdsUndelivered = true, hasInternet = false)
        assertEquals(ContactPolicy.Action.PUSH, p.decide("online", beacon(BleProtocol.FLAG_HAS_INTERNET), -60, victim, 0))
        assertEquals(ContactPolicy.Action.EXCHANGE,
            p.decide("online-relay", beacon(BleProtocol.FLAG_HAS_INTERNET, BleProtocol.FLAG_HOLDS_FRAMES, BleProtocol.FLAG_OWN_SOS), -60, victim, 0))
    }

    @Test
    fun `online phone pulls from anyone holding frames, offline phones always carry victims`() {
        val p = ContactPolicy(random = Random(1), relayPullProbability = 0.0)
        val online = ContactPolicy.Local(holdsUndelivered = false, hasInternet = true)
        assertEquals(ContactPolicy.Action.PULL, p.decide("relay", beacon(BleProtocol.FLAG_HOLDS_FRAMES), -70, online, 0))
        assertEquals(ContactPolicy.Action.PULL, p.decide("victim", beacon(BleProtocol.FLAG_OWN_SOS, BleProtocol.FLAG_HOLDS_FRAMES), -70, offline, 0))
        assertEquals(ContactPolicy.Action.NONE, p.decide("relay2", beacon(BleProtocol.FLAG_HOLDS_FRAMES), -70, offline, 0))
        assertEquals(ContactPolicy.Action.NONE, p.decide("idle", beacon(), -50, online, 0))
    }

    @Test
    fun `cooldown, weak signal and unknown beacons`() {
        val p = ContactPolicy(cooldownMs = 60_000)
        val online = ContactPolicy.Local(holdsUndelivered = false, hasInternet = true)
        val holds = beacon(BleProtocol.FLAG_HOLDS_FRAMES)
        assertEquals(ContactPolicy.Action.PULL, p.decide("a", holds, -70, online, 0))
        assertEquals(ContactPolicy.Action.NONE, p.decide("a", holds, -70, online, 30_000))
        assertEquals(ContactPolicy.Action.PULL, p.decide("a", holds.copy(digest = 7), -70, online, 30_000)) // new inventory
        assertEquals(ContactPolicy.Action.PULL, p.decide("a", holds, -70, online, 61_000))
        assertEquals(ContactPolicy.Action.NONE, p.decide("far", holds, -99, online, 0))
        assertEquals(ContactPolicy.Action.PULL, p.decide("ios-bg", null, -70, online, 0))
    }

    @Test
    fun `outbox rotation serves own frames first then spreads the rest`() {
        val signer = SecretKeySigner(ByteArray(32))
        fun f() = FrameCodec.encode(PanicFrame(flags = 0, kind = 0, frameId = UUID.randomUUID(), alertId = UUID.randomUUID(),
            deviceId = UUID.randomUUID(), timestamp = 1), signer)
        val own = Outbox.Entry(f(), own = true, addedAt = 0)
        val relayed = List(10) { Outbox.Entry(f(), own = false, addedAt = it.toLong()) }
        val rot = OutboxRotation()
        val first = rot.pick(listOf(own) + relayed)
        val second = rot.pick(listOf(own) + relayed)
        assertTrue(first[0].contentEquals(own.frame) && second[0].contentEquals(own.frame))
        val relayedServed = (first.drop(1) + second.drop(1)).map(FrameCodec::frameId).toSet()
        assertEquals(10, relayedServed.size) // 5 + 5 distinct relayed frames across two reads
    }
}
