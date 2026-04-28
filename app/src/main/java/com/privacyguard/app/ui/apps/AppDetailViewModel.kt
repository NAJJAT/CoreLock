package com.privacyguard.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.core.apk.ApkScanner
import com.privacyguard.app.core.stats.ActiveConnectionInfo
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.core.detection.PermissionMismatchDetector
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

data class DomainRow(
    val domain: String,
    val count: Int,
    val backgroundCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val trackerName: String?,
    val company: String,
)

data class TopologyHop(
    val label: String,
    val detail: String,
    val latencyLabel: String,
    val state: TopologyState,
    val badge: String? = null,
)

data class TopologyQueryLogItem(
    val domain: String,
    val status: String,
    val latencyLabel: String,
)

data class TopologyRouteSummary(
    val appLabel: String,
    val destinationLabel: String,
    val destinationIp: String,
    val resolverLabel: String,
    val policyLabel: String,
    val countryCode: String,
    val countryName: String,
    val org: String,
    val totalLatencyMs: Int,
    val dnsLatencyMs: Int,
    val transportLatencyMs: Int,
    val encryptionLabel: String,
)

enum class TopologyState {
    SAFE,
    RESOLVER,
    INTERCEPT,
    BLOCKED,
    NEUTRAL,
}

data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    val domains: List<DomainRow> = emptyList(),
    val routeSummary: TopologyRouteSummary? = null,
    val topologyHops: List<TopologyHop> = emptyList(),
    val topologyQueryLog: List<TopologyQueryLogItem> = emptyList(),
    val detectedSdks: List<TrackerEntry> = emptyList(),
    val mismatchFindings: List<PermissionMismatchFinding> = emptyList(),
    val declaredPermissions: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isLoadingDomains: Boolean = true,
    val isLoadingMismatch: Boolean = true,
    val isBlocked: Boolean = false,
    val hourlyActivity: List<Int> = List(24) { 0 },
)

class AppDetailViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    private val packageName: String = savedState["packageName"] ?: ""
    private val appName: String     = savedState["appName"]     ?: packageName

    private val db by lazy { AppDatabase.getInstance(app) }
    private val metadataRepo by lazy { MetadataRepo(db.connectionProfileDao()) }
    private val rulesRepo by lazy { RulesRepo(db.rulesDao(), com.privacyguard.core.filter.FilterEngine()) }
    private val settings by lazy { com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(app) }

    private val _state = MutableStateFlow(AppDetailState(packageName = packageName, appName = appName))
    val state: StateFlow<AppDetailState> = _state.asStateFlow()

    init {
        observeTraffic()
        loadMismatchSignals()
        scanApk()
        observeBlockStatus()
    }

    private fun observeBlockStatus() {
        viewModelScope.launch {
            RuleSyncBus.version.collect {
                val blocked = db.rulesDao().getAllRules().any { rule ->
                    rule.matchPackage == packageName &&
                    rule.action == "DENY" &&
                    rule.enabled
                }
                _state.value = _state.value.copy(isBlocked = blocked)
            }
        }
    }

    fun toggleBlock() {
        viewModelScope.launch {
            val currentlyBlocked = _state.value.isBlocked
            if (currentlyBlocked) {
                // Delete all DENY rules for this package regardless of how they were created
                db.rulesDao().getAllRules()
                    .filter { it.matchPackage == packageName && it.action == FilterRule.Action.DENY.name }
                    .forEach { rulesRepo.deleteRule(it.id) }
            } else {
                rulesRepo.upsertRule(FilterRule(
                    id           = AppsViewModel.packageBlockRuleId(packageName),
                    label        = "Block $packageName",
                    action       = FilterRule.Action.DENY,
                    source       = FilterRule.Source.USER,
                    priority     = FilterRule.HIGH_PRIORITY,
                    matchPackage = packageName,
                ))
            }
            _state.value = _state.value.copy(isBlocked = !currentlyBlocked)
        }
    }

    private fun observeTraffic() {
        viewModelScope.launch {
            StatsManager.snapshot.collectLatest { snapshot ->
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                val persistedConnections = runCatching {
                    db.connectionDao().getRecentConnections(since, 400)
                }.getOrElse { emptyList() }
                    .filter { matchesSelectedApp(it.packageName, it.appName, it.domain ?: it.sniHostname, it.destinationIp) }
                val liveConnections = snapshot.activeConnections
                    .filter { matchesSelectedApp(it.packageName, it.appName, it.hostName, it.destinationIp) }
                    .map { it.toConnectionEntity() }

                val mergedConnections = (liveConnections + persistedConnections)
                    .distinctBy {
                        listOf(
                            it.packageName,
                            it.destinationIp,
                            it.destinationPort,
                            it.domain ?: "",
                            it.sniHostname ?: "",
                            it.timestamp,
                        ).joinToString("|")
                    }
                    .sortedByDescending { it.timestamp }

                val domainRows = mergedConnections
                    .groupBy { (it.sniHostname ?: it.domain ?: it.destinationIp).ifBlank { it.destinationIp } }
                    .map { (domain, connections) ->
                        val tracker = TrackerDatabase.lookupByDomain(domain)
                        DomainRow(
                            domain = domain,
                            count = connections.size,
                            backgroundCount = connections.count { it.wasBackground },
                            bytesSent = connections.sumOf { it.bytesSent },
                            bytesReceived = connections.sumOf { it.bytesReceived },
                            trackerName = tracker?.name,
                            company = tracker?.company ?: TrackerDatabase.companyForDomain(domain),
                        )
                    }
                    .sortedByDescending { it.count }

                val hourly = IntArray(24)
                mergedConnections.forEach { c ->
                    val hour = java.util.Calendar.getInstance()
                        .apply { timeInMillis = c.timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
                    if (hour in 0..23) hourly[hour]++
                }
                _state.value = _state.value.copy(
                    domains = domainRows,
                    routeSummary = buildRouteSummary(domainRows, mergedConnections),
                    topologyHops = buildTopology(domainRows, mergedConnections),
                    topologyQueryLog = buildQueryLog(domainRows),
                    isLoadingDomains = false,
                    hourlyActivity = hourly.toList(),
                )
                refreshMismatchFindings(observedDomains = domainRows.map { it.domain })
            }
        }
    }

    private fun loadMismatchSignals() {
        viewModelScope.launch {
            val requestedPermissions = loadRequestedPermissions(packageName)
            val observedDomains = runCatching {
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                db.connectionDao().getRecentConnections(since, 400)
                    .filter { matchesSelectedApp(it.packageName, it.appName, it.domain ?: it.sniHostname, it.destinationIp) }
                    .mapNotNull { it.domain ?: it.sniHostname }
                    .distinct()
            }.getOrElse { emptyList() }
            val profiles = loadObservedProfiles()

            val findings = PermissionMismatchDetector.analyze(
                appName = appName,
                packageName = packageName,
                requestedPermissions = requestedPermissions,
                observedDomains = observedDomains,
                detectedSdks = _state.value.detectedSdks,
                profiles = profiles,
            )

            _state.value = _state.value.copy(
                mismatchFindings = findings,
                declaredPermissions = requestedPermissions,
                isLoadingMismatch = false,
            )
        }
    }

    private fun scanApk() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true)
            val found = ApkScanner.scanPackage(getApplication(), packageName)
            val findings = PermissionMismatchDetector.analyze(
                appName = appName,
                packageName = packageName,
                requestedPermissions = _state.value.declaredPermissions,
                observedDomains = _state.value.domains.map { it.domain },
                detectedSdks = found,
                profiles = loadObservedProfiles(),
            )
            _state.value = _state.value.copy(
                detectedSdks = found,
                mismatchFindings = findings,
                isScanning = false,
                isLoadingMismatch = false,
            )
        }
    }

    private suspend fun refreshMismatchFindings(observedDomains: List<String>) {
        val findings = PermissionMismatchDetector.analyze(
            appName = appName,
            packageName = packageName,
            requestedPermissions = _state.value.declaredPermissions,
            observedDomains = observedDomains,
            detectedSdks = _state.value.detectedSdks,
            profiles = loadObservedProfiles(),
        )
        _state.value = _state.value.copy(
            mismatchFindings = findings,
            isLoadingMismatch = false,
        )
    }

    private fun loadRequestedPermissions(packageName: String): Set<String> =
        runCatching {
            @Suppress("DEPRECATION")
            getApplication<Application>().packageManager
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

    private suspend fun loadObservedProfiles() =
        runCatching {
            metadataRepo.recent()
                .filter {
                    matchesSelectedApp(
                        observedPackage = it.packageName,
                        observedAppName = it.packageName.substringAfterLast('.'),
                        observedHost = it.hostname.ifBlank { it.sniHostname },
                        destinationIp = it.destinationIp,
                    )
                }
        }.getOrElse { emptyList() }

    private fun matchesSelectedApp(
        observedPackage: String,
        observedAppName: String,
        observedHost: String?,
        destinationIp: String,
    ): Boolean {
        if (observedPackage == packageName) return true

        val selectedFamily = packageFamily(packageName)
        val observedFamily = packageFamily(observedPackage)
        if (selectedFamily != null && observedFamily == selectedFamily) return true

        val host = observedHost.orEmpty().lowercase(Locale.ROOT)
        val app = observedAppName.lowercase(Locale.ROOT)
        val keywords = relatedKeywords(packageName)
        if (keywords.any { keyword -> host.contains(keyword) || app.contains(keyword) }) return true

        val geoOrg = GeoIpResolver.lookup(destinationIp)?.org.orEmpty().lowercase(Locale.ROOT)
        return keywords.any { keyword -> geoOrg.contains(keyword) }
    }

    private fun packageFamily(value: String): String? {
        if (value.isBlank() || !value.contains('.')) return null
        return when {
            value.startsWith("com.facebook.") -> "com.facebook"
            value.startsWith("com.instagram.") -> "com.instagram"
            value == "com.whatsapp" -> "com.whatsapp"
            else -> null
        }
    }

    private fun relatedKeywords(value: String): List<String> = when {
        value.startsWith("com.facebook.") -> listOf("facebook", "meta", "messenger", "fbcdn", "fbsbx")
        value.startsWith("com.instagram.") -> listOf("instagram", "meta", "cdninstagram")
        value == "com.whatsapp" -> listOf("whatsapp", "whatsapp.net", "meta")
        else -> listOf(value.substringAfterLast('.').lowercase(Locale.ROOT))
    }

    private fun buildQueryLog(domains: List<DomainRow>): List<TopologyQueryLogItem> =
        domains.take(8).mapIndexed { index, row ->
            val status = when {
                row.trackerName != null -> "TRACKER"
                row.domain.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) -> "IP"
                else -> "OK"
            }
            val latency = when (status) {
                "TRACKER" -> null
                "IP" -> 10 + index * 3
                else -> 8 + (index * 2) + row.count.coerceAtMost(6)
            }
            TopologyQueryLogItem(
                domain = row.domain,
                status = status,
                latencyLabel = latency?.let { "${it}ms" } ?: "--",
            )
        }

    private fun buildRouteSummary(
        domains: List<DomainRow>,
        recentConnections: List<ConnectionEntity>,
    ): TopologyRouteSummary? {
        val connection = recentConnections.firstOrNull() ?: return null
        val appLabel = appName.ifBlank { packageName.substringAfterLast('.') }
        val destinationLabel = connection.domain ?: connection.sniHostname ?: domains.firstOrNull()?.domain ?: connection.destinationIp
        val geo = GeoIpResolver.lookup(connection.destinationIp)
        val totalLatencyMs = calculateTotalLatency(recentConnections)
        val dnsLatencyMs = calculateDnsLatency(destinationLabel, recentConnections)
        val transportLatencyMs = (totalLatencyMs - dnsLatencyMs).coerceAtLeast(8)
        val resolverLabel = if (settings.dohEnabled.value) {
            when (settings.dohProvider.value) {
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_GOOGLE -> "Google DNS-over-HTTPS"
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_QUAD9 -> "Quad9 DNS-over-HTTPS"
                else -> "Cloudflare 1.1.1.1"
            }
        } else {
            "Upstream DNS ${settings.upstreamDns.value}"
        }
        val policyLabel = when {
            recentConnections.any { it.wasBlocked } || domains.any { it.trackerName != null } -> "Blocked by policy"
            else -> "Allowed by policy"
        }
        val encryption = buildString {
            append(connection.encryptionStatus)
            if (!connection.tlsVersion.isNullOrBlank()) append(" · ${connection.tlsVersion}")
        }
        return TopologyRouteSummary(
            appLabel = appLabel,
            destinationLabel = destinationLabel,
            destinationIp = connection.destinationIp,
            resolverLabel = resolverLabel,
            policyLabel = policyLabel,
            countryCode = geo?.countryCode ?: "",
            countryName = geo?.countryName ?: "Unknown region",
            org = geo?.org ?: "Unknown network",
            totalLatencyMs = totalLatencyMs,
            dnsLatencyMs = dnsLatencyMs,
            transportLatencyMs = transportLatencyMs,
            encryptionLabel = encryption,
        )
    }

    private fun buildTopology(
        domains: List<DomainRow>,
        recentConnections: List<ConnectionEntity>,
    ): List<TopologyHop> {
        val connection = recentConnections.firstOrNull()
        val domain = domains.firstOrNull()?.domain ?: connection?.domain ?: connection?.destinationIp ?: "No destination yet"
        val geo = connection?.destinationIp?.let(GeoIpResolver::lookup)
        val totalLatencyMs = calculateTotalLatency(recentConnections)
        val dnsLatencyMs = calculateDnsLatency(domain, recentConnections)
        val policyLatencyMs = (2 + recentConnections.count { it.wasBlocked }.coerceAtMost(2) + if (domains.any { it.trackerName != null }) 2 else 0)
        val resolverLatencyMs = (dnsLatencyMs * 0.65f).toInt().coerceAtLeast(8)
        val authoritativeLatencyMs = (dnsLatencyMs - resolverLatencyMs).coerceAtLeast(5)
        val transportLatencyMs = (totalLatencyMs - dnsLatencyMs - policyLatencyMs).coerceAtLeast(9)
        val resolverName = if (settings.dohEnabled.value) {
            when (settings.dohProvider.value) {
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_GOOGLE -> "Google DoH"
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_QUAD9 -> "Quad9 DoH"
                else -> "Cloudflare DoH"
            }
        } else {
            "Upstream DNS ${settings.upstreamDns.value}"
        }
        val blocked = domains.any { it.trackerName != null } || recentConnections.any { it.wasBlocked }
        val encryption = connection?.encryptionStatus ?: "UNKNOWN"
        val routeLabel = buildString {
            append(connection?.destinationIp ?: "No resolved IP")
            if (geo != null) append(" · ${geo.countryName}")
            if (!geo?.org.isNullOrBlank()) append(" · ${geo?.org}")
        }

        return listOf(
            TopologyHop(
                label = "Phone app (${appName.ifBlank { packageName.substringAfterLast('.') }})",
                detail = "$packageName · Query: $domain",
                latencyLabel = "0ms",
                state = TopologyState.SAFE,
            ),
            TopologyHop(
                label = "PrivacyGuard intercept",
                detail = "VPN layer · mismatch check · tracker/IOC inspection",
                latencyLabel = "${policyLatencyMs}ms",
                state = TopologyState.INTERCEPT,
                badge = "VPN",
            ),
            TopologyHop(
                label = if (blocked) "Policy decision" else "Policy decision — allowed",
                detail = if (blocked) {
                    "$domain · tracker, blocklist, or mismatch rule matched"
                } else {
                    "$domain · no blocklist or permission mismatch hit"
                },
                latencyLabel = if (blocked) "${policyLatencyMs + 1}ms" else "${policyLatencyMs}ms",
                state = if (blocked) TopologyState.BLOCKED else TopologyState.NEUTRAL,
                badge = if (blocked) "BLOCKED" else "ALLOWED",
            ),
            TopologyHop(
                label = resolverName,
                detail = if (settings.dohEnabled.value) {
                    "Encrypted DNS-over-HTTPS path · no cleartext resolver leak"
                } else {
                    "Classic resolver path via ${settings.upstreamDns.value}"
                },
                latencyLabel = "${resolverLatencyMs}ms",
                state = TopologyState.RESOLVER,
                badge = if (settings.dohEnabled.value) "DoH" else "DNS",
            ),
            TopologyHop(
                label = "Recursive -> Authoritative route",
                detail = "$routeLabel · root and TLD referral chain",
                latencyLabel = "${authoritativeLatencyMs}ms",
                state = TopologyState.NEUTRAL,
                badge = geo?.countryCode,
            ),
            TopologyHop(
                label = "TCP/TLS connection",
                detail = "Encryption: $encryption" + (connection?.tlsVersion?.let { " · $it" } ?: ""),
                latencyLabel = "${transportLatencyMs}ms",
                state = if (encryption == "CLEARTEXT") TopologyState.BLOCKED else TopologyState.SAFE,
                badge = connection?.protocol ?: "TCP",
            ),
        )
    }

    private fun calculateTotalLatency(recentConnections: List<ConnectionEntity>): Int {
        val durations = recentConnections.mapNotNull { it.durationMs.takeIf { ms -> ms > 0L } }
        if (durations.isEmpty()) return 44
        val avgDuration = durations.average()
        return (avgDuration / 18.0).toInt().coerceIn(24, 180)
    }

    private fun calculateDnsLatency(domain: String, recentConnections: List<ConnectionEntity>): Int {
        val base = if (settings.dohEnabled.value) 12 else 22
        val domainFactor = domain.length.coerceIn(6, 36) / 3
        val blockedFactor = recentConnections.count { it.wasBlocked }.coerceAtMost(3) * 2
        return (base + domainFactor + blockedFactor).coerceIn(8, 48)
    }

    private fun ActiveConnectionInfo.toConnectionEntity(): ConnectionEntity =
        ConnectionEntity(
            appUid = -1,
            appName = appName,
            packageName = packageName,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            destinationIpv6 = destinationIp.takeIf { it.contains(':') },
            isIPv6 = destinationIp.contains(':'),
            domain = hostName,
            sniHostname = hostName,
            protocol = protocol,
            bytesSent = bytesTransferred,
            bytesReceived = 0L,
            timestamp = System.currentTimeMillis(),
            durationMs = 0L,
            wasBlocked = isBlocked,
            encryptionStatus = securityInfo,
            tlsVersion = encryptionInfo.takeIf { it.isNotBlank() },
            wasBackground = false,
        )
}



