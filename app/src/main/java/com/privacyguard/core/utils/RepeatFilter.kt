package com.privacyguard.core.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Says whether an event is a repeat of one seen less than [windowMs] ago.
 * Used to store one row per lookup: Android asks for A and AAAA (and often HTTPS)
 * records of the same name at once, and retries unanswered queries, so a single
 * lookup used to become two to six rows.
 */
class RepeatFilter(private val windowMs: Long, private val maxKeys: Int = 4_096) {

    private val lastSeen = ConcurrentHashMap<String, Long>()

    /** True when [key] was seen within the window; either way, [now] is remembered. */
    fun isRepeat(key: String, now: Long): Boolean {
        val previous = lastSeen.put(key, now)
        if (lastSeen.size > maxKeys) lastSeen.entries.removeIf { now - it.value >= windowMs }
        return previous != null && now - previous < windowMs
    }
}
