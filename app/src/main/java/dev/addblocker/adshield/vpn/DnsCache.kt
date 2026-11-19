package dev.addblocker.adshield.vpn

import java.util.concurrent.ConcurrentHashMap

/**
 * Small TTL-aware cache for upstream answers. Keeps repeat lookups off the
 * network (faster page loads, less data) without ever holding an entry past
 * the TTL the resolver itself returned.
 */
class DnsCache(private val maxEntries: Int = 2048) {

    private class Entry(val payload: ByteArray, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(name: String, qType: Int): ByteArray? {
        val key = key(name, qType)
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove(key)
            return null
        }
        return entry.payload
    }

    fun put(name: String, qType: Int, payload: ByteArray, ttlSeconds: Int) {
        if (ttlSeconds <= 0) return
        if (entries.size >= maxEntries) entries.clear()
        val expiresAt = System.currentTimeMillis() + ttlSeconds.coerceAtMost(3600) * 1000L
        entries[key(name, qType)] = Entry(payload, expiresAt)
    }

    fun clear() = entries.clear()

    private fun key(name: String, qType: Int): String = name.lowercase() + "|" + qType
}
