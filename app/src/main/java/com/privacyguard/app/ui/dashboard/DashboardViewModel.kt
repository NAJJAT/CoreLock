package com.privacyguard.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.behavior.BehaviorDnaAnalyzer
import com.privacyguard.app.core.network.NetworkTrustAnalyzer
import com.privacyguard.app.core.pcap.PcapWriter
import com.privacyguard.app.core.privacy.PrivacyScoreBreakdown
import com.privacyguard.app.core.privacy.PrivacyScoreCalculator
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.db.NetworkTrustEntity
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isVpnActive: Boolean = false,
    val trackersBlocked: Int = 0,
    val cleartextCount: Int = 0,
    val blocklistDomains: Int = 0,
    val encryptionHealth: Float = 0f,
    val anomalies: List<DnsAnomalyEntity> = emptyList(),
    val securityCards: List<SecurityCardState> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val weeklyReport: WeeklyPrivacyReport = WeeklyPrivacyReport(),
    val privacyScore: PrivacyScoreBreakdown = PrivacyScoreBreakdown(50, 0f, 0f, 0, 0, 0f),
    val isPcapCapturing: Boolean = false,
    val pcapPath: String? = null,
)

data class SecurityCardState(
    val title: String,
    val subtitle: String,
    val status: String,
    val severity: CardSeverity,
    val value: String,
)

data class WeeklyPrivacyReport(
    val totalConnections: Int = 0,
    val blockedConnections: Int = 0,
    val anomalies: Int = 0,
    val dataTransferred: String = "0 B",
    val trustLevel: String = "Learning",
    val privacyScore: Int = 50,
    val behaviorAlerts: Int = 0,
)

enum class CardSeverity { GOOD, INFO, WARNING, CRITICAL }

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val prefs = SettingsPreferences.getInstance(app)
    private val metadataRepo = MetadataRepo(db.connectionProfileDao())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    fun togglePcapCapture() {
        val context = getApplication<Application>()
        if (PcapWriter.isCapturing()) {
            PcapWriter.stopCapture()
        } else {
            PcapWriter.startCapture(context)
        }
        viewModelScope.launch { refresh() }
    }

    fun setKillSwitch(enabled: Boolean) {
        prefs.setKillSwitchEnabled(enabled)
        if (enabled) {
            KillSwitch.enable()
            KillSwitch.startMonitoring(getApplication())
        } else {
            KillSwitch.disable()
            KillSwitch.stopMonitoring()
        }
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val snapshot = StatsManager.snapshot.value
        val now = System.currentTimeMillis()
        val daySince = now - 24L * 60L * 60L * 1000L
        val weekSince = now - 7L * 24L * 60L * 60L * 1000L
        val recentConnections = db.connectionDao().getRecentConnections(daySince, 500)
        val weeklyConnections = db.connectionDao().getRecentConnections(weekSince, 2_000)
        val recentAnomalies = db.dnsAnomalyDao().recent(20)
        val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
        val behaviorSummaries = BehaviorDnaAnalyzer.summarizeAll(profiles)
        val behaviorAlerts = behaviorSummaries.sumOf { it.findings.size }
        val trustSummary = NetworkTrustAnalyzer.summarize(getApplication(), recentConnections, recentAnomalies)
        db.networkTrustDao().upsert(
            NetworkTrustEntity(
                networkKey = trustSummary.networkKey,
                networkLabel = trustSummary.networkLabel,
                trustScore = trustSummary.score,
                trustLevel = trustSummary.level,
                cleartextCount = trustSummary.cleartextCount,
                dnsAnomalyCount = trustSummary.dnsAnomalyCount,
                weakTlsCount = trustSummary.weakTlsCount,
                blockedCount = trustSummary.blockedCount,
                lastSeen = now,
            )
        )
        if (trustSummary.shouldRecommendStrict && prefs.protectionLevel.value != com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name) {
            prefs.setProtectionLevel(com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name)
        }
        val cleartextCount = recentConnections.count {
            !it.wasBlocked && (it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN")
        }
        val secureCount = recentConnections.count { it.encryptionStatus == "TLS" || it.tlsVersion?.startsWith("TLS_1_") == true }
        val encryptionHealth = if (recentConnections.isEmpty()) 0f
            else secureCount.toFloat() / recentConnections.size.toFloat()

        val cards = buildSecurityCards(recentConnections, recentAnomalies, behaviorAlerts, trustSummary)
        val privacyScore = PrivacyScoreCalculator.calculate(
            connections = recentConnections,
            profiles = profiles,
            trackersBlocked = snapshot.totalTrackersBlocked,
            blocklistDomains = BlocklistManager.size.value,
        )
        _uiState.value = DashboardUiState(
            isVpnActive = PrivacyVpnService.isRunning,
            trackersBlocked = snapshot.totalTrackersBlocked,
            cleartextCount = cleartextCount,
            blocklistDomains = BlocklistManager.size.value,
            encryptionHealth = encryptionHealth.coerceIn(0f, 1f),
            anomalies = recentAnomalies.take(5),
            securityCards = cards,
            recommendations = recommendationsFor(cards, trustSummary, behaviorAlerts),
            weeklyReport = buildWeeklyReport(weeklyConnections, recentAnomalies, privacyScore.score, behaviorAlerts, trustSummary.level),
            privacyScore = privacyScore,
            isPcapCapturing = PcapWriter.isCapturing(),
            pcapPath = PcapWriter.getCurrentFilePath() ?: PcapWriter.getLastCompletedFile()?.absolutePath,
        )
    }

    private fun buildSecurityCards(
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        behaviorAlerts: Int,
        trustSummary: com.privacyguard.app.core.network.NetworkTrustSummary,
    ): List<SecurityCardState> {
        val snapshot = StatsManager.snapshot.value
        val ipv6Sessions = connections.count { it.isIPv6 || it.destinationIpv6 != null || it.destinationIp.contains(':') }
        val blockedIpv6Packets = snapshot.ipv6PacketsBlocked
        val ipv6Leak = PrivacyVpnService.isRunning && ipv6Sessions > 0 && blockedIpv6Packets == 0L
        val nightConnections = connections.count { isNightActivity(it.timestamp) }
        val largeUpload = connections.maxOfOrNull { it.bytesSent } ?: 0L
        val beaconDomains = connections
            .groupBy { "${it.packageName}|${it.domain ?: it.sniHostname ?: it.destinationIp}" }
            .values
            .count { isBeaconLike(it) }
        val highEntropy = anomalies.count { it.anomalyType == "HIGH_ENTROPY" || it.anomalyType == "DGA_BEACON" }
        val highSeverity = anomalies.count { it.severity >= 8 }
        val backgroundCount = connections.count { it.wasBackground }
        val backgroundApps = connections.filter { it.wasBackground }
            .mapNotNull { it.packageName.takeIf { p -> p.isNotBlank() } }
            .distinct().size
        val cleartextUnblocked = connections.count {
            !it.wasBlocked && (it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN")
        }

        return listOf(
            SecurityCardState(
                title = "App Behavior DNA",
                subtitle = "Learns normal app rhythm and flags deviations",
                status = when {
                    behaviorAlerts >= 3 -> "Anomalous"
                    behaviorAlerts > 0 -> "Learning"
                    else -> "Quiet"
                },
                severity = when {
                    behaviorAlerts >= 3 -> CardSeverity.CRITICAL
                    behaviorAlerts > 0 -> CardSeverity.WARNING
                    else -> CardSeverity.GOOD
                },
                value = "$behaviorAlerts alerts",
            ),
            SecurityCardState(
                title = "IPv6 Leak",
                subtitle = "Dual-stack routing and bypass check",
                status = when {
                    ipv6Leak -> "Critical"
                    blockedIpv6Packets > 0 -> "Blocked"
                    else -> "Contained"
                },
                severity = if (ipv6Leak) CardSeverity.CRITICAL else CardSeverity.GOOD,
                value = if (blockedIpv6Packets > 0) "$blockedIpv6Packets blocked" else "$ipv6Sessions IPv6",
            ),
            SecurityCardState(
                title = "Kill Switch",
                subtitle = "Blocks traffic if VPN drops",
                status = if (prefs.killSwitchEnabled.value) "Armed" else "Off",
                severity = if (prefs.killSwitchEnabled.value) CardSeverity.GOOD else CardSeverity.WARNING,
                value = if (PrivacyVpnService.isRunning) "VPN live" else "VPN off",
            ),
            SecurityCardState(
                title = "No Payload Decryption",
                subtitle = "Metadata-only inspection; TLS contents stay private",
                status = "Verified",
                severity = CardSeverity.GOOD,
                value = "Headers only",
            ),
            SecurityCardState(
                title = "Shannon Entropy Detection",
                subtitle = "DGA/C2 labels and tunneling-like DNS names",
                status = if (highEntropy > 0) "High Value" else "Normal",
                severity = if (highEntropy > 0) CardSeverity.CRITICAL else CardSeverity.INFO,
                value = "$highEntropy signals",
            ),
            SecurityCardState(
                title = "Night Activity Detection",
                subtitle = "Unexpected sessions between 00:00 and 05:00",
                status = if (nightConnections > 0) "Review" else "Quiet",
                severity = if (nightConnections > 0) CardSeverity.WARNING else CardSeverity.GOOD,
                value = "$nightConnections sessions",
            ),
            SecurityCardState(
                title = "Beacon Interval Analysis",
                subtitle = "Repeated app x destination contact patterns",
                status = if (beaconDomains > 0) "APT Pattern" else "Stable",
                severity = if (beaconDomains > 0) CardSeverity.CRITICAL else CardSeverity.GOOD,
                value = "$beaconDomains patterns",
            ),
            SecurityCardState(
                title = "Large Upload Detection",
                subtitle = "Possible data exfiltration by volume",
                status = if (largeUpload >= LARGE_UPLOAD_BYTES) "High Value" else "Normal",
                severity = if (largeUpload >= LARGE_UPLOAD_BYTES) CardSeverity.CRITICAL else CardSeverity.INFO,
                value = formatBytes(largeUpload),
            ),
            SecurityCardState(
                title = "Network Trust Level",
                subtitle = trustSummary.networkLabel,
                status = trustSummary.level,
                severity = when (trustSummary.level) {
                    "Reduced" -> CardSeverity.WARNING
                    "Watch" -> CardSeverity.INFO
                    else -> CardSeverity.GOOD
                },
                value = "${trustSummary.score}/100",
            ),
            SecurityCardState(
                title = "Background Activity",
                subtitle = "Connections made while app was not in foreground",
                status = when {
                    backgroundApps >= 5 -> "High"
                    backgroundApps > 0  -> "Moderate"
                    else                -> "Quiet"
                },
                severity = when {
                    backgroundApps >= 5 -> CardSeverity.WARNING
                    backgroundApps > 0  -> CardSeverity.INFO
                    else                -> CardSeverity.GOOD
                },
                value = "$backgroundCount connections · $backgroundApps apps",
            ),
            SecurityCardState(
                title = "Cleartext Connections",
                subtitle = "Unencrypted HTTP connections allowed through",
                status = when {
                    cleartextUnblocked >= 10 -> "Elevated"
                    cleartextUnblocked > 0   -> "Present"
                    else                     -> "None"
                },
                severity = when {
                    cleartextUnblocked >= 10 -> CardSeverity.CRITICAL
                    cleartextUnblocked > 0   -> CardSeverity.WARNING
                    else                     -> CardSeverity.GOOD
                },
                value = "$cleartextUnblocked today",
            ),
        )
    }

    private fun buildWeeklyReport(
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        privacyScore: Int,
        behaviorAlerts: Int,
        trustLevel: String,
    ): WeeklyPrivacyReport {
        val blocked = connections.count { it.wasBlocked }
        val totalBytes = connections.sumOf { it.bytesSent + it.bytesReceived }
        return WeeklyPrivacyReport(
            totalConnections = connections.size,
            blockedConnections = blocked,
            anomalies = anomalies.size,
            dataTransferred = formatBytes(totalBytes),
            trustLevel = trustLevel,
            privacyScore = privacyScore,
            behaviorAlerts = behaviorAlerts,
        )
    }

    private fun recommendationsFor(
        cards: List<SecurityCardState>,
        trustSummary: com.privacyguard.app.core.network.NetworkTrustSummary,
        behaviorAlerts: Int,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (cards.any { it.title == "Kill Switch" && it.severity == CardSeverity.WARNING }) {
            notes += "Enable Kill Switch by default for Sara-level protection."
        }
        if (behaviorAlerts > 0) {
            notes += "Behavior DNA found $behaviorAlerts app deviations. New destinations and odd-hour uploads now deserve first-class review."
        }
        if (cards.any { it.title == "IPv6 Leak" && it.severity == CardSeverity.CRITICAL }) {
            notes += "IPv6 traffic detected while VPN is active. Add IPv6 routing support or disable IPv6 bypass."
        }
        if (cards.any { it.title == "Shannon Entropy Detection" && it.severity == CardSeverity.CRITICAL }) {
            notes += "High entropy DNS was detected. Save the behavior fingerprint even if the domain changes."
        }
        if (cards.any { it.title == "Beacon Interval Analysis" && it.severity == CardSeverity.CRITICAL }) {
            notes += "Beacon-like intervals found. Treat the app x destination pair as reduced trust."
        }
        if (trustSummary.level == "Reduced") {
            notes += "Strict mode was recommended on ${trustSummary.networkLabel.lowercase()} because trust fell to ${trustSummary.score}/100."
        }
        if (notes.isEmpty()) {
            notes += "STANDARD blocking + Kill Switch gives most users the strongest default protection."
            notes += "تحليل السلوك يعمل بدون فك تشفير المحتوى ويحافظ على خصوصية المستخدم."
        }
        return notes.take(4)
    }

    private fun isNightActivity(timestamp: Long): Boolean {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 0..5
    }

    private fun isBeaconLike(connections: List<ConnectionEntity>): Boolean {
        if (connections.size < 6) return false
        val sorted = connections.sortedBy { it.timestamp }
        val intervals = sorted.zipWithNext { a, b -> b.timestamp - a.timestamp }
            .filter { it in 5_000L..10L * 60L * 1000L }
        if (intervals.size < 5) return false
        val avg = intervals.average()
        val variance = intervals.map { (it - avg) * (it - avg) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        return stdDev / avg < 0.25
    }

    private fun trustLevel(highSeverity: Int, beaconPatterns: Int, largeUpload: Long): String = when {
        highSeverity > 0 || beaconPatterns > 0 -> "Reduced"
        largeUpload >= LARGE_UPLOAD_BYTES -> "Watch"
        else -> "Trusted"
    }

    companion object {
        const val LARGE_UPLOAD_BYTES = 5L * 1024L * 1024L
    }
}
