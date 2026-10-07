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
    val bytesTransferred: Long,
    val hostName:        String? = null,
    val securityInfo:    String  = "Unknown",
    val encryptionInfo:  String  = "Unknown",
    val payloadPreview:  String? = null,
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
    val activeConnections:   List<ActiveConnectionInfo> = emptyList(),
    val recentActivity:      List<ActivityInfo>     = emptyList(),
    val topTrackers:         List<TrackerInfo>       = emptyList(),
    val appStats:            List<AppStat>           = emptyList(),
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
        listOf(blockedToday, trackersBlocked, totalPackets, dataSavedBytes, ipv6PacketsBlocked, ipv6BytesBlocked)
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
