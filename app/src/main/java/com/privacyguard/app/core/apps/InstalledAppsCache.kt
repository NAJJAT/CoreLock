package com.privacyguard.app.core.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of installed apps that can use the network.
 *
 * Listing packages with permissions and loading every label is one of the
 * slowest PackageManager calls, so it runs once, off the main thread, and again
 * only after an app is installed, updated or removed.
 */
object InstalledAppsCache {

    data class InstalledApp(
        val packageName: String,
        val appName: String,
        val requestedPermissions: Set<String>,
    )

    /** Per-package details used for the stalkerware assessment. */
    data class PackageDetails(val hasLauncherIcon: Boolean, val installerPackage: String?)

    private val _version = MutableStateFlow(0)
    /** Bumps whenever the installed set changes; collect it to reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val lock = Mutex()
    @Volatile private var cached: List<InstalledApp>? = null
    private val details = ConcurrentHashMap<String, PackageDetails>()
    @Volatile private var receiverRegistered = false

    suspend fun networkApps(context: Context): List<InstalledApp> {
        cached?.let { return it }
        val app = context.applicationContext
        registerReceiver(app)
        return lock.withLock {
            cached ?: run {
                val loadedAt = _version.value
                withContext(Dispatchers.IO) { load(app) }.also {
                    // An install/remove during the load makes this list stale; don't keep it.
                    if (_version.value == loadedAt) cached = it
                }
            }
        }
    }

    /** Launcher/installer info, memoized until the next package change. */
    @Suppress("DEPRECATION")
    fun details(context: Context, packageName: String): PackageDetails =
        details.getOrPut(packageName) {
            val pm = context.packageManager
            PackageDetails(
                hasLauncherIcon = pm.getLaunchIntentForPackage(packageName) != null,
                installerPackage = runCatching { pm.getInstallerPackageName(packageName) }.getOrNull(),
            )
        }

    fun invalidate() {
        cached = null
        details.clear()
        _version.value++
    }

    private fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .asSequence()
                .filter { info ->
                    info.packageName != context.packageName &&
                        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
                }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        appName = info.applicationInfo?.loadLabel(pm)?.toString()
                            ?: info.packageName.substringAfterLast('.'),
                        requestedPermissions = info.requestedPermissions?.toSet().orEmpty(),
                    )
                }
                .toList()
        }.getOrElse { emptyList() }
    }

    @Synchronized
    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // System package broadcasts still arrive at a non-exported receiver.
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = invalidate()
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }
}
