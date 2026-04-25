package com.privacyguard.app.ui.statistics

import android.app.Application
import com.privacyguard.app.core.report.ItReportExporter
import com.privacyguard.app.core.stats.StatsManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.behavior.BehaviorDnaAnalyzer
import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.NetworkTrustEntity
import com.privacyguard.app.data.repository.MetadataRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class StatisticsData(
    val totalConnections: Int = 0,
    val blockedToday: Int = 0,
    val cleartextToday: Int = 0,
    val dataTransferredToday: String = "0 MB",
    val blockRate: Float = 0f,
    val encryptionHealth: Float = 0.87f,
    val privacyScore: Int = 50,
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

data class ThreatTimelineItem(
    val timestamp: Long,
    val title: String,
    val subtitle: String,
    val severity: String,
)

class StatisticsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val metadataRepo = MetadataRepo(db.connectionProfileDao())

    private val _stats = MutableStateFlow(StatisticsData())
    val stats: StateFlow<StatisticsData> = _stats.asStateFlow()

    private val _topBlockedDomains = MutableStateFlow<List<BlockedDomainStat>>(emptyList())
    val topBlockedDomains: StateFlow<List<BlockedDomainStat>> = _topBlockedDomains.asStateFlow()

    private val _highSeverityAnomalies = MutableStateFlow(0)
    val highSeverityAnomalies: StateFlow<Int> = _highSeverityAnomalies.asStateFlow()

    private val _topCountries = MutableStateFlow<List<CountryStat>>(emptyList())
    val topCountries: StateFlow<List<CountryStat>> = _topCountries.asStateFlow()

    private val _timeline = MutableStateFlow<List<ThreatTimelineItem>>(emptyList())
    val timeline: StateFlow<List<ThreatTimelineItem>> = _timeline.asStateFlow()

    private val _rememberedNetworks = MutableStateFlow<List<NetworkTrustEntity>>(emptyList())
    val rememberedNetworks: StateFlow<List<NetworkTrustEntity>> = _rememberedNetworks.asStateFlow()

    private val _lastItReportPaths = MutableStateFlow<Pair<String, String>?>(null)
    val lastItReportPaths: StateFlow<Pair<String, String>?> = _lastItReportPaths.asStateFlow()

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
        val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
        val behaviorSummaries = BehaviorDnaAnalyzer.summarizeAll(profiles)
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
            privacyScore = (
                (if (totalConnections == 0) 0.5f else secureToday.toFloat() / totalConnections.toFloat()) * 50f +
                    (1f - (cleartextToday.toFloat() / totalConnections.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)) * 25f +
                    (1f - (behaviorSummaries.sumOf { it.findings.size } / 10f).coerceIn(0f, 1f)) * 25f
                ).toInt().coerceIn(0, 100),
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

        val anomalyItems = db.dnsAnomalyDao().recent(10).map {
            ThreatTimelineItem(
                timestamp = it.timestamp,
                title = it.domain,
                subtitle = "${it.packageName} · ${it.anomalyType}",
                severity = if (it.severity >= 8) "CRITICAL" else "WARN",
            )
        }
        val blockedItems = recentConnections
            .filter { it.wasBlocked }
            .take(10)
            .map {
                ThreatTimelineItem(
                    timestamp = it.timestamp,
                    title = it.domain ?: it.destinationIp,
                    subtitle = "${it.appName} · blocked ${it.destinationPort}",
                    severity = "BLOCKED",
                )
            }
        val behaviorItems = behaviorSummaries
            .flatMap { summary ->
                summary.findings.map { finding ->
                    ThreatTimelineItem(
                        timestamp = profiles.firstOrNull { it.packageName == finding.packageName && it.hostname == finding.hostname }?.lastSeen ?: now,
                        title = finding.hostname,
                        subtitle = "${finding.packageName} · ${finding.title}",
                        severity = finding.severity.label,
                    )
                }
            }
            .take(10)
        _timeline.value = (anomalyItems + blockedItems + behaviorItems)
            .sortedByDescending { it.timestamp }
            .take(20)

        _rememberedNetworks.value = db.networkTrustDao().allNetworks().take(6)
    }

    fun exportItReport() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - 24L * 60L * 60L * 1000L
            val connections = db.connectionDao().getRecentConnections(since, 500)
            val anomalies = db.dnsAnomalyDao().recent(100)
            val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
            val files = ItReportExporter.export(
                context = getApplication(),
                connections = connections,
                anomalies = anomalies,
                profiles = profiles,
                trackersBlocked = StatsManager.snapshot.value.totalTrackersBlocked,
                blocklistDomains = 0,
            )
            _lastItReportPaths.value = files.jsonFile.absolutePath to files.pdfFile.absolutePath
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
