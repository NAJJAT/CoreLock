package com.privacyguard.app.ui.statistics

import com.privacyguard.app.data.db.host
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
    val dataTransferredToday: String = "",
    val blockRate: Float = 0f,
    val encryptionHealth: Float = 0f,
    val privacyScore: Int = 50,
    /** The same breakdown Home uses; its factors are what the score is made of. */
    val score: com.privacyguard.app.core.privacy.PrivacyScoreBreakdown? = null,
    /** When these numbers were computed: the lists they open use the same window. */
    val at: Long = 0L,
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
    /** Largest known network owner among the connections; null when none is known. */
    val org: String?,
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

    data class DayTrend(val label: String, val total: Int, val blocked: Int)
    private val _weeklyTrend = MutableStateFlow<List<DayTrend>>(emptyList())
    val weeklyTrend: StateFlow<List<DayTrend>> = _weeklyTrend.asStateFlow()

    @Volatile private var cachedProfiles: List<com.privacyguard.core.metadata.ConnectionProfile> = emptyList()
    @Volatile private var cachedBehaviorSummaries: List<com.privacyguard.app.core.behavior.AppBehaviorSummary> = emptyList()

    init {
        viewModelScope.launch {
            while (true) {
                try { refreshSlow() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
                delay(60_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                try { refresh() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
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
        // Whole window, no LIMIT: these are totals, not a preview. Blocked attempts are
        // counted by BlockStats, so this list is the connections that went through.
        val recentConnections = db.connectionDao().between(todaySince, now)
        val blockedRows = db.connectionDao().blockedBetween(todaySince, now)
        val blocks = com.privacyguard.app.data.repository.BlockStats(db).between(todaySince, now)
        val profiles = cachedProfiles
        val behaviorSummaries = cachedBehaviorSummaries
        val level = com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(getApplication()).protectionLevel.value
        val score = com.privacyguard.app.core.privacy.PrivacyScoreCalculator.calculate(
            connections = recentConnections,
            profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) },
            blocklistDomains = com.privacyguard.app.core.privacy.PrivacyScoreCalculator.blockedDomainsForLevel(db, level),
        )

        _stats.value = StatisticsData(
            totalConnections = recentConnections.size,
            blockedToday = blocks.total,
            cleartextToday = score.cleartextConnections,
            dataTransferredToday = formatBytes(db.connectionDao().bytesBetween(todaySince, now)),
            blockRate = blocks.lookupBlockRate,
            encryptionHealth = if (recentConnections.isEmpty()) 0f else score.encryptedRatio,
            privacyScore = score.score,
            score = score,
            at = now,
        )

        // Blocking happens mostly at DNS, so the top blocked names come from blocked lookups.
        _topBlockedDomains.value = db.dnsQueryDao()
            .topBlockedDomainsSince(since, 5)
            .map { BlockedDomainStat(it.domain, it.blockedQueries) }
        // The size of the 7-day anomaly list this card opens (EvidenceKind.ANOMALIES_7D).
        _highSeverityAnomalies.value = db.dnsAnomalyDao().since(since).count { it.timestamp <= now }

        val cal = java.util.Calendar.getInstance()
        _weeklyTrend.value = (6 downTo 0).map { daysAgo ->
            cal.timeInMillis = now
            cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
            val dayStart = cal.apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dayEnd = dayStart + 86_400_000L
            val label = if (daysAgo == 0) getApplication<android.app.Application>().getString(com.privacyguard.app.R.string.stats_today)
                else java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(dayStart))
            DayTrend(
                label,
                db.connectionDao().countBetween(dayStart, dayEnd),
                com.privacyguard.app.data.repository.BlockStats(db).between(dayStart, dayEnd).total,
            )
        }

        _topAppsByData.value = recentConnections
            .groupBy { it.packageName.ifBlank { it.appName } }
            .map { (pkg, conns) ->
                AppDataStat(
                    appName = com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), pkg, conns.firstOrNull { it.appName.isNotBlank() }?.appName),
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
                    org             = results.mapNotNull { it.org }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    connectionCount = results.size,
                )
            }
            .sortedByDescending { it.connectionCount }
            .take(5)

        val anomalyItems = db.dnsAnomalyDao().recent(10).map {
            ThreatTimelineItem(
                timestamp = it.timestamp,
                title = it.domain,
                subtitle = "${com.privacyguard.app.ui.text.FindingText.appLabel(getApplication(), it.packageName)} · ${com.privacyguard.app.ui.text.FindingText.dnsAnomalyTitle(getApplication(), it.anomalyType)}",
                severity = if (it.severity >= 9) "CRITICAL" else if (it.severity >= 7) "HIGH" else "MEDIUM",
            )
        }
        val blockedItems = blockedRows
            .take(10)
            .map {
                ThreatTimelineItem(
                    timestamp = it.timestamp,
                    title = it.host ?: it.destinationIp,
                    subtitle = getApplication<android.app.Application>().getString(com.privacyguard.app.R.string.stats_blocked_port, it.appName, it.destinationPort),
                    severity = "BLOCKED",
                )
            }
        val behaviorItems = behaviorSummaries
            .flatMap { summary ->
                summary.findings.map { finding ->
                    ThreatTimelineItem(
                        timestamp = profiles.firstOrNull { it.packageName == finding.packageName && it.hostname == finding.hostname }?.lastSeen ?: now,
                        title = finding.hostname,
                        subtitle = "${com.privacyguard.app.ui.text.FindingText.appLabel(getApplication(), finding.packageName)} · ${com.privacyguard.app.ui.text.FindingText.title(getApplication(), finding)}",
                        severity = finding.severity.name,
                    )
                }
            }
            .take(10)
        _timeline.value = (anomalyItems + blockedItems + behaviorItems)
            .sortedByDescending { it.timestamp }
            .take(20)

        _rememberedNetworks.value = db.networkTrustDao().allNetworks().take(6)

        // Build 7×24 heatmap from the full 7-day window (not just today)
        val weekConnections = db.connectionDao().between(since, now)
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
            val connections = db.connectionDao().allSince(since)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
            val exportDir = java.io.File(getApplication<android.app.Application>().filesDir, "exports/csv").apply { mkdirs() }
            val file = java.io.File(exportDir, "connections_$stamp.csv")
            file.bufferedWriter().use { w ->
                w.write("timestamp,appName,packageName,domain,destinationIp,destinationPort,protocol,bytesSent,bytesReceived,encryptionStatus,tlsVersion,wasBlocked,wasBackground\n")
                connections.forEach { c ->
                    w.write("${c.timestamp},${c.appName.csvEscape()},${c.packageName.csvEscape()},${(c.host ?: "").csvEscape()},${c.destinationIp},${c.destinationPort},${c.protocol},${c.bytesSent},${c.bytesReceived},${c.encryptionStatus},${c.tlsVersion ?: ""},${c.wasBlocked},${c.wasBackground}\n")
                }
            }
            _csvExportPath.value = file.absolutePath
        }
    }

    fun exportItReport() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - 24L * 60L * 60L * 1000L
            val connections = db.connectionDao().allSince(since)
            val blocks = com.privacyguard.app.data.repository.BlockStats(db).between(since, now)
            val anomalies = db.dnsAnomalyDao().recent(100)
            val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
            val files = ItReportExporter.export(
                context = getApplication(),
                connections = connections,
                anomalies = anomalies,
                profiles = profiles,
                blocked = blocks,
                blocklistDomains = com.privacyguard.app.core.privacy.PrivacyScoreCalculator.blockedDomainsForLevel(
                    db, com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(getApplication()).protectionLevel.value),
            )
            _lastItReportPaths.value = files.jsonFile.absolutePath to files.pdfFile.absolutePath
        }
    }

    private fun String.csvEscape(): String = "\"${replace("\"", "\"\"")}\""

    private fun formatBytes(bytes: Long): String = com.privacyguard.app.core.utils.ByteFormat.format(bytes)
}
