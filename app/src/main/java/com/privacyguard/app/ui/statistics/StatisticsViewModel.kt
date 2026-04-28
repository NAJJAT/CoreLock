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

data class SunburstOrgSlice(val org: String, val count: Int)

data class AppDataStat(val appName: String, val packageName: String, val totalBytes: Long)

// 7×24 connection count grid [dayOfWeek Mon=0][hour]
typealias HeatmapGrid = Array<IntArray>

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

    private val _heatmap = MutableStateFlow<HeatmapGrid>(Array(7) { IntArray(24) })
    val heatmap: StateFlow<HeatmapGrid> = _heatmap.asStateFlow()

    private val _sunburstOrgs = MutableStateFlow<List<SunburstOrgSlice>>(emptyList())
    val sunburstOrgs: StateFlow<List<SunburstOrgSlice>> = _sunburstOrgs.asStateFlow()

    private val _topAppsByData = MutableStateFlow<List<AppDataStat>>(emptyList())
    val topAppsByData: StateFlow<List<AppDataStat>> = _topAppsByData.asStateFlow()

    @Volatile private var cachedProfiles: List<com.privacyguard.core.metadata.ConnectionProfile> = emptyList()
    @Volatile private var cachedBehaviorSummaries: List<com.privacyguard.app.core.behavior.AppBehaviorSummary> = emptyList()

    init {
        viewModelScope.launch {
            while (true) {
                refreshSlow()
                delay(60_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5_000)
            }
        }
    }

    private suspend fun refreshSlow() {
        cachedProfiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
        cachedBehaviorSummaries = BehaviorDnaAnalyzer.summarizeAll(cachedProfiles)
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val todaySince = now - 24L * 60L * 60L * 1000L
        val since = now - 7L * 24L * 60L * 60L * 1000L
        val recentConnections = db.connectionDao().getRecentConnections(todaySince, 1_000)
        val profiles = cachedProfiles
        val behaviorSummaries = cachedBehaviorSummaries
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
            encryptionHealth = if (totalConnections == 0) 0f else secureToday.toFloat() / totalConnections.toFloat(),
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

        _topAppsByData.value = recentConnections
            .groupBy { it.packageName.ifBlank { it.appName } }
            .map { (pkg, conns) ->
                AppDataStat(
                    appName = conns.firstOrNull { it.appName.isNotBlank() }?.appName ?: pkg.substringAfterLast('.'),
                    packageName = pkg,
                    totalBytes = conns.sumOf { it.bytesSent + it.bytesReceived },
                )
            }
            .filter { it.totalBytes > 0 }
            .sortedByDescending { it.totalBytes }
            .take(6)

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

        // Build 7×24 heatmap from the full 7-day window (not just today)
        val weekConnections = db.connectionDao().getRecentConnections(since, 5_000)
        val grid = Array(7) { IntArray(24) }
        weekConnections.forEach { conn ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = conn.timestamp }
            val dayOfWeek = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0..Sun=6
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            if (dayOfWeek in 0..6 && hour in 0..23) grid[dayOfWeek][hour]++
        }
        _heatmap.value = grid

        // Sunburst: top orgs across 7 days
        _sunburstOrgs.value = weekConnections
            .mapNotNull { GeoIpResolver.lookup(it.destinationIp)?.org }
            .groupBy { it }
            .map { (org, list) -> SunburstOrgSlice(org, list.size) }
            .sortedByDescending { it.count }
            .take(12)
    }

    private val _csvExportPath = MutableStateFlow<String?>(null)
    val csvExportPath: StateFlow<String?> = _csvExportPath.asStateFlow()

    fun exportConnectionsCsv() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - 7L * 24L * 60L * 60L * 1000L
            val connections = db.connectionDao().getRecentConnections(since, 5_000)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
            val file = java.io.File(getApplication<android.app.Application>().filesDir, "connections_$stamp.csv")
            file.bufferedWriter().use { w ->
                w.write("timestamp,appName,packageName,domain,destinationIp,destinationPort,protocol,bytesSent,bytesReceived,encryptionStatus,tlsVersion,wasBlocked,wasBackground\n")
                connections.forEach { c ->
                    w.write("${c.timestamp},${c.appName.csvEscape()},${c.packageName.csvEscape()},${(c.domain ?: c.sniHostname ?: "").csvEscape()},${c.destinationIp},${c.destinationPort},${c.protocol},${c.bytesSent},${c.bytesReceived},${c.encryptionStatus},${c.tlsVersion ?: ""},${c.wasBlocked},${c.wasBackground}\n")
                }
            }
            _csvExportPath.value = file.absolutePath
        }
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

    private fun String.csvEscape(): String = "\"${replace("\"", "\"\"")}\""

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
