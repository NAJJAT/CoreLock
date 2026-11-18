package com.privacyguard.core.session

import com.privacyguard.core.utils.BoundedRecentMap

/**
 * Five-tuples already logged as blocked: a dropped SYN or UDP packet is retransmitted on
 * the same tuple. Those are packets of one attempt, not new attempts, so the service skips
 * them before the owner lookup and [BlockRowGate].
 *
 * Bug-fix round 2:
 *  - Hard cap (S-2): old entries used to be dropped only when older than the window, so
 *    a burst of distinct blocked tuples inside it grew the table without limit and each new
 *    entry scanned it all on the packet thread. Past [maxKeys] the oldest tuple now goes;
 *    at worst that tuple is counted as one more attempt.
 *  - One rule hit per attempt (S-1): the hit count was written to the database for every
 *    retransmitted packet. [countsAsHit] uses the same window as the history row.
 */
class BlockAttempts(
    private val windowMs: Long = WINDOW_MS,
    maxKeys: Int = MAX_KEYS,
) {
    private val logged = BoundedRecentMap<SessionKey>(maxKeys)

    /** True when a packet on [key] at [now] starts a new attempt (it is recorded either way). */
    fun record(key: SessionKey, now: Long): Boolean {
        val last = logged.put(key, now)
        return last == null || now - last !in 0 until windowMs
    }

    /**
     * Whether a blocked packet on [key] at [now] counts as a hit of its rule: only the first
     * packet of an attempt, the one [record] will log. Reads without recording.
     */
    fun countsAsHit(key: SessionKey, now: Long): Boolean {
        val last = logged.get(key)
        return last == null || now - last !in 0 until windowMs
    }

    val size: Int get() = logged.size

    fun clear() = logged.clear()

    companion object {
        const val WINDOW_MS = 30_000L
        const val MAX_KEYS = 4_096
    }
}
