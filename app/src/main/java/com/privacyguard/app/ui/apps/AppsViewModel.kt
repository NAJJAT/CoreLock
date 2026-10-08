package com.privacyguard.app.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.apps.InstalledAppsCache
import com.privacyguard.app.core.detection.StalkerwareAssessment
import com.privacyguard.app.core.detection.StalkerwareDetector
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppSortBy { RISK, DATA, CONNECTIONS, BACKGROUND }

data class AppRiskItem(
    val appName: String,
    val packageName: String,
    val totalDestinations: Int,
    val suspiciousCount: Int,
    val cleartextCount: Int,
    val totalBytesOut: Long,
    val maxRiskScore: Int,
    val isBlocked: Boolean,
    val stalkerwareScore: Int = 0,
    val stalkerwareReasons: List<String> = emptyList(),
    val backgroundCount: Int = 0,
    /** Why the score is what it is, strongest first (plain language). */
    val riskReasons: List<String> = emptyList(),
) {
    val riskLevel: String
        get() = when {
            maxRiskScore >= 70 -> "HIGH"
            maxRiskScore >= 40 -> "MED"
            else -> "LOW"
        }

    /**
     * Letter grade from the risk score alone. The score already counts cleartext and
     * stalkerware traits; background traffic and volume count for nothing (a
     * messenger is busy in the background by design).
     */
    val privacyGrade: String
        get() = when {
            maxRiskScore < 15 -> "A"
            maxRiskScore < 40 -> "B"
            maxRiskScore < 55 -> "C"
            maxRiskScore < 70 -> "D"
            else -> "F"
        }
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val configDb = ConfigDatabase.getInstance(app)
    private val metadataRepo = MetadataRepo(db.connectionProfileDao())
    private val rulesRepo = RulesRepo(configDb.rulesDao(), FilterEngine())

    private val _apps = MutableStateFlow<List<AppRiskItem>>(emptyList())
    val apps: StateFlow<List<AppRiskItem>> = _apps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _highRiskOnly = MutableStateFlow(false)
    val highRiskOnly: StateFlow<Boolean> = _highRiskOnly.asStateFlow()

    private val _blockedOnly = MutableStateFlow(false)
    val blockedOnly: StateFlow<Boolean> = _blockedOnly.asStateFlow()

    private val _sortBy = MutableStateFlow(AppSortBy.RISK)
    val sortBy: StateFlow<AppSortBy> = _sortBy.asStateFlow()

    init {
        // Refresh live traffic only while the Apps screen is collecting, and reload
        // right away when an app is installed or removed. The installed-app list
        // itself comes from InstalledAppsCache, not from PackageManager each time.
        viewModelScope.launch {
            _apps.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .combine(InstalledAppsCache.version) { visible, _ -> visible }
                .collectLatest { visible ->
                    while (visible) {
                        try { refresh() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { }
                        delay(LIVE_REFRESH_MS)
                    }
                }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setHighRiskOnly(enabled: Boolean) {
        _highRiskOnly.value = enabled
    }

    fun setBlockedOnly(enabled: Boolean) {
        _blockedOnly.value = enabled
    }

    fun setSortBy(s: AppSortBy) {
        _sortBy.value = s
    }

    private suspend fun refresh() = withContext(Dispatchers.Default) {
        val profiles   = metadataRepo.recent()
        val liveStats  = StatsManager.snapshot.value.appStats
        val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val recentConnections = runCatching {
            db.connectionDao().getRecentConnections(since, 800)
        }.getOrElse { emptyList() }
        val level = runCatching {
            FilterEngine.BlockLevel.valueOf(
                com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(getApplication()).protectionLevel.value
            )
        }.getOrDefault(FilterEngine.BlockLevel.STANDARD)
        // User blocks, plus behavior blocks the current protection level applies.
        val activeSources = buildSet {
            add(FilterRule.Source.USER.name)
            if (level >= FilterEngine.BlockLevel.STANDARD) add(FilterRule.Source.BEHAVIOR.name)
            if (level >= FilterEngine.BlockLevel.STRICT) add(FilterRule.Source.BEHAVIOR_STRICT.name)
        }
        val blockedPackages = configDb.rulesDao()
            .getAllRules()
            .filter {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.type in activeSources &&
                    !it.matchPackage.isNullOrBlank()
            }
            .mapNotNull { it.matchPackage }
            .toSet()

        val installedApps = InstalledAppsCache.networkApps(getApplication())
        val permissionsByPackage = installedApps.associate { it.packageName to it.requestedPermissions }
        val installed = installedNetworkApps(installedApps, blockedPackages)
        val installedPackages = installed.map { it.packageName }.toSet()
        val installedNames = installed.associate { it.packageName to it.appName }
        val profileBuckets = profiles.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val liveBuckets = liveStats.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val connectionBuckets = recentConnections.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val sensorBuckets = runCatching {
            db.sensorEventDao().since(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        }.getOrDefault(emptyList()).filter { it.packageName != null }.groupBy { it.packageName!! }
        val observed = if (profiles.isNotEmpty()) {
            installed
                .mapNotNull { installedApp ->
                    val pkg = installedApp.packageName
                    val rows = profileBuckets[pkg].orEmpty()
                    val liveRows = liveBuckets[pkg].orEmpty()
                    val connectionRows = connectionBuckets[pkg].orEmpty()
                    if (rows.isEmpty() && liveRows.isEmpty() && connectionRows.isEmpty()) return@mapNotNull null
                    val stalkerware = assessStalkerware(pkg, permissionsByPackage[pkg].orEmpty(), rows)
                    val fallbackMetrics = connectionMetrics(connectionRows)
                    val risk = com.privacyguard.app.core.detection.AppRiskScorer.score(
                        com.privacyguard.app.core.detection.BehaviorBlocker.inputsFrom(
                            pkg, connectionRows, sensorBuckets[pkg].orEmpty(), purposeOf(pkg), stalkerware.score,
                        )
                    )
                    AppRiskItem(
                        appName           = installedNames[pkg].orEmpty().ifBlank { pkg.substringAfterLast('.') },
                        packageName       = pkg,
                        totalDestinations = rows.size.takeIf { it > 0 } ?: fallbackMetrics.totalDestinations,
                        suspiciousCount   = rows.count { it.riskScore >= 40 }.takeIf { it > 0 } ?: fallbackMetrics.suspiciousCount,
                        cleartextCount    = rows.count { it.encryptionStatus.name == "CLEARTEXT" }
                            .takeIf { it > 0 } ?: fallbackMetrics.cleartextCount,
                        totalBytesOut     = rows.sumOf { it.totalBytesOut } + liveRows.sumOf { it.bytesTransferred } + fallbackMetrics.totalBytes,
                        maxRiskScore      = risk.score,
                        isBlocked         = pkg in blockedPackages,
                        stalkerwareScore  = stalkerware.score,
                        stalkerwareReasons = stalkerware.reasons,
                        backgroundCount   = connectionRows.count { it.wasBackground },
                        riskReasons       = risk.reasons,
                    )
                }
                .sortedWith(compareByDescending<AppRiskItem> { it.maxRiskScore }.thenByDescending { it.totalBytesOut })
        } else {
            // VPN just started — DB not warm yet. Show live traffic from StatsManager
            // so the Apps screen is never completely blank while VPN is running.
            liveBuckets
                .map { (pkg, stats) ->
                    val displayName = installedNames[pkg].orEmpty().ifBlank {
                        stats.firstOrNull()?.appName?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
                    }
                    AppRiskItem(
                        appName           = displayName,
                        packageName       = pkg,
                        totalDestinations = 0,
                        suspiciousCount   = 0,
                        cleartextCount    = 0,
                        totalBytesOut     = stats.sumOf { it.bytesTransferred },
                        maxRiskScore      = if (stats.any { it.blockedCount > 0 }) 40 else 10,
                        isBlocked         = pkg in blockedPackages,
                        stalkerwareScore  = 0,
                    )
                }
                .sortedByDescending { it.totalBytesOut }
        }

        val observedByPackage = observed.associateBy { it.packageName }
        _apps.value = (observed + installed.filterNot { observedByPackage.containsKey(it.packageName) })
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<AppRiskItem> { it.maxRiskScore }.thenBy { it.appName.lowercase() })
    }

    private fun installedNetworkApps(
        installedApps: List<InstalledAppsCache.InstalledApp>,
        blockedPackages: Set<String>,
    ): List<AppRiskItem> = installedApps.map { app ->
        AppRiskItem(
            appName = app.appName,
            packageName = app.packageName,
            totalDestinations = 0,
            suspiciousCount = 0,
            cleartextCount = 0,
            totalBytesOut = 0L,
            maxRiskScore = 5,
            isBlocked = app.packageName in blockedPackages,
            stalkerwareScore = 0,
        )
    }

    private fun connectionMetrics(rows: List<ConnectionEntity>): ConnectionMetrics {
        if (rows.isEmpty()) return ConnectionMetrics()
        val destinations = rows
            .map { (it.sniHostname ?: it.domain ?: it.destinationIp).ifBlank { it.destinationIp } }
            .distinct()
            .size
        val suspicious = rows.count { it.wasBlocked }
        val cleartext = rows.count { it.encryptionStatus.equals("CLEARTEXT", ignoreCase = true) }
        val totalBytes = rows.sumOf { it.bytesSent + it.bytesReceived }
        val risk = when {
            suspicious > 0 -> 40
            cleartext > 0 -> 25
            else -> 12
        }
        return ConnectionMetrics(
            totalDestinations = destinations,
            suspiciousCount = suspicious,
            cleartextCount = cleartext,
            totalBytes = totalBytes,
            maxRiskScore = risk,
        )
    }

    private data class ConnectionMetrics(
        val totalDestinations: Int = 0,
        val suspiciousCount: Int = 0,
        val cleartextCount: Int = 0,
        val totalBytes: Long = 0L,
        val maxRiskScore: Int = 0,
    )

    private fun canonicalPackageForObserved(
        observedPackage: String,
        installedPackages: Set<String>,
    ): String {
        if (observedPackage in installedPackages) return observedPackage
        val preferred = preferredFamilyPackage(observedPackage, installedPackages)
        return preferred ?: observedPackage
    }

    private fun preferredFamilyPackage(
        observedPackage: String,
        installedPackages: Set<String>,
    ): String? = when {
        observedPackage.startsWith("com.facebook.") -> when {
            "com.facebook.katana" in installedPackages -> "com.facebook.katana"
            else -> installedPackages.firstOrNull { it.startsWith("com.facebook.") }
        }
        observedPackage.startsWith("com.instagram.") -> when {
            "com.instagram.android" in installedPackages -> "com.instagram.android"
            else -> installedPackages.firstOrNull { it.startsWith("com.instagram.") }
        }
        observedPackage == "com.whatsapp" -> observedPackage.takeIf { it in installedPackages }
        else -> null
    }

    private fun purposeOf(pkg: String): com.privacyguard.app.core.sensors.SensorPurpose = runCatching {
        val info = getApplication<Application>().packageManager.getApplicationInfo(pkg, 0)
        val category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) info.category else -1
        com.privacyguard.app.core.sensors.SensorPurpose.of(pkg, category, info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
    }.getOrDefault(com.privacyguard.app.core.sensors.SensorPurpose.UNKNOWN)

    private fun assessStalkerware(
        packageName: String,
        requestedPermissions: Set<String>,
        rows: List<ConnectionProfile>,
    ): StalkerwareAssessment {
        return runCatching {
            val details = InstalledAppsCache.details(getApplication(), packageName)
            StalkerwareDetector.assess(
                requestedPermissions = requestedPermissions,
                profiles = rows,
                hasLauncherIcon = details.hasLauncherIcon,
                installerPackage = details.installerPackage,
                isSystemApp = details.isSystemApp,
            )
        }.getOrDefault(StalkerwareAssessment(0, emptyList()))
    }

    fun togglePackageBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            if (blocked) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = packageBlockRuleId(packageName),
                        label = "Block $packageName",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchPackage = packageName,
                    )
                )
            } else {
                // Unblocking also tells the behavior blocker to leave this app alone.
                com.privacyguard.app.core.detection.BehaviorBlocker.neverBlock(getApplication(), packageName)
                // Delete any DENY rule matching this package, regardless of how it was created
                configDb.rulesDao().getAllRules()
                    .filter { it.matchPackage == packageName && it.action == FilterRule.Action.DENY.name }
                    .forEach { rulesRepo.deleteRule(it.id) }
            }
            refresh()
        }
    }

    companion object {
        /** Live traffic refresh while the screen is visible; installed apps are cached. */
        private const val LIVE_REFRESH_MS = 3_000L

        fun packageBlockRuleId(packageName: String) = "pkg:block:$packageName"
    }
}
