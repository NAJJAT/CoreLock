package com.privacyguard.app.core.stats

/**
 * Turns byte counters into rates. Called about once a second with every live
 * session's running total and the total of sessions that have closed so far, so a
 * session closing between two ticks still counts towards the throughput.
 */
class LiveTrafficMeter {

    data class Rates(
        /** Bytes per second for each live session, by its id. */
        val perSession: Map<String, Long>,
        /** Bytes per second for all traffic. */
        val total: Long,
    )

    private var lastAt = 0L
    private var lastTotal = -1L
    private var lastPerSession: Map<String, Long> = emptyMap()

    fun tick(now: Long, liveBytes: Map<String, Long>, closedBytesTotal: Long): Rates {
        val total = closedBytesTotal + liveBytes.values.sum()
        val elapsed = (now - lastAt).coerceAtLeast(1L)
        val perSession = liveBytes.mapValues { (id, bytes) ->
            val before = lastPerSession[id] ?: 0L   // new session: all its bytes are new
            perSecond(bytes - before, elapsed)
        }
        val totalRate = if (lastTotal < 0) 0L else perSecond(total - lastTotal, elapsed)
        lastAt = now
        lastTotal = total
        lastPerSession = liveBytes
        return Rates(perSession, totalRate)
    }

    private fun perSecond(delta: Long, elapsedMs: Long): Long =
        (delta.coerceAtLeast(0L) * 1_000L) / elapsedMs
}
