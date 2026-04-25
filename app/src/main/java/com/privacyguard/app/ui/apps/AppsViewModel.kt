package com.privacyguard.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.detection.StalkerwareAssessment
import com.privacyguard.app.core.detection.StalkerwareDetector
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
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
    val stalkerwareReasons: List<String> = emptyList()
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

    private suspend fun refresh() {
        val profiles   = metadataRepo.recent()
        val liveStats  = StatsManager.snapshot.value.appStats
        val names      = liveStats.associate { it.packageName to it.appName }
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
        val observed = if (profiles.isNotEmpty()) {
            // Full metadata available — use richer risk scoring from MetadataEngine
            profiles
                .groupBy { it.packageName }
                .map { (pkg, rows) ->
                    AppRiskItem(
                        appName           = names[pkg].orEmpty().ifBlank { pkg.substringAfterLast('.') },
                        packageName       = pkg,
                        totalDestinations = rows.size,
                        suspiciousCount   = rows.count { it.riskScore >= 40 },
                        cleartextCount    = rows.count { it.encryptionStatus.name == "CLEARTEXT" || it.encryptionStatus.name == "UNKNOWN" },
                        totalBytesOut     = rows.sumOf { it.totalBytesOut },
                        maxRiskScore      = rows.maxOfOrNull { it.riskScore } ?: 0,
                        isBlocked         = pkg in blockedPackages,
                        stalkerwareScore  = assessStalkerware(pkg, rows).score,
                        stalkerwareReasons = assessStalkerware(pkg, rows).reasons,
                    )
                }
                .sortedByDescending { it.maxRiskScore }
        } else {
            // VPN just started — DB not warm yet. Show live traffic from StatsManager
            // so the Apps screen is never completely blank while VPN is running.
            liveStats
                .map { stat ->
                    AppRiskItem(
                        appName           = stat.appName.ifBlank { stat.packageName.substringAfterLast('.') },
                        packageName       = stat.packageName,
                        totalDestinations = 0,
                        suspiciousCount   = 0,
                        cleartextCount    = 0,
                        totalBytesOut     = stat.bytesTransferred,
                        maxRiskScore      = if (stat.blockedCount > 0) 40 else 10,
                        isBlocked         = stat.packageName in blockedPackages,
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
            val ruleId = "pkg:block:$packageName"
            if (blocked) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = ruleId,
                        label = "Block $packageName",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchPackage = packageName
                    )
                )
            } else {
                rulesRepo.deleteRule(ruleId)
            }
            refresh()
        }
    }
}
