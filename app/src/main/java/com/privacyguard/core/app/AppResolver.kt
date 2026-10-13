package com.privacyguard.core.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.privacyguard.domain.model.AppInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Android UIDs → [AppInfo] with in-memory caching.
 *
 * Called on every packet in the VPN pipeline — caching is mandatory for performance.
 * Cache is invalidated via [onPackageChanged] (called from a BroadcastReceiver).
 */
class AppResolver(
    private val pm: PackageManager,
) {
    private val uidCache   = ConcurrentHashMap<Int, AppInfo>(64)
    private val pkgCache   = ConcurrentHashMap<String, AppInfo>(64)

    // ─────────────────────────────────────────────────────────────────────────
    // Lookup
    // ─────────────────────────────────────────────────────────────────────────

    fun resolveUid(uid: Int): AppInfo {
        if (uid <= 0) return AppInfo.unknown(uid)
        if (isSystemUid(uid)) return AppInfo.system()
        return uidCache.getOrPut(uid) { loadFromUid(uid) }
    }

    fun resolvePackage(packageName: String): AppInfo =
        pkgCache.getOrPut(packageName) { loadFromPackage(packageName) }

    private fun loadFromUid(uid: Int): AppInfo {
        val pkg = pm.getNameForUid(uid) ?: return AppInfo.unknown(uid)
        val info = loadFromPackage(pkg)
        uidCache[uid] = info
        return info
    }

    private fun loadFromPackage(packageName: String): AppInfo {
        return try {
            val info  = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val label = pm.getApplicationLabel(info).toString()
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            AppInfo(
                uid         = info.uid,
                packageName = packageName,
                label       = label,
                isSystem    = isSystem,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            AppInfo.unknown(-1, packageName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk loading (call at VPN startup)
    // ─────────────────────────────────────────────────────────────────────────

    fun preload() {
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
            val label = pm.getApplicationLabel(info).toString()
            val app   = AppInfo(
                uid         = info.uid,
                packageName = info.packageName,
                label       = label,
                isSystem    = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
            uidCache[info.uid]          = app
            pkgCache[info.packageName]  = app
        }
    }

    /** Returns all user-installed apps sorted by label. */
    fun installedUserApps(): List<AppInfo> =
        pkgCache.values
            .filter { !it.isSystem && it.uid > 0 }
            .sortedBy { it.label.lowercase() }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache invalidation
    // ─────────────────────────────────────────────────────────────────────────

    fun onPackageChanged(packageName: String) {
        val app = pkgCache.remove(packageName) ?: return
        uidCache.remove(app.uid)
    }

    fun invalidateAll() { uidCache.clear(); pkgCache.clear() }

    private fun isSystemUid(uid: Int) = uid in 0..999 || uid == 1000

    companion object { val cacheSize: Int get() = 0 }
}