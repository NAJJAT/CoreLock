package com.privacyguard.app.ui.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.data.db.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StatisticsData(
    val totalConnections: Int = 0,
    val blockedToday: Int = 0,
    val cleartextToday: Int = 0,
    val dataTransferredToday: String = "0 MB",
    val blockRate: Float = 0f,
    val encryptionHealth: Float = 0.87f,
)

data class TrackerStats(
    val name: String,
    val company: String,
    val count: Int
)

data class BlockedDomainStat(
    val domain: String,
    val count: Int
)

data class CountryStat(
    val countryCode: String,
    val countryName: String,
    val org: String,
    val connectionCount: Int,
)

class StatisticsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)

    private val _stats = MutableStateFlow(StatisticsData())
    val stats: StateFlow<StatisticsData> = _stats.asStateFlow()

    private val _topBlockedDomains = MutableStateFlow<List<BlockedDomainStat>>(emptyList())
    val topBlockedDomains: StateFlow<List<BlockedDomainStat>> = _topBlockedDomains.asStateFlow()

    private val _highSeverityAnomalies = MutableStateFlow(0)
    val highSeverityAnomalies: StateFlow<Int> = _highSeverityAnomalies.asStateFlow()

    private val _topCountries = MutableStateFlow<List<CountryStat>>(emptyList())
    val topCountries: StateFlow<List<CountryStat>> = _topCountries.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(3_000)
            }
        }
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val todaySince = now - 24L * 60L * 60L * 1000L
        val since = now - 7L * 24L * 60L * 60L * 1000L
        val recentConnections = db.connectionDao().getRecentConnections(todaySince, 1_000)
        val totalConnections = recentConnections.size
        val blockedToday = recentConnections.count { it.wasBlocked }
        val cleartextToday = recentConnections.count {
            it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN"
        }
        val secureToday = recentConnections.count { it.encryptionStatus == "TLS" }
        val totalBytes = recentConnections.sumOf { it.bytesSent + it.bytesReceived }

        _stats.value = StatisticsData(
            totalConnections = totalConnections,
            blockedToday = blockedToday,
            cleartextToday = cleartextToday,
            dataTransferredToday = formatBytes(totalBytes),
            blockRate = if (totalConnections == 0) 0f else blockedToday.toFloat() / totalConnections.toFloat(),
            encryptionHealth = if (totalConnections == 0) 0.87f else secureToday.toFloat() / totalConnections.toFloat(),
        )

        _topBlockedDomains.value = db.connectionDao()
            .getTopBlockedDomains(since, 5)
            .map { BlockedDomainStat(it.domain, it.count) }
        _highSeverityAnomalies.value = db.dnsAnomalyDao().countHighSeverity()

        _topCountries.value = recentConnections
            .mapNotNull { GeoIpResolver.lookup(it.destinationIp) }
            .groupBy { it.countryCode }
            .map { (_, results) ->
                CountryStat(
                    countryCode     = results.first().countryCode,
                    countryName     = results.first().countryName,
                    org             = results.first().org,
                    connectionCount = results.size,
                )
            }
            .sortedByDescending { it.connectionCount }
            .take(5)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
