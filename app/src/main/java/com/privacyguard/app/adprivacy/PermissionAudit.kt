package com.privacyguard.app.adprivacy

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

enum class LocationLevel {
    /** ACCESS_BACKGROUND_LOCATION granted (Android 10+). */
    ALWAYS,
    /** Foreground location only. */
    WHILE_IN_USE,
    /** Android 9 and older have no while-in-use split: a grant means always. */
    ALWAYS_LEGACY,
}

data class PermissionApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val locationLevel: LocationLevel? = null,
    val precise: Boolean = false,
)

/**
 * Who holds location and microphone permission, from PackageManager grant flags.
 *
 * What Android does NOT expose to a regular app: when another app last used
 * location or the mic (AppOpsManager history is a system API). The Privacy
 * dashboard in system settings has that, so the UI links there instead.
 */
object PermissionAudit {

    fun locationApps(pm: PackageManager, selfPackage: String): List<PermissionApp> =
        installed(pm, selfPackage).mapNotNull { info ->
            val fine = info.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = info.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) return@mapNotNull null
            val level = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> LocationLevel.ALWAYS_LEGACY
                info.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> LocationLevel.ALWAYS
                else -> LocationLevel.WHILE_IN_USE
            }
            info.toApp(pm, level, precise = fine)
        }.sortedWith(compareBy<PermissionApp>({ it.isSystem }, { it.locationLevel != LocationLevel.ALWAYS }, { it.label.lowercase() }))

    fun micApps(pm: PackageManager, selfPackage: String): List<PermissionApp> =
        installed(pm, selfPackage)
            .filter { it.isGranted(Manifest.permission.RECORD_AUDIO) }
            .map { it.toApp(pm, null, false) }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))

    @Suppress("DEPRECATION")
    private fun installed(pm: PackageManager, selfPackage: String): List<PackageInfo> =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { it.packageName != selfPackage }

    private fun PackageInfo.isGranted(permission: String): Boolean {
        val perms = requestedPermissions ?: return false
        val flags = requestedPermissionsFlags ?: return false
        val i = perms.indexOf(permission)
        return i >= 0 && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun PackageInfo.toApp(pm: PackageManager, level: LocationLevel?, precise: Boolean): PermissionApp {
        val app = applicationInfo
        return PermissionApp(
            packageName = packageName,
            label = app?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
            isSystem = app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            locationLevel = level,
            precise = precise,
        )
    }
}
