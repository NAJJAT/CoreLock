package com.privacyguard.app.core.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveConnectionInfo(
    val id:              String,
    val appName:         String,
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
    val totalTrackersBlocked: Int                   = 0,
    val activeConnections:   List<ActiveConnectionInfo> = emptyList(),
    val recentActivity:      List<ActivityInfo>     = emptyList(),
    val topTrackers:         List<TrackerInfo>       = emptyList(),
    val appStats:            List<AppStat>           = emptyList(),
)

/**
 * Singleton stats observable for UI screens.
 */
object StatsManager {
    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    fun update(block: StatsSnapshot.() -> StatsSnapshot) {
        _snapshot.value = _snapshot.value.block()
    }

    fun incrementBlocked() {
        _snapshot.value = _snapshot.value.copy(
            blockedToday = _snapshot.value.blockedToday + 1,
            totalTrackersBlocked = _snapshot.value.totalTrackersBlocked + 1,
        )
    }

    fun recordPacket(bytes: Long) {
        _snapshot.value = _snapshot.value.copy(
            totalPackets = _snapshot.value.totalPackets + 1,
            dataSavedBytes = _snapshot.value.dataSavedBytes + bytes,
        )
    }

    fun reset() {
        _snapshot.value = StatsSnapshot()
    }
}
