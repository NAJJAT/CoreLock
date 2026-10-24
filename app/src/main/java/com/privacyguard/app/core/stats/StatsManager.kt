package com.privacyguard.app.core.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ActiveConnectionInfo(
    val id:              String,
    val appName:         String,
    val packageName:     String = "",
    val destination:     String,
    val destinationIp:   String,
    val destinationPort: Int,
    val protocol:        String,
    val isBlocked:       Boolean,
    /** Bytes up + down so far. */
    val bytesTransferred: Long,
    val hostName:        String? = null,
    val securityInfo:    String  = "Unknown",
    val encryptionInfo:  String  = "Unknown",
    val payloadPreview:  String? = null,
    val bytesSent:       Long = 0L,
    val bytesReceived:   Long = 0L,
    /** Up + down during the last second. */
    val bytesPerSec:     Long = 0L,
)

data class TrackerInfo(
    val domain: String,
    val count:  Int,
)

data class ActivityInfo(
    val appName:     String,
    val description: String,
    val timeMillis:  Long,
    val isBlocked:   Boolean,
)

data class AppStat(
    val uid:           Int,
    val packageName:   String,
    val appName:       String,
    val blockedCount:  Long,
    val bytesTransferred: Long,
)

data class StatsSnapshot(
    val blockedToday:        Long                    = 0L,
    val dataSavedBytes:      Long                    = 0L,
    val totalPackets:        Long                    = 0L,
    val ipv6PacketsBlocked:  Long                    = 0L,
    val ipv6BytesBlocked:    Long                    = 0L,
    val totalTrackersBlocked: Int                   = 0,
    /** TCP connections stopped by their TLS server name (SNI), not by DNS; part of [blockedToday]. */
    val sniBlocked:          Long                    = 0L,
    /** QUIC flows stopped by the server name in their Initial; part of [blockedToday]. */
    val quicNameBlocked:     Long                    = 0L,
    /** QUIC flows dropped because QUIC is blocked (setting, Strict default, inspection). */
    val quicOffDropped:      Long                    = 0L,
    /** QUIC flows dropped because their Initial could not be read; the app retries over TCP. */
    val quicUnreadableDropped: Long                  = 0L,
    /** QUIC flows dropped because the hold limits were reached; the app retries or uses TCP. */
    val quicHoldLimitDropped: Long                   = 0L,
    /** Blocked connection attempts written to the history (first of each retry window). */
    val blockedRowsWritten:  Long                    = 0L,
    /** Blocked connection attempts merged into that row: retries within BlockRowGate.WINDOW_MS. */
    val blockedRowsMerged:   Long                    = 0L,
    val activeConnections:   List<ActiveConnectionInfo> = emptyList(),
    val recentActivity:      List<ActivityInfo>     = emptyList(),
    val topTrackers:         List<TrackerInfo>       = emptyList(),
    val appStats:            List<AppStat>           = emptyList(),
    /** All traffic through the tunnel (up + down) during the last second. */
    val throughputBytesPerSec: Long                  = 0L,
)

/**
 * Singleton stats observable for UI screens.
 *
 * The VPN thread records every packet, so writes must be cheap: counters are
 * plain atomics and list changes go to an internal state. [snapshot] is
 * published at most once per [PUBLISH_INTERVAL_MS], so screens redraw about
 * once a second instead of once per packet. A trailing publish always follows
 * the last write, so the final numbers show up even when traffic stops.
 */
object StatsManager {
    const val PUBLISH_INTERVAL_MS = 1_000L

    private val blockedToday = AtomicLong()
    private val trackersBlocked = AtomicLong()
    private val totalPackets = AtomicLong()
    private val dataSavedBytes = AtomicLong()
    private val ipv6PacketsBlocked = AtomicLong()
    private val ipv6BytesBlocked = AtomicLong()
    private val sniBlocked = AtomicLong()
    private val quicNameBlocked = AtomicLong()
    private val quicOffDropped = AtomicLong()
    private val quicUnreadableDropped = AtomicLong()
    private val quicHoldLimitDropped = AtomicLong()
    private val blockedRowsWritten = AtomicLong()
    private val blockedRowsMerged = AtomicLong()

    /** Lists (connections, activity, per-app stats); counter fields here are unused. */
    private val lists = MutableStateFlow(StatsSnapshot())

    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    private val publishPending = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Applies [block] to the list fields. Counter fields it sets are ignored. */
    fun update(block: StatsSnapshot.() -> StatsSnapshot) {
        lists.update { it.block() }
        schedulePublish()
    }

    fun incrementBlocked() {
        blockedToday.incrementAndGet()
        trackersBlocked.incrementAndGet()
        schedulePublish()
    }

    /** A TCP connection stopped by its server name; count it with [incrementBlocked] as well. */
    fun incrementSniBlocked() {
        sniBlocked.incrementAndGet()
        schedulePublish()
    }

    /** A QUIC flow stopped by its server name; count it with [incrementBlocked] as well. */
    fun incrementQuicNameBlocked() {
        quicNameBlocked.incrementAndGet()
        schedulePublish()
    }

    /** A QUIC flow dropped because QUIC is blocked: not a block (the app falls back to TCP). */
    fun incrementQuicOffDropped() {
        quicOffDropped.incrementAndGet()
        schedulePublish()
    }

    /** A QUIC flow dropped as unreadable: not a block (the app falls back to TCP). */
    fun incrementQuicUnreadableDropped() {
        quicUnreadableDropped.incrementAndGet()
        schedulePublish()
    }

    /** A QUIC flow dropped at the hold limits: not a block either. */
    fun incrementQuicHoldLimitDropped() {
        quicHoldLimitDropped.incrementAndGet()
        schedulePublish()
    }

    /**
     * A blocked connection attempt and whether it got its own history row. Written +
     * merged is every attempt, so the 60 s merge never hides how often an app retried.
     */
    fun recordBlockedRow(written: Boolean) {
        (if (written) blockedRowsWritten else blockedRowsMerged).incrementAndGet()
        schedulePublish()
    }

    fun recordPacket(bytes: Long) {
        totalPackets.incrementAndGet()
        dataSavedBytes.addAndGet(bytes)
        schedulePublish()
    }

    fun recordIpv6Blocked(bytes: Long) {
        totalPackets.incrementAndGet()
        ipv6PacketsBlocked.incrementAndGet()
        ipv6BytesBlocked.addAndGet(bytes)
        schedulePublish()
    }

    fun reset() {
        listOf(blockedToday, trackersBlocked, totalPackets, dataSavedBytes, ipv6PacketsBlocked, ipv6BytesBlocked, sniBlocked, quicNameBlocked, quicOffDropped, quicUnreadableDropped, quicHoldLimitDropped, blockedRowsWritten, blockedRowsMerged)
            .forEach { it.set(0) }
        lists.value = StatsSnapshot()
        publishNow()
    }

    /** Publishes the current numbers immediately (used by [reset] and tests). */
    fun publishNow() {
        _snapshot.value = lists.value.copy(
            blockedToday = blockedToday.get(),
            dataSavedBytes = dataSavedBytes.get(),
            totalPackets = totalPackets.get(),
            ipv6PacketsBlocked = ipv6PacketsBlocked.get(),
            ipv6BytesBlocked = ipv6BytesBlocked.get(),
            totalTrackersBlocked = trackersBlocked.get().toInt(),
            sniBlocked = sniBlocked.get(),
            quicNameBlocked = quicNameBlocked.get(),
            quicOffDropped = quicOffDropped.get(),
            quicUnreadableDropped = quicUnreadableDropped.get(),
            quicHoldLimitDropped = quicHoldLimitDropped.get(),
            blockedRowsWritten = blockedRowsWritten.get(),
            blockedRowsMerged = blockedRowsMerged.get(),
        )
    }

    // At most one publish is pending per interval, and nothing runs while idle.
    private fun schedulePublish() {
        if (!publishPending.compareAndSet(false, true)) return
        scope.launch {
            delay(PUBLISH_INTERVAL_MS)
            publishPending.set(false)
            publishNow()
        }
    }
}
