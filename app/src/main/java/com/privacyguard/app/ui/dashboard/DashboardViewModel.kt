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
import com.privacyguard.app.ui.alerts.AlertFeed
import com.privacyguard.app.ui.traffic.EvidenceKind
import com.privacyguard.app.ui.traffic.HomeEvidence
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isVpnActive: Boolean = false,
    /** DNS lookups blocked in the 24 h before [evidenceAt]; opens [EvidenceKind.BLOCKED_24H]. */
    val trackersBlocked: Int = 0,
    val cleartextCount: Int = 0,
    val blocklistDomains: Int = 0,
    val encryptionHealth: Float = 0f,
    val encryptedCount: Int = 0,
    val connectionCount24h: Int = 0,
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
    /**
     * When the numbers were computed. Drill-downs load their lists for the window
     * ending here, so a list always has exactly as many rows as the number showed.
     */
    val evidenceAt: Long = 0L,
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
    /** The list behind [value], or null for cards that state a setting rather than a count. */
    val evidence: EvidenceKind? = null,
)

data class WeeklyPrivacyReport(
    val totalConnections: Int = 0,
    /** DNS lookups blocked in the week (connections are never logged as blocked). */
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
    private val evidence = HomeEvidence(db)
    private val alertFeed = AlertFeed(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    @Volatile private var slowCacheReady: Boolean = false
    @Volatile private var prevActiveBytesTotal: Long = 0L

    init {
        // Fast path: live values only (VPN state, throughput, active sessions) — every 1 s.
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
        // Slow path: every number that opens a list is computed here from complete
        // queries (no LIMIT), anchored at one timestamp.
        viewModelScope.launch {
            while (true) {
                try {
                    refreshSlow()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) { /* keep the last good numbers */ }
                delay(15_000)
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
        val at = System.currentTimeMillis()
        val weekSince = at - HomeEvidence.WEEK_MS
        val day = db.connectionDao().between(at - HomeEvidence.DAY_MS, at)
        val weekAnomalies = db.dnsAnomalyDao().since(weekSince).filter { it.timestamp <= at }
        val profiles = db.connectionProfileDao().allProfiles().map { metadataRepo.toDomainForDashboard(it) }
        val summaries = BehaviorDnaAnalyzer.summarizeAll(profiles)
        val behaviorAlerts = summaries.sumOf { it.findings.size }

        val trustSummary = NetworkTrustAnalyzer.summarize(getApplication(), day, weekAnomalies)
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
                lastSeen = at,
            )
        )
        if (trustSummary.shouldRecommendStrict && prefs.protectionLevel.value != com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name) {
            prefs.setProtectionLevel(com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name)
        }

        val encrypted = day.count { HomeEvidence.isEncrypted(it) }
        val blocked24h = evidence.blockedCount(EvidenceKind.BLOCKED_24H, at)
        val cards = buildSecurityCards(day, weekAnomalies, behaviorAlerts, trustSummary)
        val privacyScore = PrivacyScoreCalculator.calculate(
            connections = day,
            profiles = profiles,
            trackersBlocked = blocked24h,
            blocklistDomains = BlocklistManager.size.value,
        )
        val weekly = WeeklyPrivacyReport(
            totalConnections = evidence.connectionCount(EvidenceKind.CONNECTIONS_7D, at),
            blockedConnections = evidence.blockedCount(EvidenceKind.BLOCKED_7D, at),
            anomalies = weekAnomalies.size,
            dataTransferred = formatBytes(evidence.bytes(EvidenceKind.DATA_7D, at)),
            trustLevel = trustSummary.level,
            privacyScore = privacyScore.score,
            behaviorAlerts = behaviorAlerts,
        )
        val sleepSince = at - SLEEP_WINDOW_MS
        val sleep = SleepActivitySummary(
            activeApps = db.dnsQueryDao().countIdleAppsBetween(sleepSince, at),
            dnsQueries = db.dnsQueryDao().countIdleBetween(sleepSince, at),
            trackingDomains = db.dnsQueryDao().countIdleBlockedDomainsBetween(sleepSince, at),
        )

        _uiState.value = _uiState.value.copy(
            trackersBlocked = blocked24h,
            cleartextCount = HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, day).size,
            blocklistDomains = BlocklistManager.size.value,
            encryptionHealth = if (day.isEmpty()) 0f else (encrypted.toFloat() / day.size).coerceIn(0f, 1f),
            encryptedCount = encrypted,
            connectionCount24h = day.size,
            anomalies = weekAnomalies.take(5),
            securityCards = cards,
            recommendations = recommendationsFor(cards, trustSummary, behaviorAlerts),
            weeklyReport = weekly,
            privacyScore = privacyScore,
            alertBadgeCount = alertFeed.loadItems().size,
            sleepActivity = sleep,
            evidenceAt = at,
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

    private fun refreshFast() {
        val snapshot = StatsManager.snapshot.value
        val currentActiveBytes = snapshot.activeConnections.sumOf { it.bytesTransferred }
        val throughput = (currentActiveBytes - prevActiveBytesTotal).coerceAtLeast(0L)
        prevActiveBytesTotal = currentActiveBytes
        _uiState.value = _uiState.value.copy(
            isVpnActive = PrivacyVpnService.isRunning,
            isPcapCapturing = PcapWriter.isCapturing(),
            pcapPath = PcapWriter.getCurrentFilePath() ?: PcapWriter.getLastCompletedFile()?.absolutePath,
            throughputBytesPerSec = throughput,
            activeConnectionCount = snapshot.activeConnections.size,
            protectionLevel = prefs.protectionLevel.value,
        )
    }

    private fun buildSecurityCards(
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        behaviorAlerts: Int,
        trustSummary: com.privacyguard.app.core.network.NetworkTrustSummary,
    ): List<SecurityCardState> {
        // Each value below is the size of (or read from) the list its card opens.
        val snapshot = StatsManager.snapshot.value
        val ipv6Sessions = HomeEvidence.select(EvidenceKind.IPV6_24H, connections).size
        val blockedIpv6Packets = snapshot.ipv6PacketsBlocked
        val nightConnections = HomeEvidence.select(EvidenceKind.NIGHT_24H, connections).size
        val largeUpload = HomeEvidence.select(EvidenceKind.UPLOADS_24H, connections).firstOrNull()?.bytesSent ?: 0L
        val beaconDomains = HomeEvidence.beacons(connections).size
        val highEntropy = anomalies.count { it.anomalyType == "HIGH_ENTROPY" || it.anomalyType == "DGA_BEACON" }
        val background = HomeEvidence.select(EvidenceKind.BACKGROUND_24H, connections)
        val backgroundCount = background.size
        val backgroundApps = background.mapNotNull { it.packageName.takeIf { p -> p.isNotBlank() } }.distinct().size
        // Only what we saw in plain text; "unknown" used to be counted as cleartext too.
        val cleartextUnblocked = HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, connections).size

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
                evidence = EvidenceKind.BEHAVIOR,
            ),
            SecurityCardState(
                kind = CardKind.IPV6,
                title = str(R.string.dash_card_ipv6),
                subtitle = str(R.string.dash_card_ipv6_desc),
                status = if (blockedIpv6Packets > 0) str(R.string.dash_status_blocked) else str(R.string.dash_status_contained),
                severity = CardSeverity.GOOD,
                value = plural(R.plurals.dash_ipv6_count, ipv6Sessions),
                evidence = EvidenceKind.IPV6_24H,
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
                evidence = EvidenceKind.ENTROPY_7D,
            ),
            SecurityCardState(
                kind = CardKind.NIGHT,
                title = str(R.string.dash_card_night),
                subtitle = str(R.string.dash_card_night_desc),
                status = if (nightConnections > 0) str(R.string.dash_status_review) else str(R.string.dash_status_quiet),
                severity = if (nightConnections > 0) CardSeverity.WARNING else CardSeverity.GOOD,
                value = plural(R.plurals.dash_sessions_count, nightConnections),
                evidence = EvidenceKind.NIGHT_24H,
            ),
            SecurityCardState(
                kind = CardKind.BEACON,
                title = str(R.string.dash_card_beacon),
                subtitle = str(R.string.dash_card_beacon_desc),
                status = if (beaconDomains > 0) str(R.string.dash_status_apt) else str(R.string.dash_status_stable),
                severity = if (beaconDomains > 0) CardSeverity.CRITICAL else CardSeverity.GOOD,
                value = plural(R.plurals.dash_patterns_count, beaconDomains),
                evidence = EvidenceKind.BEACONS_24H,
            ),
            SecurityCardState(
                kind = CardKind.UPLOAD,
                title = str(R.string.dash_card_upload),
                subtitle = str(R.string.dash_card_upload_desc),
                status = if (largeUpload >= LARGE_UPLOAD_BYTES) str(R.string.dash_status_high_value) else str(R.string.dash_status_normal),
                severity = if (largeUpload >= LARGE_UPLOAD_BYTES) CardSeverity.CRITICAL else CardSeverity.INFO,
                value = formatBytes(largeUpload),
                evidence = EvidenceKind.UPLOADS_24H,
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
                evidence = EvidenceKind.BACKGROUND_24H,
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
                evidence = EvidenceKind.CLEARTEXT_24H,
            ),
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

    companion object {
        const val LARGE_UPLOAD_BYTES = 5L * 1024L * 1024L
        /** Window of the Home "while you slept" card; the sleep report opens with the same one. */
        const val SLEEP_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

