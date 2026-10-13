package com.privacyguard.app.ui.apps

import com.privacyguard.app.data.db.host
import android.app.Application
import com.privacyguard.app.R
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
    /** Owner from DomainClassifier (the classifier every screen uses); null when unknown. */
    val company: String?,
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

data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    val domains: List<DomainRow> = emptyList(),
    val detectedSdks: List<TrackerEntry> = emptyList(),
    val mismatchFindings: List<PermissionMismatchFinding> = emptyList(),
    val declaredPermissions: Set<String> = emptySet(),
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
        viewModelScope.async(Dispatchers.IO) { loadRequestedPermissions(packageName) }
    }

    private val configDb by lazy { ConfigDatabase.getInstance(app) }
    private val metadataRepo by lazy { MetadataRepo(db.connectionProfileDao()) }
    private val rulesRepo by lazy { RulesRepo(configDb.rulesDao(), com.privacyguard.core.filter.FilterEngine()) }
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
                    label           = getApplication<Application>().getString(R.string.rule_block_app_bg, appName.ifBlank { packageName }),
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
                    label        = getApplication<Application>().getString(R.string.rule_block_app, appName.ifBlank { packageName }),
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
            // Polled: the live stats change every second, the history this reads does not.
            while (true) {
                val snapshot = StatsManager.snapshot.value
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                // Only this package's own traffic. Matching by keyword ("meta", "facebook")
                // or GeoIP organisation credited other apps' connections to this one.
                val persistedConnections = runCatching {
                    db.connectionDao().forPackageBetween(packageName, since, System.currentTimeMillis())
                }.getOrElse { emptyList() }
                val liveConnections = snapshot.activeConnections
                    .filter { it.packageName == packageName }
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
                        val cls = com.privacyguard.app.core.tracker.DomainClassifier.classify(domain, packageName)
                        DomainRow(
                            domain = domain,
                            count = connections.size,
                            backgroundCount = connections.count { it.foreground == false },
                            bytesSent = connections.sumOf { it.bytesSent },
                            bytesReceived = connections.sumOf { it.bytesReceived },
                            // The app maker's own SDK in its own app is first-party, not a tracker.
                            trackerName = cls.trackerName?.takeIf { cls.purpose != com.privacyguard.app.core.tracker.DomainPurpose.FIRST_PARTY },
                            company = cls.company,
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
                            c.foreground == false || c.screenOn == false -> suspiciousByHour[hour]++
                            else -> allowedByHour[hour]++
                        }
                    }
                }
                val dnsDomains = db.dnsQueryDao().topDomainsForApp(packageName, since, 10)
                val dnsHourly = db.dnsQueryDao().hourlyForApp(packageName, since)
                // One unit per hour: connections, plus blocked lookups (which never become a
                // connection). Allowed lookups are not added: each one usually precedes a
                // connection that is already counted, so a visit would count two or three times.
                dnsHourly.forEach { bucket ->
                    if (bucket.hour in 0..23) blockedByHour[bucket.hour] += bucket.blockedQueries
                }
                val recentActivity = mergedConnections.take(12).map {
                    RecentActivityRow(
                        timestamp = it.timestamp,
                        label = it.host ?: it.destinationIp,
                        detail = "${it.protocol} ${it.destinationPort}",
                        blocked = it.wasBlocked,
                        idle = it.screenOn == false,
                    )
                }
                _state.value = _state.value.copy(
                    domains = domainRows,
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
                    idleConnectionCount = mergedConnections.count { it.foreground == false },
                    dnsDomains = dnsDomains,
                    recentActivity = recentActivity,
                )
                refreshMismatchFindings(observedDomains = domainRows.map { it.domain })
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    private fun loadMismatchSignals() {
        viewModelScope.launch {
            val requestedPermissions = permissionsLookup.await()
            val observedDomains = runCatching {
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                db.connectionDao().forPackageBetween(packageName, since, System.currentTimeMillis())
                    .mapNotNull { it.host }
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
                requestedPermissions = permissionsLookup.await(),
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
            requestedPermissions = permissionsLookup.await(),
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
            metadataRepo.profilesForApp(packageName)
        }.getOrElse { emptyList() }

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
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            timestamp = System.currentTimeMillis(),
            durationMs = 0L,
            wasBlocked = isBlocked,
            encryptionStatus = securityInfo,
            tlsVersion = encryptionInfo.takeIf { it.isNotBlank() },
            wasBackground = false,
        )
}





