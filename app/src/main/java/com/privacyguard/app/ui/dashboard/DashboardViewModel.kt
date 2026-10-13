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
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.app.ui.alerts.AlertFeed
import com.privacyguard.app.ui.traffic.EvidenceKind
import com.privacyguard.app.ui.traffic.HomeEvidence
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
    /** Distinct destinations behind [trackersBlocked]. */
    val blockedDomains24h: Int = 0,
    val cleartextCount: Int = 0,
    val blocklistDomains: Int = 0,
    val encryptionHealth: Float = 0f,
    val encryptedCount: Int = 0,
    val connectionCount24h: Int = 0,
    val recentAlerts: List<com.privacyguard.app.ui.alerts.AlertItem> = emptyList(),
    val securityCards: List<SecurityCardState> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val weeklyReport: WeeklyPrivacyReport = WeeklyPrivacyReport(),
    val privacyScore: PrivacyScoreBreakdown = PrivacyScoreBreakdown(score = 50, encryptedRatio = 0f, highRiskApps = 0, cleartextConnections = 0, blocklistCoverage = 0f),
    val isPcapCapturing: Boolean = false,
    val pcapPath: String? = null,
    val pcapStoppedAtLimit: Boolean = false,
    val throughputBytesPerSec: Long = 0L,
    val activeConnectionCount: Int = 0,
    val alertBadgeCount: Int = 0,
    val protectionLevel: String = "STANDARD",
    val sleepActivity: SleepActivitySummary = SleepActivitySummary(),
    /** "Switch to Strict?" for the current network; null when there is nothing to suggest. */
    val strictSuggestion: StrictSuggestion? = null,
    /**
     * When the numbers were computed. Drill-downs load their lists for the window
     * ending here, so a list always has exactly as many rows as the number showed.
     */
    val evidenceAt: Long = 0L,
)

/** Trust fell on this network; the user decides whether to switch to Strict. */
data class StrictSuggestion(val networkKey: String, val networkLabel: String, val score: Int) {
    companion object {
        /** Shown when trust is Reduced on Wi-Fi, the level is not Strict yet, and the user has not said no. */
        fun of(
            trust: com.privacyguard.app.core.network.NetworkTrustSummary,
            level: String,
            dismissed: Set<String>,
        ): StrictSuggestion? = trust.takeIf {
            it.shouldRecommendStrict &&
                level != com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name &&
                it.networkKey !in dismissed
        }?.let { StrictSuggestion(it.networkKey, it.networkLabel, it.score) }
    }
}

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
    val blockedDomains: Int = 0,
    val anomalies: Int = 0,
    val dataTransferred: String = "0 B",
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
    private val evidence = HomeEvidence(db)
    private val alertFeed = AlertFeed(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    @Volatile private var slowCacheReady: Boolean = false

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
        if (level == com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name) {
            _uiState.value = _uiState.value.copy(strictSuggestion = null)
        }
        // Recompute now, not at the next 15-second refresh: leaving Strict brings the
        // suggestion back at once, so it can be dismissed right away.
        viewModelScope.launch { runCatching { refreshSlow() } }
    }

    fun acceptStrictSuggestion() = setProtectionLevel(com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name)

    /** Not asked again on this network; the user can still pick Strict themselves. */
    fun dismissStrictSuggestion() {
        val suggestion = _uiState.value.strictSuggestion ?: return
        prefs.dismissStrictSuggestion(suggestion.networkKey)
        _uiState.value = _uiState.value.copy(strictSuggestion = null)
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
        // The list the Behavior card and weekly tile open, so the count matches it.
        val behaviorFindings = evidence.behaviorFindings(at)
        val behaviorAlerts = behaviorFindings.size

        val trustSummary = NetworkTrustAnalyzer.summarize(getApplication(), weekAnomalies)
        db.networkTrustDao().upsert(
            NetworkTrustEntity(
                networkKey = trustSummary.networkKey,
                networkLabel = trustSummary.networkLabel,
                trustScore = trustSummary.score,
                trustLevel = trustSummary.level,
                openWifi = trustSummary.openWifi,
                captivePortal = trustSummary.captivePortal,
                tamperedAnswers = trustSummary.tamperedAnswers,
                dnsHijacked = trustSummary.dnsHijacked,
                tlsIntercepted = trustSummary.tlsIntercepted,
                lastSeen = at,
            )
        )
        // Never change the level for the user: only suggest it.
        val strictSuggestion = StrictSuggestion.of(trustSummary, prefs.protectionLevel.value, prefs.strictSuggestionDismissed.value)
        val inspectionActive = com.privacyguard.vpn.interception.TlsInterceptionFactory.isInspectionActive(getApplication())

        val encryption = HomeEvidence.encryptionShare(day)
        // Same windows and definition as the lists they open (HomeEvidence BLOCKED_*).
        val blocks24h = com.privacyguard.app.data.repository.BlockStats(db).between(at - EvidenceKind.BLOCKED_24H.windowMs, at)
        val blocks7d = com.privacyguard.app.data.repository.BlockStats(db).between(at - EvidenceKind.BLOCKED_7D.windowMs, at)
        val cards = buildSecurityCards(day, weekAnomalies, behaviorAlerts, trustSummary, inspectionActive)
        val levelDomains = PrivacyScoreCalculator.blockedDomainsForLevel(db, prefs.protectionLevel.value)
        val privacyScore = PrivacyScoreCalculator.calculate(
            connections = day,
            profiles = profiles,
            blocklistDomains = levelDomains,
        )
        val weekly = WeeklyPrivacyReport(
            totalConnections = evidence.connectionCount(EvidenceKind.CONNECTIONS_7D, at),
            blockedConnections = blocks7d.total,
            blockedDomains = blocks7d.domains,
            anomalies = weekAnomalies.size,
            dataTransferred = formatBytes(evidence.bytes(EvidenceKind.DATA_7D, at)),
            // The score of these same 7 days, not today's.
            privacyScore = PrivacyScoreCalculator.forWindow(db, weekSince, at, levelDomains).score,
            behaviorAlerts = behaviorAlerts,
        )
        val alerts = alertFeed.loadItems()
        val sleepSince = at - SLEEP_WINDOW_MS
        val sleep = SleepActivitySummary(
            activeApps = db.dnsQueryDao().countIdleAppsBetween(sleepSince, at),
            dnsQueries = db.dnsQueryDao().countIdleBetween(sleepSince, at),
            trackingDomains = db.dnsQueryDao().countIdleBlockedDomainsBetween(sleepSince, at),
        )

        _uiState.value = _uiState.value.copy(
            trackersBlocked = blocks24h.total,
            blockedDomains24h = blocks24h.domains,
            cleartextCount = HomeEvidence.select(EvidenceKind.CLEARTEXT_24H, day).size,
            blocklistDomains = levelDomains,
            // Same share as the privacy score and Stats: of connections we could identify.
            encryptionHealth = if (day.isEmpty()) 0f else encryption.ratio,
            encryptedCount = encryption.encrypted,
            connectionCount24h = day.size,
            // The newest entries of the inbox the bell opens (and counts).
            recentAlerts = alerts.take(5),
            securityCards = cards,
            recommendations = recommendationsFor(cards, trustSummary, behaviorAlerts, inspectionActive, strictSuggestion != null),
            weeklyReport = weekly,
            privacyScore = privacyScore,
            alertBadgeCount = alerts.size,
            sleepActivity = sleep,
            strictSuggestion = strictSuggestion,
            evidenceAt = at,
        )
        slowCacheReady = true
    }

    private fun refreshFast() {
        val snapshot = StatsManager.snapshot.value
        _uiState.value = _uiState.value.copy(
            isVpnActive = PrivacyVpnService.isRunning,
            isPcapCapturing = PcapWriter.isCapturing(),
            pcapPath = PcapWriter.getCurrentFilePath() ?: PcapWriter.lastCapture(getApplication())?.absolutePath,
            pcapStoppedAtLimit = PcapWriter.stoppedAtLimit,
            throughputBytesPerSec = snapshot.throughputBytesPerSec,
            // Same rows as the Traffic tab it opens, which hides PrivacyGuard's own sessions.
            activeConnectionCount = snapshot.activeConnections.count { !it.packageName.startsWith("com.privacyguard.app") },
            protectionLevel = prefs.protectionLevel.value,
        )
    }

    private fun buildSecurityCards(
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        behaviorAlerts: Int,
        trustSummary: com.privacyguard.app.core.network.NetworkTrustSummary,
        inspectionActive: Boolean,
    ): List<SecurityCardState> {
        // Each value below is the size of (or read from) the list its card opens.
        val snapshot = StatsManager.snapshot.value
        val ipv6Sessions = HomeEvidence.select(EvidenceKind.IPV6_24H, connections).size
        val nightConnections = HomeEvidence.select(EvidenceKind.NIGHT_24H, connections).size
        val largeUpload = HomeEvidence.select(EvidenceKind.UPLOADS_24H, connections).firstOrNull()?.bytesSent ?: 0L
        val beaconDomains = HomeEvidence.beacons(connections).size
        val highEntropy = anomalies.count { it.anomalyType == "HIGH_ENTROPY" }
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
                // Findings are changes to review (medium at most), not confirmed threats.
                status = if (behaviorAlerts > 0) str(R.string.dash_status_review) else str(R.string.dash_status_quiet),
                severity = if (behaviorAlerts > 0) CardSeverity.WARNING else CardSeverity.GOOD,
                value = plural(R.plurals.dash_alerts_count, behaviorAlerts),
                evidence = EvidenceKind.BEHAVIOR_7D,
            ),
            SecurityCardState(
                kind = CardKind.IPV6,
                title = str(R.string.dash_card_ipv6),
                subtitle = str(R.string.dash_card_ipv6_desc),
                // IPv6 goes through the tunnel like IPv4; this counts it, it is not a leak.
                status = str(R.string.dash_status_tunnelled),
                severity = CardSeverity.GOOD,
                value = plural(R.plurals.dash_ipv6_count, ipv6Sessions),
                evidence = EvidenceKind.IPV6_24H,
            ),
            SecurityCardState(
                kind = CardKind.KILL_SWITCH,
                title = str(R.string.dash_card_kill_switch),
                subtitle = if (KillSwitch.lockdownActive) str(R.string.set_kill_switch_on)
                    else str(R.string.dash_kill_switch_off_desc),
                // Android only reports lockdown to the running VPN, so off = not checked.

                status = when {
                    KillSwitch.lockdownActive -> str(R.string.dash_status_blocking)
                    prefs.killSwitchEnabled.value -> str(R.string.dash_status_alert_only)
                    else -> str(R.string.diag_off)
                },
                severity = if (KillSwitch.lockdownActive) CardSeverity.GOOD else CardSeverity.WARNING,
                value = when {
                    !PrivacyVpnService.isRunning -> str(R.string.dash_ks_unchecked)
                    KillSwitch.lockdownActive -> str(R.string.dash_ks_lockdown_on)
                    else -> str(R.string.dash_ks_lockdown_off)
                },
            ),
            // Read from the inspection setting itself, never assumed: enterprise builds
            // with HTTPS inspection on do decrypt traffic.
            DecryptionCard.of(inspectionActive).let { d ->
                SecurityCardState(
                    kind = CardKind.NO_DECRYPTION,
                    title = str(d.title), subtitle = str(d.subtitle), status = str(d.status),
                    severity = d.severity, value = str(d.value),
                )
            },
            SecurityCardState(
                kind = CardKind.ENTROPY,
                title = str(R.string.dash_card_entropy),
                subtitle = str(R.string.dash_card_entropy_desc),
                status = if (highEntropy > 0) str(R.string.dash_status_review) else str(R.string.dash_status_normal),
                // CDNs and ad networks use random-looking names too: worth a look, not an alarm.
                severity = if (highEntropy > 0) CardSeverity.WARNING else CardSeverity.INFO,
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
                // A timer worth reviewing, not proof of malware.
                severity = if (beaconDomains > 0) CardSeverity.WARNING else CardSeverity.GOOD,
                value = plural(R.plurals.dash_patterns_count, beaconDomains),
                evidence = EvidenceKind.BEACONS_24H,
            ),
            SecurityCardState(
                kind = CardKind.UPLOAD,
                title = str(R.string.dash_card_upload),
                subtitle = str(R.string.dash_card_upload_desc),
                status = if (largeUpload >= LARGE_UPLOAD_BYTES) str(R.string.dash_status_review) else str(R.string.dash_status_normal),
                severity = if (largeUpload >= LARGE_UPLOAD_BYTES) CardSeverity.WARNING else CardSeverity.INFO,
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
        inspectionActive: Boolean,
        strictSuggested: Boolean,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (cards.any { it.kind == CardKind.KILL_SWITCH && it.severity == CardSeverity.WARNING }) {
            notes += str(R.string.dash_rec_kill_switch)
        }
        if (behaviorAlerts > 0) {
            notes += plural(R.plurals.dash_rec_behavior, behaviorAlerts)
        }
        if (cards.any { it.kind == CardKind.ENTROPY && it.severity == CardSeverity.WARNING }) {
            notes += str(R.string.dash_rec_entropy)
        }
        if (cards.any { it.kind == CardKind.BEACON && it.severity == CardSeverity.WARNING }) {
            notes += str(R.string.dash_rec_beacon)
        }
        // The suggestion card already asks; say it here only when it is not showing.
        if (trustSummary.level == "Reduced" && !strictSuggested &&
            prefs.protectionLevel.value != com.privacyguard.core.filter.FilterEngine.BlockLevel.STRICT.name) {
            notes += str(R.string.dash_rec_strict, trustSummary.networkLabel, trustSummary.score)
        }
        if (notes.isEmpty()) {
            notes += str(R.string.dash_rec_default)
            if (!inspectionActive) notes += str(R.string.dash_rec_no_decrypt)
        }
        return notes.take(4)
    }

    companion object {
        /** One background upload this big is worth a look (decimal MB, like the rest of the app). */
        const val LARGE_UPLOAD_BYTES = 5_000_000L
        /** Window of the Home "while you slept" card; the sleep report opens with the same one. */
        const val SLEEP_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }
}


/**
 * What the decryption card says. Read from the real inspection state, never assumed:
 * an enterprise build with HTTPS inspection on does decrypt traffic, and saying
 * "No payload decryption" then would be false.
 */
data class DecryptionCard(
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val subtitle: Int,
    @androidx.annotation.StringRes val status: Int,
    @androidx.annotation.StringRes val value: Int,
    val severity: CardSeverity,
) {
    companion object {
        fun of(inspectionActive: Boolean): DecryptionCard =
            if (inspectionActive) DecryptionCard(
                R.string.dash_card_decrypt_on, R.string.dash_card_decrypt_on_desc,
                R.string.dash_status_decrypting, R.string.dash_payloads, CardSeverity.WARNING,
            ) else DecryptionCard(
                R.string.dash_card_no_decrypt, R.string.dash_card_no_decrypt_desc,
                R.string.dash_status_off, R.string.dash_headers_only, CardSeverity.GOOD,
            )
    }
}
