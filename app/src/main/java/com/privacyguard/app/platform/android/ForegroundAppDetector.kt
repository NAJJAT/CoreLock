package com.privacyguard.platform.android

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Which app is on screen, from UsageStatsManager events.
 *
 * ActivityManager.getRunningAppProcesses() only returns the caller's own
 * processes since Android 5.1, so it cannot answer this for other apps. Usage
 * events can, but only after the user grants Usage Access in system settings;
 * without it every answer is null ("unknown"), never a guess.
 */
class ForegroundAppDetector(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    // Per-packet callers (connection logging) hit this constantly; usage events
    // are an IPC, so the answer is reused for a short window.
    @Volatile private var cached: Pair<Long, String?>? = null

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The package whose activity is currently resumed, or null if unknown. */
    fun foregroundPackage(): String? {
        val now = System.currentTimeMillis()
        cached?.let { (at, pkg) -> if (now - at < CACHE_MS) return pkg }
        val pkg = if (usm == null || !hasUsageAccess()) null else runCatching { resolve(now) }.getOrNull()
        cached = now to pkg
        return pkg
    }

    fun isInForeground(packageName: String): Boolean = foregroundPackage() == packageName

    /**
     * The package on screen at [atMillis], from a fresh (non-incremental) read.
     * Usage events land a moment after the switch, so callers attributing an
     * event should ask a second or two after it happened.
     */
    fun packageAt(atMillis: Long): String? {
        val manager = usm ?: return null
        if (!hasUsageAccess()) return null
        return runCatching {
            val events = manager.queryEvents(atMillis - LOOKBACK_MS, atMillis)
            val event = UsageEvents.Event()
            var current: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> current = event.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == current) current = null
                }
            }
            current
        }.getOrNull()
    }

    // Incremental: after the first lookback, only events since the last query are read.
    private var current: String? = null
    private var queriedUntil = 0L

    @Synchronized
    private fun resolve(now: Long): String? {
        val from = if (queriedUntil == 0L) now - LOOKBACK_MS else queriedUntil
        val events = usm!!.queryEvents(from, now)
        queriedUntil = now
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // ACTIVITY_RESUMED/PAUSED (Q+) share their values with the older
                // MOVE_TO_FOREGROUND/BACKGROUND constants.
                UsageEvents.Event.MOVE_TO_FOREGROUND -> current = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == current) current = null
            }
        }
        return current
    }

    private companion object {
        const val CACHE_MS = 2_000L
        // An app resumed long ago and still on screen is found as long as its
        // resume event is inside the window; 6 h covers a long session.
        const val LOOKBACK_MS = 6 * 60 * 60 * 1000L
    }
}
