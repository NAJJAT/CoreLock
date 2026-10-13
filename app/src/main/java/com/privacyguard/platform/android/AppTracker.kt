package com.privacyguard.platform.android

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.privacyguard.vpn.firewall.AppFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-specific implementation of UID → App resolution.
 *
 * Wraps [PackageManager] calls and provides:
 *  - UID → package name resolution (may be shared UID — returns primary package)
 *  - UID → human-readable app label resolution
 *  - System-app detection
 *  - Package-change broadcast handling (call [onPackageChanged] from a BroadcastReceiver)
 *
 * Integrates with [AppFilter] to populate its lookup cache.
 */
class AppTracker(
    private val context: Context,
    private val appFilter: AppFilter,
) {
    private val pm = context.packageManager

    /** uid → human-readable app name cache. */
    private val labelCache = ConcurrentHashMap<Int, String>(64)

    // ─────────────────────────────────────────────────────────────────────────
    // Resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the primary package name for [uid], or null if unknown.
     * Delegates to [AppFilter.packageForUid] which caches results.
     */
    fun packageForUid(uid: Int): String? = appFilter.packageForUid(uid)

    /**
     * Returns the human-readable application name for [uid].
     * Falls back to the package name if the label cannot be resolved.
     */
    fun labelForUid(uid: Int): String {
        labelCache[uid]?.let { return it }

        val pkg   = packageForUid(uid) ?: return "Unknown (uid=$uid)"
        val label = labelForPackage(pkg) ?: pkg
        labelCache[uid] = label
        return label
    }

    /**
     * Returns the human-readable application label for [packageName].
     */
    fun labelForPackage(packageName: String): String? = try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Returns the [ApplicationInfo] for [packageName], or null if not installed.
     */
    fun appInfo(packageName: String): ApplicationInfo? = try {
        pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Returns true if [uid] belongs to a system application.
     */
    fun isSystemApp(uid: Int): Boolean {
        if (appFilter.isSystemUid(uid)) return true
        val pkg  = packageForUid(uid) ?: return false
        val info = appInfo(pkg) ?: return false
        return (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk Loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pre-populates the [AppFilter] cache with all installed non-system apps.
     * Call this once at VPN startup so the first packet for each app doesn't
     * incur a PackageManager round-trip.
     */
    fun preloadInstalledApps() {
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (info in packages) {
            val uid = info.uid
            if (uid > 0) {
                appFilter.register(uid, info.packageName)
                labelCache[uid] = pm.getApplicationLabel(info).toString()
            }
        }
    }

    /**
     * Returns a list of all installed user applications with their UIDs.
     * Useful for the "per-app blocking" screen in the UI.
     */
    fun installedUserApps(): List<InstalledApp> {
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { info ->
                InstalledApp(
                    uid         = info.uid,
                    packageName = info.packageName,
                    label       = pm.getApplicationLabel(info).toString(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache Invalidation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when a package is installed, updated, or removed.
     * Clears the cache entry so the next lookup re-queries PackageManager.
     *
     * Register in a [BroadcastReceiver] for:
     *  - [Intent.ACTION_PACKAGE_ADDED]
     *  - [Intent.ACTION_PACKAGE_REMOVED]
     *  - [Intent.ACTION_PACKAGE_REPLACED]
     */
    fun onPackageChanged(packageName: String) {
        val uid = appFilter.uidForPackage(packageName)
        if (uid != -1) {
            appFilter.invalidate(uid)
            labelCache.remove(uid)
        } else {
            appFilter.invalidate(packageName)
        }
    }

    /**
     * Invalidates the entire UID/label cache.
     */
    fun invalidateAll() {
        appFilter.invalidateAll()
        labelCache.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground / Background detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [packageName] is currently in the foreground (importance ≤ FOREGROUND).
     * Uses ActivityManager.getRunningAppProcesses() which requires no special permission.
     * Returns false if unknown or if [packageName] is blank.
     */
    fun isInForeground(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = am.runningAppProcesses ?: return false
        return processes.any { proc ->
            proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                proc.pkgList?.contains(packageName) == true
        }
    }

    /**
     * Returns true if the app owning [uid] is currently in the background.
     */
    fun isInBackground(uid: Int): Boolean {
        val pkg = packageForUid(uid) ?: return false
        return !isInForeground(pkg)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data
    // ─────────────────────────────────────────────────────────────────────────

    data class InstalledApp(
        val uid: Int,
        val packageName: String,
        val label: String,
    )
}