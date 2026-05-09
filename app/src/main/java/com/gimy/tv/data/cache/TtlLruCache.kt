package com.gimy.tv.data.cache

import java.util.Collections

/**
 * Thread-safe LRU map with optional TTL.
 *
 * Replaces the half-dozen hand-rolled LRU caches that used to live inside
 * VodRepositoryImpl, EmbeddedHlsSource, MovieffmSource, and Forum5278Source —
 * each was its own `Collections.synchronizedMap(LinkedHashMap with accessOrder=true,
 * override removeEldestEntry)` block plus matching `synchronized { }` get/put/TTL-check
 * helpers. Centralising them here cuts ~50 lines of boilerplate and gives every
 * caller the same eviction + concurrency guarantees.
 *
 * Pass `ttlMs = null` for indefinite retention (slug↔id, cover URL caches that the
 * caller wants to live for the process lifetime, only bounded by `capacity`).
 * Non-null TTL evicts an entry on the next `get` once it's stale; the eviction is
 * lazy (no background timer), which is fine for our usage where rates are low.
 *
 * Notes:
 *  - LRU is access-order (LinkedHashMap accessOrder=true), so `get` re-orders.
 *  - `getOrPut` is NOT atomic: two concurrent misses both compute. Callers that
 *    need atomicity must lock externally; for our deterministic-hash slug caches
 *    the duplicate compute is harmless.
 */
class TtlLruCache<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMs: Long? = null,
) {
    private data class Entry<V>(val value: V, val timestamp: Long)

    private val map: MutableMap<K, Entry<V>> = Collections.synchronizedMap(
        object : LinkedHashMap<K, Entry<V>>(capacity.coerceAtLeast(1), 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
                size > capacity
        }
    )

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (ttlMs != null && System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
    }

    fun remove(key: K): V? = map.remove(key)?.value

    fun clear() {
        map.clear()
    }

    fun size(): Int = map.size

    /** Best-effort compute-if-absent. Not atomic — concurrent callers may both
     *  miss and both compute; safe when the producer is deterministic (e.g. our
     *  slug→stableHashLong mapping). */
    inline fun getOrPut(key: K, default: () -> V): V {
        get(key)?.let { return it }
        val v = default()
        put(key, v)
        return v
    }
}
