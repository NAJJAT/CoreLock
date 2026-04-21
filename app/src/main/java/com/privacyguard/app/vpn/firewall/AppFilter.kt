/**
 * AppFilter.kt
 * 
 * Application filtering for PrivacyGuard
 * 
 * What it does:
 * =============
 * Maps UIDs (User IDs) to application package names and names.
 * Used for per-app firewall rules and attribution.
 * 
 * Why this is important:
 * ======================
 * Every packet from the TUN interface has a UID (User ID) that identifies
 * which app sent the packet. AppFilter converts this UID to:
 * - Package name (e.g., "com.android.chrome")
 * - App name (e.g., "Chrome")
 * - App icon (for UI)
 * 
 * Performance Requirements:
 * =========================
 * - UID → Package lookup must be cached (O(1) after first lookup)
 * - Called for every packet (1000+ times per second)
 * - Cache must be thread-safe
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.firewall

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.privacyguard.app.core.utils.ipToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ============================================================
// App Info Data Class
// ============================================================

/**
 * Information about an installed application
 * 
 * @property uid User ID (unique identifier for the app)
 * @property packageName Package name (e.g., "com.android.chrome")
 * @property appName Human-readable app name (e.g., "Chrome")
 * @property isSystemApp True if app is pre-installed on device
 * @property isEnabled True if app is enabled
 * @property firstSeen Timestamp when app was first detected
 * @property lastSeen Timestamp when app was last active
 * @property totalBytesSent Total bytes sent by this app (since tracking started)
 * @property totalBytesReceived Total bytes received by this app
 * @property totalPacketsSent Total packets sent by this app
 * @property totalPacketsReceived Total packets received by this app
 * @property blockedCount Number of times this app was blocked
 */
data class AppInfo(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isEnabled: Boolean = true,
    val firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var totalBytesSent: Long = 0,
    var totalBytesReceived: Long = 0,
    var totalPacketsSent: Long = 0,
    var totalPacketsReceived: Long = 0,
    var blockedCount: Long = 0
) {
    /**
     * Returns total bytes (sent + received)
     */
    val totalBytes: Long get() = totalBytesSent + totalBytesReceived
    
    /**
     * Returns total packets (sent + received)
     */
    val totalPackets: Long get() = totalPacketsSent + totalPacketsReceived
    
    /**
     * Returns a formatted string for UI display
     */
    fun formatBytes(): String {
        return when {
            totalBytes < 1024 -> "$totalBytes B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            else -> String.format("%.1f MB", totalBytes / (1024.0 * 1024.0))
        }
    }
}

// ============================================================
// AppFilter - Main Implementation
// ============================================================

/**
 * Application filter - maps UIDs to application information
 * 
 * @param context Android context for PackageManager access
 */
class AppFilter(private val context: Context) {
    
    companion object {
        private const val TAG = "AppFilter"
        
        // Special UIDs
        const val UID_ROOT = 0
        const val UID_SYSTEM = 1000
        const val UID_PHONE = 1001
        const val UID_MEDIA = 1002
        const val UID_RADIO = 1003
        const val UID_NFC = 1027
        const val UID_SHELL = 2000
        const val UID_NOBODY = 9999
    }
    
    // ============================================================
    // Cache Storage
    // ============================================================
    
    // Primary cache: UID → AppInfo
    private val appCache = ConcurrentHashMap<Int, AppInfo>()
    
    // Secondary cache: Package name → UID
    private val packageToUidCache = ConcurrentHashMap<String, Int>()
    
    // Cache for app icons (Drawable is heavy, cache separately)
    private val iconCache = ConcurrentHashMap<Int, Drawable>()
    
    // Statistics
    private val totalLookups = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val packageManagerErrors = AtomicLong(0)
    
    // ============================================================
    // Initialization
    // ============================================================
    
    /**
     * Initializes the app filter with all installed apps
     * 
     * Called once at application startup
     */
    fun initialize() {
        try {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            for (appInfo in packages) {
                val uid = appInfo.uid
                val packageName = appInfo.packageName
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val info = AppInfo(
                    uid = uid,
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    isEnabled = appInfo.enabled
                )
                
                appCache[uid] = info
                packageToUidCache[packageName] = uid
            }
            
            android.util.Log.d(TAG, "Initialized with ${appCache.size} apps")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to initialize app filter", e)
        }
    }
    
    // ============================================================
    // Lookup Operations
    // ============================================================
    
    /**
     * Gets application info by UID
     * 
     * This is the MAIN lookup function called for every packet.
     * 
     * @param uid User ID (from packet)
     * @return AppInfo or null if not found
     */
    fun getAppByUid(uid: Int): AppInfo? {
        totalLookups.incrementAndGet()
        
        // Check cache first
        var appInfo = appCache[uid]
        if (appInfo != null) {
            cacheHits.incrementAndGet()
            return appInfo
        }
        
        cacheMisses.incrementAndGet()
        
        // Special UIDs that don't have package names
        val specialApp = getSpecialAppInfo(uid)
        if (specialApp != null) {
            appCache[uid] = specialApp
            return specialApp
        }
        
        // Try to get from PackageManager
        try {
            val packageManager = context.packageManager
            val packages = packageManager.getPackagesForUid(uid)
            
            if (packages != null && packages.isNotEmpty()) {
                val packageName = packages[0]  // Use first package for this UID
                val appInfoObj = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(appInfoObj).toString()
                val isSystemApp = (appInfoObj.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val info = AppInfo(
                    uid = uid,
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    isEnabled = appInfoObj.enabled
                )
                
                appCache[uid] = info
                packageToUidCache[packageName] = uid
                return info
            }
        } catch (e: PackageManager.NameNotFoundException) {
            packageManagerErrors.incrementAndGet()
            android.util.Log.w(TAG, "Package not found for UID $uid", e)
        } catch (e: Exception) {
            packageManagerErrors.incrementAndGet()
            android.util.Log.w(TAG, "Error getting app for UID $uid", e)
        }
        
        // Unknown UID - create placeholder
        val unknownApp = AppInfo(
            uid = uid,
            packageName = "unknown:$uid",
            appName = "Unknown ($uid)",
            isSystemApp = false
        )
        appCache[uid] = unknownApp
        return unknownApp
    }
    
    /**
     * Gets application info by package name
     */
    fun getAppByPackageName(packageName: String): AppInfo? {
        val uid = packageToUidCache[packageName]
        return if (uid != null) getAppByUid(uid) else null
    }
    
    /**
     * Gets special app info for system UIDs
     */
    private fun getSpecialAppInfo(uid: Int): AppInfo? {
        return when (uid) {
            UID_ROOT -> AppInfo(uid, "android:root", "Root", isSystemApp = true)
            UID_SYSTEM -> AppInfo(uid, "android:system", "Android System", isSystemApp = true)
            UID_PHONE -> AppInfo(uid, "android:phone", "Phone", isSystemApp = true)
            UID_MEDIA -> AppInfo(uid, "android:media", "Media", isSystemApp = true)
            UID_RADIO -> AppInfo(uid, "android:radio", "Radio", isSystemApp = true)
            UID_NFC -> AppInfo(uid, "android:nfc", "NFC", isSystemApp = true)
            UID_SHELL -> AppInfo(uid, "android:shell", "Shell", isSystemApp = true)
            UID_NOBODY -> AppInfo(uid, "android:nobody", "Nobody", isSystemApp = true)
            else -> null
        }
    }
    
    // ============================================================
    // Statistics Update
    // ============================================================
    
    /**
     * Updates statistics for an app (called by packet processors)
     * 
     * @param uid User ID
     * @param bytesSent Bytes sent in this packet
     * @param bytesReceived Bytes received in this packet
     */
    fun updateStats(uid: Int, bytesSent: Int, bytesReceived: Int) {
        val appInfo = appCache[uid] ?: return
        
        appInfo.lastSeen = System.currentTimeMillis()
        appInfo.totalBytesSent += bytesSent
        appInfo.totalBytesReceived += bytesReceived
        
        if (bytesSent > 0) appInfo.totalPacketsSent++
        if (bytesReceived > 0) appInfo.totalPacketsReceived++
    }
    
    /**
     * Records a blocked connection for an app
     */
    fun recordBlocked(uid: Int) {
        val appInfo = appCache[uid] ?: return
        appInfo.blockedCount++
    }
    
    // ============================================================
    // Icon Loading
    // ============================================================
    
    /**
     * Gets application icon by UID
     * 
     * Note: Drawable is heavy - cache results
     * 
     * @param uid User ID
     * @return App icon Drawable, or null if not available
     */
    fun getAppIcon(uid: Int): Drawable? {
        // Check cache
        var icon = iconCache[uid]
        if (icon != null) return icon
        
        // Load from PackageManager
        try {
            val appInfo = getAppByUid(uid) ?: return null
            val packageManager = context.packageManager
            val packageInfo = packageManager.getApplicationInfo(appInfo.packageName, 0)
            icon = packageManager.getApplicationIcon(packageInfo)
            iconCache[uid] = icon
            return icon
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to load icon for UID $uid", e)
            return null
        }
    }
    
    // ============================================================
    // Package Name Lookup
    // ============================================================
    
    /**
     * Gets package name by UID
     */
    fun getPackageName(uid: Int): String? {
        return getAppByUid(uid)?.packageName
    }
    
    /**
     * Gets app name by UID
     */
    fun getAppName(uid: Int): String? {
        return getAppByUid(uid)?.appName
    }
    
    /**
     * Gets UID by package name
     */
    fun getUid(packageName: String): Int? {
        return packageToUidCache[packageName]
    }
    
    // ============================================================
    // Cache Management
    // ============================================================
    
    /**
     * Refreshes app info for a specific UID
     * 
     * Called when an app is installed or updated
     */
    fun refreshApp(uid: Int) {
        appCache.remove(uid)
        getAppByUid(uid)  // Reload
    }
    
    /**
     * Refreshes all apps (called after app install/uninstall)
     */
    fun refreshAllApps() {
        appCache.clear()
        packageToUidCache.clear()
        iconCache.clear()
        initialize()
    }
    
    /**
     * Clears all statistics (not the cache itself)
     */
    fun clearStats() {
        appCache.values.forEach { appInfo ->
            appInfo.totalBytesSent = 0
            appInfo.totalBytesReceived = 0
            appInfo.totalPacketsSent = 0
            appInfo.totalPacketsReceived = 0
            appInfo.blockedCount = 0
        }
    }
    
    // ============================================================
    // Statistics
    // ============================================================
    
    /**
     * Returns all tracked apps
     */
    fun getAllApps(): List<AppInfo> {
        return appCache.values.toList().sortedBy { it.appName }
    }
    
    /**
     * Returns apps sorted by data usage (highest first)
     */
    fun getAppsByDataUsage(): List<AppInfo> {
        return appCache.values.toList().sortedByDescending { it.totalBytes }
    }
    
    /**
     * Returns apps with network activity (non-zero bytes)
     */
    fun getActiveApps(): List<AppInfo> {
        return appCache.values.filter { it.totalBytes > 0 }.sortedByDescending { it.totalBytes }
    }
    
    /**
     * Returns system apps
     */
    fun getSystemApps(): List<AppInfo> {
        return appCache.values.filter { it.isSystemApp }.sortedBy { it.appName }
    }
    
    /**
     * Returns user-installed apps
     */
    fun getUserApps(): List<AppInfo> {
        return appCache.values.filter { !it.isSystemApp }.sortedBy { it.appName }
    }
    
    /**
     * Returns filter statistics
     */
    fun getStats(): AppFilterStats {
        return AppFilterStats(
            cachedApps = appCache.size,
            totalLookups = totalLookups.get(),
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            hitRate = if (totalLookups.get() > 0) {
                cacheHits.get().toDouble() / totalLookups.get()
            } else 0.0,
            packageManagerErrors = packageManagerErrors.get()
        )
    }
    
    /**
     * Resets statistics
     */
    fun resetStats() {
        totalLookups.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        packageManagerErrors.set(0)
    }
}

/**
 * AppFilter statistics
 */
data class AppFilterStats(
    val cachedApps: Int,
    val totalLookups: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val hitRate: Double,
    val packageManagerErrors: Long
)