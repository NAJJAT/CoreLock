package com.privacyguard.app.core.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ActiveConnection(
    val id: String,
    val appUid: Int,
    val appName: String,
    val packageName: String,
    val destination: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val isBlocked: Boolean,
    val bytesTransferred: Long,
    val lastSeenAt: Long
)

data class RecentActivity(
    val appName: String,
    val description: String,
    val timeMillis: Long,
    val isBlocked: Boolean
)

data class TrackerStat(
    val domain: String,
    val count: Int
)

data class AppTrafficStat(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val packets: Long,
    val blockedCount: Long,
    val bytesTransferred: Long
)

data class StatsSnapshot(
    val blockedToday: Long = 0,
    val dataSavedBytes: Long = 0,
    val totalPackets: Long = 0,
    val activeConnections: List<ActiveConnection> = emptyList(),
    val topTrackers: List<TrackerStat> = emptyList(),
    val recentActivity: List<RecentActivity> = emptyList(),
    val blocklistSize: Int = 0,
    val appStats: List<AppTrafficStat> = emptyList()
) {
    val totalTrackersBlocked: Int
        get() = topTrackers.sumOf { it.count }
}

object StatsManager {

    private val blockedToday = AtomicLong(0)
    private val dataSavedBytes = AtomicLong(0)
    private val totalPackets = AtomicLong(0)
    private val activeConnections = ConcurrentHashMap<String, ActiveConnection>()
    private val trackerCounts = ConcurrentHashMap<String, AtomicLong>()
    private val recentActivity = ArrayDeque<RecentActivity>()
    private val appStats = ConcurrentHashMap<String, AppTrafficAccumulator>()

    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    fun setBlocklistSize(size: Int) {
        publishSnapshot(blocklistSize = size)
    }

    fun recordPacket(
        appUid: Int,
        appName: String,
        packageName: String,
        destination: String,
        destinationIp: String,
        destinationPort: Int,
        protocol: String,
        bytes: Int,
        isBlocked: Boolean
    ) {
        totalPackets.incrementAndGet()

        val id = "$protocol:$destinationIp:$destinationPort"
        activeConnections[id] = ActiveConnection(
            id = id,
            appUid = appUid,
            appName = appName,
            packageName = packageName,
            destination = destination,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            protocol = protocol,
            isBlocked = isBlocked,
            bytesTransferred = (activeConnections[id]?.bytesTransferred ?: 0L) + bytes,
            lastSeenAt = System.currentTimeMillis()
        )

        val appKey = if (packageName.isBlank()) "uid:$appUid" else packageName
        val current = appStats[appKey] ?: AppTrafficAccumulator(appUid, packageName, appName)
        current.packets.incrementAndGet()
        current.bytesTransferred.addAndGet(bytes.toLong())
        appStats[appKey] = current

        publishSnapshot()
    }

    fun removeConnection(id: String) {
        activeConnections.remove(id)
        publishSnapshot()
    }

    fun recordBlock(
        domain: String,
        appUid: Int,
        appName: String,
        packageName: String,
        category: String,
        estimatedSavedBytes: Int = 12 * 1024
    ) {
        blockedToday.incrementAndGet()
        dataSavedBytes.addAndGet(estimatedSavedBytes.toLong())
        trackerCounts.getOrPut(domain) { AtomicLong(0) }.incrementAndGet()
        val appKey = if (packageName.isBlank()) "uid:$appUid" else packageName
        val current = appStats[appKey] ?: AppTrafficAccumulator(appUid, packageName, appName)
        current.blockedCount.incrementAndGet()
        appStats[appKey] = current
        appendRecentActivity(
            RecentActivity(
                appName = appName,
                description = "Blocked $category request to $domain",
                timeMillis = System.currentTimeMillis(),
                isBlocked = true
            )
        )
        publishSnapshot()
    }

    fun recordAllowedActivity(appName: String, destination: String) {
        appendRecentActivity(
            RecentActivity(
                appName = appName,
                description = "Allowed connection to $destination",
                timeMillis = System.currentTimeMillis(),
                isBlocked = false
            )
        )
        publishSnapshot()
    }

    private fun appendRecentActivity(item: RecentActivity) {
        synchronized(recentActivity) {
            recentActivity.addFirst(item)
            while (recentActivity.size > 25) {
                recentActivity.removeLast()
            }
        }
    }

    private fun publishSnapshot(blocklistSize: Int = _snapshot.value.blocklistSize) {
        val topTrackers = trackerCounts.entries
            .sortedByDescending { it.value.get() }
            .take(7)
            .map { TrackerStat(domain = it.key, count = it.value.get().toInt()) }

        val recent = synchronized(recentActivity) { recentActivity.toList() }
        val active = activeConnections.values
            .sortedByDescending { it.lastSeenAt }
            .take(30)
        val perAppStats = appStats.values
            .map {
                AppTrafficStat(
                    uid = it.uid,
                    packageName = it.packageName,
                    appName = it.appName,
                    packets = it.packets.get(),
                    blockedCount = it.blockedCount.get(),
                    bytesTransferred = it.bytesTransferred.get()
                )
            }
            .sortedByDescending { it.bytesTransferred }

        _snapshot.value = StatsSnapshot(
            blockedToday = blockedToday.get(),
            dataSavedBytes = dataSavedBytes.get(),
            totalPackets = totalPackets.get(),
            activeConnections = active,
            topTrackers = topTrackers,
            recentActivity = recent,
            blocklistSize = blocklistSize,
            appStats = perAppStats
        )
    }

    private data class AppTrafficAccumulator(
        val uid: Int,
        val packageName: String,
        val appName: String,
        val packets: AtomicLong = AtomicLong(0),
        val blockedCount: AtomicLong = AtomicLong(0),
        val bytesTransferred: AtomicLong = AtomicLong(0)
    )
}
