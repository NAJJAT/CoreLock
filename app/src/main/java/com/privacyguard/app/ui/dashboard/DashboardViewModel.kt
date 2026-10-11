package com.privacyguard.app.ui.dashboard

import android.app.Application
import com.privacyguard.app.R
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
import com.privacyguard.app.core.behavior.BehaviorSeverity
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.platform.android.NotificationHelper
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
    val throughputBytesPerSec: Long = 0L,
    val activeConnectionCount: Int = 0,
    val alertBadgeCount: Int = 0,
    val protectionLevel: String = "STANDARD",
    val sleepActivity: SleepActivitySummary = SleepActivitySummary(),
)

data class SleepActivitySummary(
    val activeApps: Int = 0,
    val dnsQueries: Int = 0,
    val trackingDomains: Int = 0,
)

/** Stable identity of a card: navigation and rules key off this, never the (translated) title. */
enum class CardKind { BEHAVIOR, IPV6, KILL_SWITCH, NO_DECRYPTION, ENTROPY, NIGHT, BEACON, UPLOAD, TRUST, BACKGROUND, CLEARTEXT }

data class SecurityCardState(
    val kind: CardKind,
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
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
    private fun plural(id: Int, n: Number): String = n.toInt().let { getApplication<Application>().resources.getQuantityString(id, it, it) }
    private val db = AppDatabase.getInstance(app)
    private val prefs = SettingsPreferences.getInstance(app)
    private val metadataRepo = MetadataRepo(db.connectionProfileDao())
    private val notifHelper = NotificationHelper(app)
    private val notifiedBehaviorKeys = mutableSetOf<String>()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // Cached slow-path data; refreshed every 30s to avoid heavy DB reads every 1s
    @Volatile private var cachedProfiles: List<com.privacyguard.core.metadata.ConnectionProfile> = emptyList()
    @Volatile private var cachedAnomalies: List<com.privacyguard.app.data.db.DnsAnomalyEntity> = emptyList()
    @Volatile private var cachedBehaviorSummaries: List<com.privacyguard.app.core.behavior.AppBehaviorSummary> = emptyList()
    @Volatile private var cachedBehaviorAlerts: Int = 0
    @Volatile private var cachedWeeklyConnections: List<ConnectionEntity> = emptyList()
    @Volatile private var cachedTlsAlertCount: Int = 0
    @Volatile private var cachedSleepActivity: SleepActivitySummary = SleepActivitySummary()
    @Volatile private var slowCacheReady: Boolean = false
    @Volatile private var prevActiveBytesTotal: Long = 0L

    init {
        // Fast path: live stats + connection counts — runs every 1s
        viewModelScope.launch {
            while (true) {
                try {
                    if (slowCacheReady) {
                        refreshFast()
                        delay(1_000)
                    } else {
                        delay(250)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) { delay(1_000) }
            }
        }
        // Slow path: full DB profiles + behavior DNA — runs every 30s
        viewModelScope.launch {
            while (true) {
                try {
                    refreshSlow()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) { /* log silently */ }
                delay(30_000)
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
        viewModelScope.launch { refreshFast() }
    }

    fun setProtectionLevel(level: String) {
        prefs.setProtectionLevel(level)
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
        viewModelScope.launch { refreshFast() }
    }

    private suspend fun refreshSlow() {
        val now = System.currentTimeMillis()
        val weekSince = now - 7L * 24L * 60L * 60L * 1000L
        val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
        val summaries = BehaviorDnaAnalyzer.summarizeAll(profiles)
        cachedProfiles = profiles
        cachedAnomalies = db.dnsAnomalyDao().recent(20)
        cachedBehaviorSummaries = summaries
        cachedBehaviorAlerts = summaries.sumOf { it.findings.size }
        cachedWeeklyConnections = db.connectionDao().getRecentConnections(weekSince, 2_000)
        cachedTlsAlertCount = db.tlsAlertDao().countJa3Threats() + db.tlsAlertDao().countWeakCipher()
        cachedSleepActivity = SleepActivitySummary(
            activeApps = db.dnsQueryDao().countIdleAppsSince(weekSince),
            dnsQueries = db.dnsQueryDao().countIdleSince(weekSince),
            trackingDomains = db.dnsQueryDao().countIdleBlockedDomainsSince(weekSince),
        )
        slowCacheReady = true

        // Notify once per HIGH-severity behavior finding per VPN session
        summaries.forEach { summary ->
            summary.findings
                .filter { it.severity == BehaviorSeverity.HIGH }
                .forEach { finding ->
                    val key = "${finding.packageName}|${finding.hostname}|${finding.title}"
                    if (notifiedBehaviorKeys.add(key) && prefs.notificationsEnabled.value) {
                        notifHelper.postBehaviorAlert(
                            packageName = finding.packageName,
                            title = com.privacyguard.app.ui.text.FindingText.title(getApplication(), finding),
                            summary = com.privacyguard.app.ui.text.FindingText.summary(getApplication(), finding),
                            mainActivityClass = try {
                                Class.forName("com.privacyguard.app.MainActivity")
                            } catch (_: ClassNotFoundException) {
                                DashboardViewModel::class.java
                            },
                        )
                    }
                }
        }
    }

    private suspend fun refreshFast() {
        val snapshot = StatsManager.snapshot.value
        val now = System.currentTimeMillis()
        val daySince = now - 24L * 60L * 60L * 1000L
        val recentConnections = db.connectionDao().getRecentConnections(daySince, 500)
        val weeklyConnections = cachedWeeklyConnections
        val recentAnomalies = cachedAnomalies
        val profiles = cachedProfiles
        val behaviorSummaries = cachedBehaviorSummaries
        val behaviorAlerts = cachedBehaviorAlerts

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
        val currentActiveBytes = snapshot.activeConnections.sumOf { it.bytesTransferred }
        val throughput = (currentActiveBytes - prevActiveBytesTotal).coerceAtLeast(0L)
        prevActiveBytesTotal = currentActiveBytes
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
            throughputBytesPerSec = throughput,
            activeConnectionCount = snapshot.activeConnections.size,
            alertBadgeCount = recentAnomalies.size + cachedTlsAlertCount,
            protectionLevel = prefs.protectionLevel.value,
            sleepActivity = cachedSleepActivity,
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
                kind = CardKind.BEHAVIOR,
                title = str(R.string.dash_card_behavior),
                subtitle = str(R.string.dash_card_behavior_desc),
                status = when {
                    behaviorAlerts >= 3 -> str(R.string.dash_status_anomalous)
                    behaviorAlerts > 0 -> str(R.string.dash_status_learning)
                    else -> str(R.string.dash_status_quiet)
                },
                severity = when {
                    behaviorAlerts >= 3 -> CardSeverity.CRITICAL
                    behaviorAlerts > 0 -> CardSeverity.WARNING
                    else -> CardSeverity.GOOD
                },
                value = plural(R.plurals.dash_alerts_count, behaviorAlerts),
            ),
            SecurityCardState(
                kind = CardKind.IPV6,
                title = str(R.string.dash_card_ipv6),
                subtitle = str(R.string.dash_card_ipv6_desc),
                status = when {
                    ipv6Leak -> str(R.string.dash_status_critical)
                    blockedIpv6Packets > 0 -> str(R.string.dash_status_blocked)
                    else -> str(R.string.dash_status_contained)
                },
                severity = if (ipv6Leak) CardSeverity.CRITICAL else CardSeverity.GOOD,
                value = if (blockedIpv6Packets > 0) plural(R.plurals.dash_blocked_count, blockedIpv6Packets) else plural(R.plurals.dash_ipv6_count, ipv6Sessions),
            ),
            SecurityCardState(
                kind = CardKind.KILL_SWITCH,
                title = str(R.string.dash_card_kill_switch),
                subtitle = if (KillSwitch.lockdownActive) str(R.string.set_kill_switch_on)
                    else str(R.string.dash_kill_switch_off_desc),
                status = when {
                    KillSwitch.lockdownActive -> str(R.string.dash_status_blocking)
                    prefs.killSwitchEnabled.value -> str(R.string.dash_status_alert_only)
                    else -> str(R.string.diag_off)
                },
                severity = if (KillSwitch.lockdownActive) CardSeverity.GOOD else CardSeverity.WARNING,
                value = if (PrivacyVpnService.isRunning) str(R.string.dash_vpn_live) else str(R.string.dash_vpn_off),
            ),
            SecurityCardState(
                kind = CardKind.NO_DECRYPTION,
                title = str(R.string.dash_card_no_decrypt),
                subtitle = str(R.string.dash_card_no_decrypt_desc),
                status = str(R.string.dash_status_verified),
                severity = CardSeverity.GOOD,
                value = str(R.string.dash_headers_only),
            ),
            SecurityCardState(
                kind = CardKind.ENTROPY,
                title = str(R.string.dash_card_entropy),
                subtitle = str(R.string.dash_card_entropy_desc),
                status = if (highEntropy > 0) str(R.string.dash_status_high_value) else str(R.string.dash_status_normal),
                severity = if (highEntropy > 0) CardSeverity.CRITICAL else CardSeverity.INFO,
                value = plural(R.plurals.dash_signals_count, highEntropy),
            ),
            SecurityCardState(
                kind = CardKind.NIGHT,
                title = str(R.string.dash_card_night),
                subtitle = str(R.string.dash_card_night_desc),
                status = if (nightConnections > 0) str(R.string.dash_status_review) else str(R.string.dash_status_quiet),
                severity = if (nightConnections > 0) CardSeverity.WARNING else CardSeverity.GOOD,
                value = plural(R.plurals.dash_sessions_count, nightConnections),
            ),
            SecurityCardState(
                kind = CardKind.BEACON,
                title = str(R.string.dash_card_beacon),
                subtitle = str(R.string.dash_card_beacon_desc),
                status = if (beaconDomains > 0) str(R.string.dash_status_apt) else str(R.string.dash_status_stable),
                severity = if (beaconDomains > 0) CardSeverity.CRITICAL else CardSeverity.GOOD,
                value = plural(R.plurals.dash_patterns_count, beaconDomains),
            ),
            SecurityCardState(
                kind = CardKind.UPLOAD,
                title = str(R.string.dash_card_upload),
                subtitle = str(R.string.dash_card_upload_desc),
                status = if (largeUpload >= LARGE_UPLOAD_BYTES) str(R.string.dash_status_high_value) else str(R.string.dash_status_normal),
                severity = if (largeUpload >= LARGE_UPLOAD_BYTES) CardSeverity.CRITICAL else CardSeverity.INFO,
                value = formatBytes(largeUpload),
            ),
            SecurityCardState(
                kind = CardKind.TRUST,
                title = str(R.string.dash_card_trust),
                subtitle = trustSummary.networkLabel,
                status = str(when (trustSummary.level) {
                    "Reduced" -> R.string.dash_trust_reduced
                    "Watch" -> R.string.dash_trust_watch
                    else -> R.string.dash_trust_trusted
                }),
                severity = when (trustSummary.level) {
                    "Reduced" -> CardSeverity.WARNING
                    "Watch" -> CardSeverity.INFO
                    else -> CardSeverity.GOOD
                },
                value = "${trustSummary.score}/100",
            ),
            SecurityCardState(
                kind = CardKind.BACKGROUND,
                title = str(R.string.dash_card_background),
                subtitle = str(R.string.dash_card_background_desc),
                status = when {
                    backgroundApps >= 5 -> str(R.string.dash_status_high)
                    backgroundApps > 0  -> str(R.string.dash_status_moderate)
                    else                -> str(R.string.dash_status_quiet)
                },
                severity = when {
                    backgroundApps >= 5 -> CardSeverity.WARNING
                    backgroundApps > 0  -> CardSeverity.INFO
                    else                -> CardSeverity.GOOD
                },
                value = str(R.string.dash_background_value, plural(R.plurals.dash_connections_count, backgroundCount), plural(R.plurals.dash_apps_count, backgroundApps)),
            ),
            SecurityCardState(
                kind = CardKind.CLEARTEXT,
                title = str(R.string.dash_card_cleartext),
                subtitle = str(R.string.dash_card_cleartext_desc),
                status = when {
                    cleartextUnblocked >= 10 -> str(R.string.dash_status_elevated)
                    cleartextUnblocked > 0   -> str(R.string.dash_status_present)
                    else                     -> str(R.string.dash_status_none)
                },
                severity = when {
                    cleartextUnblocked >= 10 -> CardSeverity.CRITICAL
                    cleartextUnblocked > 0   -> CardSeverity.WARNING
                    else                     -> CardSeverity.GOOD
                },
                value = plural(R.plurals.dash_today_count, cleartextUnblocked),
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
        if (cards.any { it.kind == CardKind.KILL_SWITCH && it.severity == CardSeverity.WARNING }) {
            notes += str(R.string.dash_rec_kill_switch)
        }
        if (behaviorAlerts > 0) {
            notes += plural(R.plurals.dash_rec_behavior, behaviorAlerts)
        }
        if (cards.any { it.kind == CardKind.IPV6 && it.severity == CardSeverity.CRITICAL }) {
            notes += str(R.string.dash_rec_ipv6)
        }
        if (cards.any { it.kind == CardKind.ENTROPY && it.severity == CardSeverity.CRITICAL }) {
            notes += str(R.string.dash_rec_entropy)
        }
        if (cards.any { it.kind == CardKind.BEACON && it.severity == CardSeverity.CRITICAL }) {
            notes += str(R.string.dash_rec_beacon)
        }
        if (trustSummary.level == "Reduced") {
            notes += str(R.string.dash_rec_strict, trustSummary.networkLabel, trustSummary.score)
        }
        if (notes.isEmpty()) {
            notes += str(R.string.dash_rec_default)
            notes += str(R.string.dash_rec_no_decrypt)
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

