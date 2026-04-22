package com.privacyguard.app.core.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false
)

object AppResolver {

    private val cache = ConcurrentHashMap<Int, AppInfo>()
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        if (!::appContext.isInitialized) return
        cache.clear()
        AppDatabase.clear()

        val packageManager = appContext.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installedApps) {
            val resolved = AppInfo(
                uid = app.uid,
                packageName = app.packageName,
                appName = packageManager.getApplicationLabel(app).toString(),
                isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
            cache[resolved.uid] = resolved
            AppDatabase.put(resolved)
        }
    }

    fun getAppByUid(uid: Int): AppInfo {
        cache[uid]?.let { return it }
        AppDatabase.get(uid)?.let {
            cache[uid] = it
            return it
        }

        specialApp(uid)?.let {
            cache[uid] = it
            AppDatabase.put(it)
            return it
        }

        if (::appContext.isInitialized) {
            try {
                val packageManager = appContext.packageManager
                val packages = packageManager.getPackagesForUid(uid)
                val packageName = packages?.firstOrNull()
                if (packageName != null) {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val resolved = AppInfo(
                        uid = uid,
                        packageName = packageName,
                        appName = packageManager.getApplicationLabel(appInfo).toString(),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                    cache[uid] = resolved
                    AppDatabase.put(resolved)
                    return resolved
                }
            } catch (_: Exception) {
            }
        }

        return AppInfo(uid, "unknown:$uid", "Unknown ($uid)")
    }

    fun getAppName(uid: Int): String = getAppByUid(uid).appName

    fun getInstalledApps(): List<AppInfo> {
        if (cache.isEmpty() && ::appContext.isInitialized) {
            refresh()
        }
        return cache.values.sortedBy { it.appName.lowercase() }
    }

    fun getUserApps(): List<AppInfo> = getInstalledApps().filter { !it.isSystemApp }

    private fun specialApp(uid: Int): AppInfo? = when (uid) {
        0 -> AppInfo(uid, "android:root", "Root", true)
        1000 -> AppInfo(uid, "android:system", "Android System", true)
        1001 -> AppInfo(uid, "android:radio", "Radio", true)
        1002 -> AppInfo(uid, "android:bluetooth", "Bluetooth", true)
        1013 -> AppInfo(uid, "android:media", "Media", true)
        else -> null
    }
}
