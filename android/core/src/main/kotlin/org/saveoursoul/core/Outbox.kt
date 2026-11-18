package org.saveoursoul.core

import java.io.File
import java.util.UUID

/**
 * Durable store-and-forward queue of frames: this phone's own panic frames and frames it is
 * relaying for others. Survives process death and reboots, so a frame picked up over Bluetooth
 * in a dead zone is uploaded as soon as any network appears.
 */
class Outbox(private val file: File, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {

    data class Entry(
        val frame: ByteArray,
        /** True for frames this phone created; false for frames relayed on behalf of someone else. */
        val own: Boolean,
        val addedAt: Long,
        /** The backend has acknowledged receipt (accepted or duplicate). */
        val confirmed: Boolean = false,
        val smsSent: Boolean = false,
    ) {
        val frameId: UUID get() = FrameCodec.frameId(frame)
    }

    private val entries = LinkedHashMap<UUID, Entry>()

    init {
        load()
    }

    /** Adds a frame; returns false if it was already queued (keeps the lower hop count). */
    @Synchronized
    fun add(frame: ByteArray, own: Boolean): Boolean {
        val id = FrameCodec.frameId(frame)
        val existing = entries[id]
        if (existing != null) {
            if (FrameCodec.hops(frame) < FrameCodec.hops(existing.frame)) {
                entries[id] = existing.copy(frame = frame)
                save()
            }
            return false
        }
        entries[id] = Entry(frame, own, clock())
        save()
        return true
    }

    @Synchronized
    fun unconfirmed(): List<Entry> = entries.values.filter { !it.confirmed }

    @Synchronized
    fun get(id: UUID): Entry? = entries[id]

    @Synchronized
    fun markConfirmed(ids: Collection<UUID>) = update(ids) { it.copy(confirmed = true) }

    @Synchronized
    fun markSmsSent(ids: Collection<UUID>) = update(ids) { it.copy(smsSent = true) }

    /** Drops confirmed frames after [confirmedTtlSec] and anything after [maxAgeSec] (the backend rejects it anyway). */
    @Synchronized
    fun prune(confirmedTtlSec: Long = 24 * 3600, maxAgeSec: Long = 72 * 3600) {
        val now = clock()
        val before = entries.size
        entries.values.removeAll { e -> (e.confirmed && now - e.addedAt > confirmedTtlSec) || now - e.addedAt > maxAgeSec }
        if (entries.size != before) save()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun update(ids: Collection<UUID>, fn: (Entry) -> Entry) {
        var changed = false
        for (id in ids) entries[id]?.let { entries[id] = fn(it); changed = true }
        if (changed) save()
    }

    private fun load() {
        if (!file.exists()) return
        file.readLines().forEach { line ->
            val p = line.split(' ')
            if (p.size != 5) return@forEach
            runCatching {
                val frame = FrameCodec.fromBase64Url(p[0])
                if (frame.size != FrameCodec.FRAME_LENGTH) return@runCatching
                val e = Entry(frame, p[1] == "1", p[2].toLong(), p[3] == "1", p[4] == "1")
                entries[e.frameId] = e
            }
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.values.joinToString("\n") { e ->
            listOf(FrameCodec.toBase64Url(e.frame), e.own.bit, e.addedAt, e.confirmed.bit, e.smsSent.bit).joinToString(" ")
        })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private val Boolean.bit get() = if (this) "1" else "0"
}
