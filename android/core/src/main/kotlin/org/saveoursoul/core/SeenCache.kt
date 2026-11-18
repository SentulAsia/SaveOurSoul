package org.saveoursoul.core

import java.util.UUID

/** Bounded LRU of frame ids a relay has already handled, so mesh floods terminate. */
class SeenCache(private val capacity: Int = 4096) {
    private val map = object : LinkedHashMap<UUID, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Unit>?) = size > capacity
    }

    /** Returns true the first time [id] is offered, false for repeats. */
    @Synchronized
    fun firstSeen(id: UUID): Boolean = map.put(id, Unit) == null

    @Synchronized
    fun contains(id: UUID): Boolean = map.containsKey(id)
}
