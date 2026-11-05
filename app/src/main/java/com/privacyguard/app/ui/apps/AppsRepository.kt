package com.privacyguard.app.ui.apps

import android.content.Context
import com.privacyguard.app.core.apps.InstalledAppsCache
import com.privacyguard.app.core.detection.StalkerwareAssessment
import com.privacyguard.app.core.detection.StalkerwareDetector
import com.privacyguard.app.core.network.ConnectivityProbes
import com.privacyguard.app.core.network.LocalNetwork
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.host
import com.privacyguard.app.data.repository.BlockStats
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.ui.home.HomeLogic
import com.privacyguard.app.ui.traffic.AppNames
import com.privacyguard.app.ui.traffic.HomeEvidence
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One app in the Apps list: the 24 h numbers 1.0 showed, plus what 1.1 adds. */
data class AppRiskItem(
    val appName: String,
    val packageName: String,
    val totalDestinations: Int,
    val suspiciousCount: Int,
    val cleartextCount: Int,
    val totalBytesOut: Long,
    val maxRiskScore: Int,
    val status: AppStatus = AppStatus.ALLOWED,
    val stalkerwareScore: Int = 0,
    val stalkerwareReasons: List<String> = emptyList(),
    val backgroundCount: Int = 0,
    /** Blocked rows since local midnight: the rows Home's "Blocked today" counts. */
    val blockedToday: Int = 0,
    /** Traffic in the last [AppsLogic.ACTIVE_WINDOW_MS], or a connection open right now. */
    val activeNow: Boolean = false,
) {
    val isBlocked: Boolean get() = status != AppStatus.ALLOWED

    val riskLevel: String
        get() = when {
            maxRiskScore >= 70 -> "HIGH"
            maxRiskScore >= 40 -> "MED"
            else -> "LOW"
        }

    /** 1.0's letter grade, unchanged; shown in App detail › Technical details. */
    val privacyGrade: String
        get() {
            var score = 100
            score -= (maxRiskScore * 0.4f).toInt().coerceAtMost(40)
            score -= (cleartextCount * 5).coerceAtMost(20)
            score -= (backgroundCount * 2).coerceAtMost(10)
            score -= (stalkerwareScore * 0.3f).toInt().coerceAtMost(30)
            return when (score.coerceAtLeast(0)) {
                in 80..100 -> "A"
                in 60..79  -> "B"
                in 40..59  -> "C"
                in 20..39  -> "D"
                else       -> "F"
            }
        }
}

/**
 * Reads the Apps list and App detail's header, and writes an app's status. The numbers
 * are 1.0's (`AppsViewModel.refresh`), moved here so App detail shows the same word and
 * grade as the list. Everything runs off the main thread: the history database is
 * encrypted and can take seconds to open after boot.
 */
class AppsRepository(private val context: Context) {

    private val db by lazy { AppDatabase.getInstance(context) }
    private val configDb by lazy { ConfigDatabase.getInstance(context) }
    private val metadataRepo by lazy { MetadataRepo(db.connectionProfileDao()) }
    // A private engine: the VPN service reloads its own from the database on RuleSyncBus.
    private val rulesRepo by lazy { RulesRepo(configDb.rulesDao(), FilterEngine()) }
    private val blockStats by lazy { BlockStats(db) }

    // Install source and launcher icon change only with an install or update; the cache
    // is dropped when InstalledAppsCache reports one ([installedAppsChanged]).
    private val details = ConcurrentHashMap<String, InstalledAppsCache.PackageDetails>()

    fun installedAppsChanged() = details.clear()

    /** Tables whose writes can change the list. */
    fun historyTables(): Array<String> = arrayOf("connections", "dns_queries", "connection_profiles")

    fun database(): AppDatabase = db
    fun configDatabase(): ConfigDatabase = configDb

    suspend fun load(now: Long = System.currentTimeMillis()): List<AppRiskItem> = build(now)

    /**
     * The same item the list shows, for App detail's header; null if not installed.
     * Built from the whole list on purpose: helper packages (Facebook, Instagram) count
     * under the main app, which needs every installed package.
     */
    suspend fun itemFor(packageName: String, now: Long = System.currentTimeMillis()): AppRiskItem? =
        build(now).firstOrNull { it.packageName == packageName }

    suspend fun userRules(): List<FilterRule> = rulesRepo.userRules()

    /** Applies [change]; returns what Undo needs to put the rules back as they were. */
    suspend fun apply(change: AppsLogic.StatusChange): AppsLogic.StatusChange = withContext(Dispatchers.IO) {
        val before = rulesRepo.allRules().associateBy { it.id }
        // New rules first: each write reaches the engine at once (RuleSyncBus), so between
        // Blocked and Blocked in background the app is never left with no rule. Safe because
        // a change never upserts and removes the same id (AppsLogicTest).
        change.upsert.forEach { rulesRepo.upsertRule(it) }
        change.remove.forEach { rulesRepo.deleteRule(it.id) }
        AppsLogic.reverseOf(change, before)
    }

    private suspend fun build(now: Long): List<AppRiskItem> = withContext(Dispatchers.IO) {
        val since = now - DAY_MS
        val profiles = metadataRepo.recent()
        // Sessions open right now: App detail merges the same ones into its counts.
        val liveSessions = StatsManager.snapshot.value.activeConnections
        // Allowed connections only (`between`): 1.0's numbers; blocked ones are in blockedToday.
        val recentConnections = db.connectionDao().between(since, now)
        val rules = rulesRepo.userRules()
        val blockedToday = blockStats.perAppToday(now)
        val active = db.connectionDao().countByPackageSince(now - AppsLogic.ACTIVE_WINDOW_MS).map { it.packageName }

        val installedApps = InstalledAppsCache.networkApps(context)
        val permissionsByPackage = installedApps.associate { it.packageName to it.requestedPermissions }
        val installedPackages = installedApps.mapTo(HashSet()) { it.packageName }
        fun canonical(pkg: String) = canonicalPackageForObserved(pkg, installedPackages)

        val profileBuckets = profiles.groupBy { canonical(it.packageName) }
        val liveBuckets = liveSessions.groupBy { canonical(it.packageName) }
        val connectionBuckets = recentConnections.groupBy { canonical(it.packageName) }
        val blockedBuckets = HashMap<String, Int>().also { m -> blockedToday.forEach { (p, n) -> m.merge(canonical(p), n, Int::plus) } }
        val activeSet = active.mapTo(HashSet()) { canonical(it) }

        // Traffic numbers are the last 24 h plus open sessions, exactly what App detail
        // shows for the app. Risk uses the same 24 h, or a row could say HIGH next to
        // "0 destinations"; the stalkerware check still looks at all behaviour.
        installedApps.filter { it.packageName.isNotBlank() }.distinctBy { it.packageName }.map { app ->
            val pkg = app.packageName
            val rows = profileBuckets[pkg].orEmpty()
            val liveRows = liveBuckets[pkg].orEmpty()
            val connectionRows = connectionBuckets[pkg].orEmpty()
            val status = AppsLogic.statusOf(rules, pkg)
            val base = AppRiskItem(
                appName = app.appName.ifBlank { AppNames.label(context, pkg) },
                packageName = pkg,
                totalDestinations = 0, suspiciousCount = 0, cleartextCount = 0, totalBytesOut = 0L,
                maxRiskScore = AppsLogic.NO_TRAFFIC_RISK,
                status = status,
                blockedToday = blockedBuckets[pkg] ?: 0,
                activeNow = liveRows.isNotEmpty() || pkg in activeSet,
            )
            if (rows.isEmpty() && liveRows.isEmpty() && connectionRows.isEmpty()) return@map base
            val stalkerware = assessStalkerware(pkg, permissionsByPackage[pkg].orEmpty(), rows)
            val hosts = (connectionRows.map { it.host ?: it.destinationIp } + liveRows.map { it.hostName ?: it.destinationIp })
                .filter { it.isNotBlank() }.toSet()
            base.copy(
                totalDestinations = hosts.size,
                suspiciousCount = rows.count { it.lastSeen >= since && it.riskScore >= 40 },
                cleartextCount = connectionRows.count { HomeEvidence.isInternetCleartext(it) } +
                    liveRows.count {
                        it.securityInfo == "CLEARTEXT" && !LocalNetwork.isLocalAddress(it.destinationIp) &&
                            !ConnectivityProbes.isProbe(it.packageName, it.hostName)
                    },
                totalBytesOut = connectionRows.sumOf { it.bytesSent + it.bytesReceived } + liveRows.sumOf { it.bytesTransferred },
                maxRiskScore = AppsLogic.windowRisk(
                    rows, since,
                    connectionRisk = if (connectionRows.isEmpty() && liveRows.isEmpty()) null else connectionRisk(connectionRows),
                ),
                stalkerwareScore = stalkerware.score,
                stalkerwareReasons = stalkerware.reasons,
                // Same test as App detail: the app was not on screen (unknown is not counted).
                backgroundCount = connectionRows.count { it.foreground == false },
            )
        }
    }

    /** 1.0's fallback risk from the window's connections, when no profile has one. */
    private fun connectionRisk(rows: List<ConnectionEntity>): Int = when {
        rows.isEmpty() -> 0
        rows.any { HomeEvidence.isInternetCleartext(it) } -> 25
        else -> 12
    }

    private fun assessStalkerware(
        packageName: String,
        requestedPermissions: Set<String>,
        rows: List<ConnectionProfile>,
    ): StalkerwareAssessment = runCatching {
        val d = details.getOrPut(packageName) { InstalledAppsCache.details(context, packageName) }
        StalkerwareDetector.assess(
            requestedPermissions = requestedPermissions,
            profiles = rows,
            hasLauncherIcon = d.hasLauncherIcon,
            installerPackage = d.installerPackage,
            isSystemApp = d.isSystemApp,
        )
    }.getOrDefault(StalkerwareAssessment(0, emptyList()))

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /**
         * Facebook and Instagram run helper packages; their traffic is shown under the
         * main app the user knows (1.0 behaviour).
         */
        internal fun canonicalPackageForObserved(
            observedPackage: String,
            installedPackages: Set<String>,
        ): String {
            if (observedPackage in installedPackages) return observedPackage
            return preferredFamilyPackage(observedPackage, installedPackages) ?: observedPackage
        }

        private fun preferredFamilyPackage(observedPackage: String, installedPackages: Set<String>): String? = when {
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
    }
}
