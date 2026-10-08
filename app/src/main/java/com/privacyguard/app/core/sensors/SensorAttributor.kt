package com.privacyguard.app.core.sensors

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

/** How sure we are about which app used the sensor. Never present LIKELY/UNKNOWN as fact. */
enum class Confidence {
    /** The system's app-ops record names this app (Advanced mode). */
    CONFIRMED,
    /** The app was on screen and holds the permission: the most probable user. */
    LIKELY,
    /** No reliable signal; see the candidates. */
    UNKNOWN,
}

data class Attribution(
    val packageName: String?,
    val confidence: Confidence,
    /** How it was decided, for the timeline ("app-ops record", "app on screen", …). */
    val method: String,
    /** Apps that could have done it (hold the permission), when not confirmed. */
    val candidates: List<String> = emptyList(),
)

/**
 * Works out which app used the camera or microphone. Android 10+ hides the
 * recording app from other apps, so this combines what is available:
 *
 *  1. Advanced mode — the system's app-ops record (who is using / last used
 *     android:camera or android:record_audio). Needs GET_APP_OPS_STATS, which only
 *     `adb shell pm grant` can give and which only the enterprise build declares;
 *     the record is read through a hidden API, so it may stop working on future
 *     Android versions and then falls through. Result: CONFIRMED.
 *  2. Usage Access (user opt-in) — the app on screen when the use started; if it
 *     holds the permission it is the LIKELY user.
 *  3. Otherwise UNKNOWN, with the apps that hold the permission as candidates.
 */
class SensorAttributor(private val context: Context) {

    private val pm = context.packageManager
    private val selfPackage = context.packageName

    /** Usage Access granted by the user (Settings > Usage access). */
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), selfPackage)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), selfPackage)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Advanced mode: GET_APP_OPS_STATS granted via adb (enterprise build only). */
    fun hasAdvancedMode(): Boolean =
        context.checkSelfPermission(GET_APP_OPS_STATS) == PackageManager.PERMISSION_GRANTED

    fun attribute(use: SensorUse, now: Long = System.currentTimeMillis()): Attribution {
        val opRecords = if (hasAdvancedMode()) readOps(use.sensor) else null
        val foreground = if (hasUsageAccess()) foregroundAt(use.startTime) else null
        return decide(
            use = use,
            now = now,
            selfPackage = selfPackage,
            opRecords = opRecords,
            foregroundPackage = foreground,
            holdsPermission = { pkg -> holdsPermission(pkg, use.sensor) },
            candidates = { candidatesFor(use.sensor) },
        )
    }

    /** Packages the user brought to the screen since [since]; null without Usage Access. */
    fun packagesOpenedSince(since: Long, until: Long): Set<String>? {
        if (!hasUsageAccess()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = runCatching { usm.queryEvents(since, until) }.getOrNull() ?: return null
        val opened = HashSet<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == RESUMED) opened += event.packageName
        }
        return opened
    }

    /**
     * The app on screen when the sensor started. An app that comes to the screen
     * within [STARTUP_WINDOW_MS] after [time] wins: camera apps open the camera
     * while starting, before Android records their activity as resumed. Otherwise
     * it is the app last resumed (and not paused) before [time].
     */
    private fun foregroundAt(time: Long): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = runCatching { usm.queryEvents(time - FOREGROUND_LOOKBACK_MS, time + STARTUP_WINDOW_MS) }.getOrNull()
            ?: return null
        var before: String? = null
        var startedJustAfter: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when {
                event.timeStamp <= time && event.eventType == RESUMED -> before = event.packageName
                event.timeStamp <= time && event.eventType == PAUSED && event.packageName == before -> before = null
                event.timeStamp > time && event.eventType == RESUMED && startedJustAfter == null ->
                    startedJustAfter = event.packageName
            }
        }
        return startedJustAfter ?: before
    }

    /** One app-ops record per package for the sensor's op. */
    data class OpRecord(val packageName: String, val running: Boolean, val lastAccess: Long)

    private fun readOps(sensor: SensorType): List<OpRecord>? = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val opName = if (sensor == SensorType.CAMERA) AppOpsManager.OPSTR_CAMERA else AppOpsManager.OPSTR_RECORD_AUDIO
        val packages = AppOpsManager::class.java
            .getMethod("getPackagesForOps", Array<String>::class.java)
            .invoke(ops, arrayOf(opName)) as? List<*> ?: emptyList<Any>()
        packages.filterNotNull().flatMap { po ->
            val pkg = po.javaClass.getMethod("getPackageName").invoke(po) as String
            (po.javaClass.getMethod("getOps").invoke(po) as List<*>).filterNotNull().map { op ->
                val running = runCatching { op.javaClass.getMethod("isRunning").invoke(op) as Boolean }.getOrDefault(false)
                val last = runCatching {
                    op.javaClass.getMethod("getLastAccessTime", Int::class.javaPrimitiveType).invoke(op, ALL_OP_FLAGS) as Long
                }.getOrDefault(-1L)
                OpRecord(pkg, running, last)
            }
        }
    } catch (t: Throwable) {
        // Hidden API blocked or changed on this Android version: fall back quietly.
        Log.w(TAG, "App-ops record unavailable: ${t.javaClass.simpleName}")
        null
    }

    private fun holdsPermission(pkg: String, sensor: SensorType): Boolean =
        pm.checkPermission(permissionFor(sensor), pkg) == PackageManager.PERMISSION_GRANTED

    @Volatile private var candidateCache: Pair<Long, Map<SensorType, List<String>>>? = null

    /** Installed apps currently granted the sensor's permission (cached 10 minutes). */
    private fun candidatesFor(sensor: SensorType): List<String> {
        val now = System.currentTimeMillis()
        candidateCache?.let { (at, map) -> if (now - at < CANDIDATE_TTL_MS) return map[sensor].orEmpty() }
        @Suppress("DEPRECATION")
        val installed: List<PackageInfo> = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())
        val map = SensorType.values().associateWith { type ->
            val perm = permissionFor(type)
            installed.filter { info ->
                val i = info.requestedPermissions?.indexOf(perm) ?: -1
                i >= 0 && ((info.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }.map { it.packageName }.filter { it != selfPackage }
        }
        candidateCache = now to map
        return map[sensor].orEmpty()
    }

    companion object {
        private const val TAG = "SensorAttributor"
        const val GET_APP_OPS_STATS = "android.permission.GET_APP_OPS_STATS"
        private const val ALL_OP_FLAGS = 0x1f
        private const val FOREGROUND_LOOKBACK_MS = 30 * 60_000L
        /** Re-check attribution this long after a use starts (see [foregroundAt]). */
        const val STARTUP_WINDOW_MS = 2_500L
        private const val CANDIDATE_TTL_MS = 10 * 60_000L
        /** App-ops records this close to the use's start or end count as that use. */
        private const val MATCH_WINDOW_MS = 5_000L

        @Suppress("DEPRECATION")
        private val RESUMED = UsageEvents.Event.MOVE_TO_FOREGROUND   // == ACTIVITY_RESUMED (API 29)
        @Suppress("DEPRECATION")
        private val PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND    // == ACTIVITY_PAUSED (API 29)

        fun permissionFor(sensor: SensorType): String =
            if (sensor == SensorType.CAMERA) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO

        /**
         * The attribution decision, separated from Android for testing.
         * [opRecords] null means Advanced mode is off or failed.
         */
        fun decide(
            use: SensorUse,
            now: Long,
            selfPackage: String,
            opRecords: List<OpRecord>?,
            foregroundPackage: String?,
            holdsPermission: (String) -> Boolean,
            candidates: () -> List<String>,
        ): Attribution {
            if (opRecords != null) {
                val end = use.endTime ?: now
                val matching = opRecords.filter { r ->
                    r.packageName != selfPackage &&
                        (r.running || r.lastAccess in (use.startTime - MATCH_WINDOW_MS)..(end + MATCH_WINDOW_MS))
                }.map { it.packageName }.distinct()
                when (matching.size) {
                    1 -> return Attribution(matching[0], Confidence.CONFIRMED, "system app-ops record")
                    0 -> Unit
                    else -> {
                        // Several apps in the window: the one on screen, if any, else unresolved.
                        val onScreen = matching.firstOrNull { it == foregroundPackage }
                        return if (onScreen != null) Attribution(onScreen, Confidence.CONFIRMED, "system app-ops record + app on screen", matching)
                        else Attribution(null, Confidence.UNKNOWN, "several apps in the app-ops record", matching)
                    }
                }
            }
            if (foregroundPackage != null && foregroundPackage != selfPackage && holdsPermission(foregroundPackage)) {
                return Attribution(foregroundPackage, Confidence.LIKELY, "app on screen holds the permission")
            }
            return Attribution(null, Confidence.UNKNOWN, if (foregroundPackage == null) "no app on screen" else "app on screen ($foregroundPackage) lacks the permission", candidates())
        }
    }
}
