package com.privacyguard.core.session

/**
 * Decides which blocked attempts get a history row: the first one per app, host, port,
 * protocol and source in each [windowMs]; the rest are only counted by the caller.
 *
 * Why: a blocked app retries at once, from a new source port each time, so per
 * connection a single Chrome DoH endpoint wrote about 1,700 rows in five minutes
 * (emulator, 2026-10-24). The window is fixed, not sliding: an app that retries all
 * day still gets one row per window, so the history shows when it kept trying.
 *
 * At most [maxKeys] keys are kept; past that the oldest window is forgotten, which
 * can only write an extra row, never hide a new destination.
 */
class BlockRowGate(
    private val windowMs: Long = WINDOW_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    // Insertion order = order the windows started, since a key is re-inserted when its
    // window restarts: the eldest entry is always the oldest window.
    private val windowStart = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxKeys
    }

    /** True when this attempt opens a new window and should be written as a row. */
    @Synchronized
    fun shouldWrite(uid: Int, host: String, port: Int, protocol: String, source: BlockSource?, now: Long): Boolean {
        val key = "$uid|${host.lowercase().trimEnd('.')}|$port|$protocol|${source?.name.orEmpty()}"
        val start = windowStart[key]
        if (start != null && now - start in 0 until windowMs) return false
        windowStart.remove(key)
        windowStart[key] = now
        return true
    }

    @Synchronized
    fun clear() = windowStart.clear()

    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_KEYS = 4_096
    }
}
