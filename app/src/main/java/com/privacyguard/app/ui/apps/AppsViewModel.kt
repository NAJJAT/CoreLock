package com.privacyguard.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.detection.StalkerwareAssessment
import com.privacyguard.app.core.detection.StalkerwareDetector
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) {
    val riskLevel: String
        get() = when {
            maxRiskScore >= 70 -> "HIGH"
            maxRiskScore >= 40 -> "MED"
            else -> "LOW"
        }
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val packageManager = app.packageManager
    private val metadataRepo = MetadataRepo(db.connectionProfileDao())
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())

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
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(3_000)
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

    private suspend fun refresh() {
        val profiles   = metadataRepo.recent()
        val liveStats  = StatsManager.snapshot.value.appStats
        val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val recentConnections = runCatching {
            db.connectionDao().getRecentConnections(since, 800)
        }.getOrElse { emptyList() }
        val blockedPackages = db.rulesDao()
            .getAllRules()
            .filter {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.type == FilterRule.Source.USER.name &&
                    !it.matchPackage.isNullOrBlank()
            }
            .mapNotNull { it.matchPackage }
            .toSet()

        val installed = installedNetworkApps(blockedPackages)
        val installedPackages = installed.map { it.packageName }.toSet()
        val installedNames = installed.associate { it.packageName to it.appName }
        val profileBuckets = profiles.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val liveBuckets = liveStats.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val connectionBuckets = recentConnections.groupBy { canonicalPackageForObserved(it.packageName, installedPackages) }
        val observed = if (profiles.isNotEmpty()) {
            installed
                .mapNotNull { installedApp ->
                    val pkg = installedApp.packageName
                    val rows = profileBuckets[pkg].orEmpty()
                    val liveRows = liveBuckets[pkg].orEmpty()
                    val connectionRows = connectionBuckets[pkg].orEmpty()
                    if (rows.isEmpty() && liveRows.isEmpty() && connectionRows.isEmpty()) return@mapNotNull null
                    val stalkerware = assessStalkerware(pkg, rows)
                    val fallbackMetrics = connectionMetrics(connectionRows)
                    AppRiskItem(
                        appName           = installedNames[pkg].orEmpty().ifBlank { pkg.substringAfterLast('.') },
                        packageName       = pkg,
                        totalDestinations = rows.size.takeIf { it > 0 } ?: fallbackMetrics.totalDestinations,
                        suspiciousCount   = rows.count { it.riskScore >= 40 }.takeIf { it > 0 } ?: fallbackMetrics.suspiciousCount,
                        cleartextCount    = rows.count { it.encryptionStatus.name == "CLEARTEXT" || it.encryptionStatus.name == "UNKNOWN" }
                            .takeIf { it > 0 } ?: fallbackMetrics.cleartextCount,
                        totalBytesOut     = rows.sumOf { it.totalBytesOut } + liveRows.sumOf { it.bytesTransferred } + fallbackMetrics.totalBytes,
                        maxRiskScore      = (rows.maxOfOrNull { it.riskScore } ?: fallbackMetrics.maxRiskScore),
                        isBlocked         = pkg in blockedPackages,
                        stalkerwareScore  = stalkerware.score,
                        stalkerwareReasons = stalkerware.reasons,
                        backgroundCount   = connectionRows.count { it.wasBackground },
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

    private fun installedNetworkApps(blockedPackages: Set<String>): List<AppRiskItem> {
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .asSequence()
                .filter { info ->
                    info.packageName != getApplication<Application>().packageName &&
                        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
                }
                .map { info ->
                    val appName = info.applicationInfo?.loadLabel(packageManager)?.toString()
                        ?: info.packageName.substringAfterLast('.')
                    AppRiskItem(
                        appName = appName,
                        packageName = info.packageName,
                        totalDestinations = 0,
                        suspiciousCount = 0,
                        cleartextCount = 0,
                        totalBytesOut = 0L,
                        maxRiskScore = 5,
                        isBlocked = info.packageName in blockedPackages,
                        stalkerwareScore = 0,
                    )
                }
                .toList()
        }.getOrElse { emptyList() }
    }

    private fun connectionMetrics(rows: List<ConnectionEntity>): ConnectionMetrics {
        if (rows.isEmpty()) return ConnectionMetrics()
        val destinations = rows
            .map { (it.sniHostname ?: it.domain ?: it.destinationIp).ifBlank { it.destinationIp } }
            .distinct()
            .size
        val suspicious = rows.count { it.wasBlocked }
        val cleartext = rows.count { it.encryptionStatus.equals("CLEARTEXT", ignoreCase = true) || it.encryptionStatus.equals("UNKNOWN", ignoreCase = true) }
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

    private fun assessStalkerware(
        packageName: String,
        rows: List<ConnectionProfile>,
    ): StalkerwareAssessment {
        return runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            StalkerwareDetector.assess(
                requestedPermissions = info.requestedPermissions?.toSet().orEmpty(),
                profiles = rows,
                hasLauncherIcon = packageManager.getLaunchIntentForPackage(packageName) != null,
                installerPackage = runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull(),
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
                // Delete any DENY rule matching this package, regardless of how it was created
                db.rulesDao().getAllRules()
                    .filter { it.matchPackage == packageName && it.action == FilterRule.Action.DENY.name }
                    .forEach { rulesRepo.deleteRule(it.id) }
            }
            refresh()
        }
    }

    companion object {
        fun packageBlockRuleId(packageName: String) = "pkg:block:$packageName"
    }
}
