package com.privacyguard.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.apk.ApkScanner
import com.privacyguard.app.core.stats.ActiveConnectionInfo
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.core.detection.PermissionMismatchDetector
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import com.privacyguard.app.core.detection.ObservedDestination
import com.privacyguard.app.core.tracker.DestinationOwner
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsDomainSummary
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    /** Operated by the app's own company (facebook.com for WhatsApp). */
    val firstParty: Boolean = false,
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

data class ActivityHourRow(
    val hour: Int,
    val allowed: Int,
    val blocked: Int,
    val suspicious: Int,
)

data class RecentActivityRow(
    val timestamp: Long,
    val label: String,
    val detail: String,
    val blocked: Boolean,
    val idle: Boolean,
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
    val routes: List<RoutePath> = emptyList(),
    val topologyQueryLog: List<TopologyQueryLogItem> = emptyList(),
    val detectedSdks: List<TrackerEntry> = emptyList(),
    val mismatchFindings: List<PermissionMismatchFinding> = emptyList(),
    val declaredPermissions: Set<String> = emptySet(),
    /** Sensitive permissions the user has granted, in plain language. */
    val grantedSensitive: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val isLoadingDomains: Boolean = true,
    val isLoadingMismatch: Boolean = true,
    val isBlocked: Boolean = false,
    val isBackgroundBlocked: Boolean = false,
    val hourlyActivity: List<Int> = List(24) { 0 },
    val activityTimeline: List<ActivityHourRow> = List(24) { ActivityHourRow(it, 0, 0, 0) },
    val dnsQueryCount: Int = 0,
    val idleDnsQueryCount: Int = 0,
    val connectionCount: Int = 0,
    val idleConnectionCount: Int = 0,
    val dnsDomains: List<DnsDomainSummary> = emptyList(),
    val recentActivity: List<RecentActivityRow> = emptyList(),
)

class AppDetailViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    private val packageName: String = savedState["packageName"] ?: ""
    private val appName: String     = savedState["appName"]     ?: packageName

    private val db by lazy { AppDatabase.getInstance(app) }
    // Declared above init {}, which uses it (properties initialise in order).
    // Loaded once and awaited by every analysis: the APK scan and domain refreshes can
    // finish before the permission lookup, and analysing with an empty set reported
    // "declares no permission" for apps that do (e.g. WhatsApp and contacts).
    private val permissionsLookup by lazy {
        viewModelScope.async(Dispatchers.IO) { loadPermissions(packageName) }
    }

    private val configDb by lazy { ConfigDatabase.getInstance(app) }
    private val metadataRepo by lazy { MetadataRepo(db.connectionProfileDao()) }
    private val rulesRepo by lazy { RulesRepo(configDb.rulesDao(), com.privacyguard.core.filter.FilterEngine()) }
    private val settings by lazy { com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(app) }

    private val _state = MutableStateFlow(AppDetailState(packageName = packageName, appName = appName))
    val state: StateFlow<AppDetailState> = _state.asStateFlow()

    init {
        observeTraffic()
        scanApk()
        observeBlockStatus()
    }

    private fun observeBlockStatus() {
        viewModelScope.launch {
            RuleSyncBus.version.collect {
                val rules = configDb.rulesDao().getAllRules()
                    .filter { it.matchPackage == packageName && it.action == "DENY" && it.enabled }
                _state.value = _state.value.copy(
                    isBlocked = rules.any { it.matchBackground == null || it.matchBackground == false },
                    isBackgroundBlocked = rules.any { it.matchBackground == true },
                )
            }
        }
    }

    fun toggleBackgroundBlock() {
        viewModelScope.launch {
            val ruleId = "bg:block:$packageName"
            if (_state.value.isBackgroundBlocked) {
                rulesRepo.deleteRule(ruleId)
            } else {
                rulesRepo.upsertRule(FilterRule(
                    id              = ruleId,
                    label           = "Block $packageName (background)",
                    action          = FilterRule.Action.DENY,
                    source          = FilterRule.Source.USER,
                    priority        = FilterRule.HIGH_PRIORITY,
                    matchPackage    = packageName,
                    matchBackground = true,
                ))
            }
        }
    }

    fun toggleBlock() {
        viewModelScope.launch {
            val currentlyBlocked = _state.value.isBlocked
            if (currentlyBlocked) {
                // Keep background-only deny rules intact when removing the main package block
                configDb.rulesDao().getAllRules()
                    .filter {
                        it.matchPackage == packageName &&
                        it.action == FilterRule.Action.DENY.name &&
                        it.matchBackground != true
                    }
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
                        val ip = connections.first().destinationIp
                        val owner = DestinationOwner.describe(domain.takeIf { it != ip }, ip, packageName)
                        DomainRow(
                            domain = domain,
                            count = connections.size,
                            backgroundCount = connections.count { it.wasBackground },
                            bytesSent = connections.sumOf { it.bytesSent },
                            bytesReceived = connections.sumOf { it.bytesReceived },
                            trackerName = owner.trackerName,
                            company = if (owner.trackerName != null) owner.owner else "${owner.owner} · ${owner.role.label}",
                            firstParty = owner.role == DestinationOwner.Role.FIRST_PARTY,
                        )
                    }
                    .sortedByDescending { it.count }

                val hourly = IntArray(24)
                val allowedByHour = IntArray(24)
                val blockedByHour = IntArray(24)
                val suspiciousByHour = IntArray(24)
                mergedConnections.forEach { c ->
                    val hour = java.util.Calendar.getInstance()
                        .apply { timeInMillis = c.timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
                    if (hour in 0..23) {
                        hourly[hour]++
                        when {
                            c.wasBlocked -> blockedByHour[hour]++
                            c.wasBackground -> suspiciousByHour[hour]++
                            else -> allowedByHour[hour]++
                        }
                    }
                }
                val dnsDomains = db.dnsQueryDao().topDomainsForApp(packageName, since, 10)
                val recentDns = db.dnsQueryDao().recentForApp(packageName, since, 500)
                val dnsHourly = db.dnsQueryDao().hourlyForApp(packageName, since)
                dnsHourly.forEach { bucket ->
                    if (bucket.hour in 0..23) {
                        suspiciousByHour[bucket.hour] += bucket.idleQueries
                        blockedByHour[bucket.hour] += bucket.blockedQueries
                        allowedByHour[bucket.hour] += (bucket.totalQueries - bucket.idleQueries - bucket.blockedQueries).coerceAtLeast(0)
                    }
                }
                val recentActivity = mergedConnections.take(12).map {
                    RecentActivityRow(
                        timestamp = it.timestamp,
                        label = it.domain ?: it.sniHostname ?: it.destinationIp,
                        detail = "${it.protocol} ${it.destinationPort}",
                        blocked = it.wasBlocked,
                        idle = it.wasBackground,
                    )
                }
                _state.value = _state.value.copy(
                    domains = domainRows,
                    routes = TopologyBuilder.build(
                        packageName = packageName,
                        appLabel = appName.ifBlank { packageName.substringAfterLast('.') },
                        resolverLabel = resolverLabel(),
                        connections = mergedConnections,
                        dnsQueries = recentDns,
                    ),
                    topologyQueryLog = TopologyBuilder.queryLog(recentDns),
                    isLoadingDomains = false,
                    hourlyActivity = hourly.toList(),
                    activityTimeline = (0..23).map { hour ->
                        ActivityHourRow(
                            hour = hour,
                            allowed = allowedByHour[hour],
                            blocked = blockedByHour[hour],
                            suspicious = suspiciousByHour[hour],
                        )
                    },
                    dnsQueryCount = db.dnsQueryDao().countForApp(packageName, since),
                    idleDnsQueryCount = db.dnsQueryDao().idleCountForApp(packageName, since),
                    connectionCount = mergedConnections.size,
                    idleConnectionCount = mergedConnections.count { it.wasBackground },
                    dnsDomains = dnsDomains,
                    recentActivity = recentActivity,
                )
                refreshMismatchFindings()
            }
        }
    }

    private fun scanApk() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true)
            val found = ApkScanner.scanPackage(getApplication(), packageName)
            _state.value = _state.value.copy(detectedSdks = found, isScanning = false)
            refreshMismatchFindings()
        }
    }

    /**
     * Permission findings from the current traffic: traffic the app should not be
     * able to produce ([PermissionMismatchDetector.analyze]) plus what it does with
     * the access it was granted ([PermissionMismatchDetector.exposure]).
     */
    private suspend fun refreshMismatchFindings() {
        val permissions = permissionsLookup.await()
        val current = _state.value
        val mismatches = PermissionMismatchDetector.analyze(
            appName = appName,
            packageName = packageName,
            requestedPermissions = permissions.requested,
            observedDomains = current.domains.map { it.domain },
            detectedSdks = current.detectedSdks,
            profiles = loadObservedProfiles(),
        )
        val exposure = PermissionMismatchDetector.exposure(
            appName = appName,
            grantedPermissions = permissions.granted,
            destinations = current.domains.map {
                ObservedDestination(
                    host = it.domain,
                    owner = it.company.substringBefore(" · "),
                    trackerName = it.trackerName,
                    firstParty = it.firstParty,
                    bytesSent = it.bytesSent,
                    backgroundConnections = it.backgroundCount,
                )
            },
            idleDnsQueries = current.idleDnsQueryCount,
        )
        _state.value = _state.value.copy(
            mismatchFindings = (mismatches + exposure).sortedByDescending { it.severity.rank },
            declaredPermissions = permissions.requested,
            grantedSensitive = PermissionMismatchDetector.SENSITIVE_PERMISSION_LABELS
                .filterKeys { it in permissions.granted }.values.toList(),
            isLoadingMismatch = false,
        )
    }

    private class PermissionSet(val requested: Set<String>, val granted: Set<String>)

    private fun loadPermissions(packageName: String): PermissionSet =
        runCatching {
            @Suppress("DEPRECATION")
            val info = getApplication<Application>().packageManager
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions.orEmpty()
            val flags = info.requestedPermissionsFlags ?: IntArray(0)
            val granted = requested.filterIndexed { i, _ ->
                (flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }
            PermissionSet(requested.toSet(), granted.toSet())
        }.getOrDefault(PermissionSet(emptySet(), emptySet()))

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

    /**
     * Traffic belongs to this app only when Android attributed it to this package.
     * Keyword and IP-owner matching used to pull other apps' traffic in (every
     * Meta-hosted connection showed up under WhatsApp, for one).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun matchesSelectedApp(
        observedPackage: String,
        observedAppName: String,
        observedHost: String?,
        destinationIp: String,
    ): Boolean = observedPackage == packageName

    private fun resolverLabel(): String =
        if (settings.dohEnabled.value) {
            when (settings.dohProvider.value) {
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_GOOGLE -> "Google DNS-over-HTTPS"
                com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_QUAD9 -> "Quad9 DNS-over-HTTPS"
                else -> "Cloudflare DNS-over-HTTPS"
            }
        } else {
            "${settings.upstreamDns.value} (plain DNS)"
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





