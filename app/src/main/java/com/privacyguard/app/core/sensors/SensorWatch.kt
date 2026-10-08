package com.privacyguard.app.core.sensors

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Camera & Mic Watch: ties the system signals ([SensorAccessMonitor]) to
 * attribution ([SensorAttributor]) and context ([SensorContextBuilder]) for each
 * use. Runs while the VPN service runs; all work happens on the monitor's
 * handler thread, driven by callbacks and two one-shot timers per use (the
 * attribution re-check and the upload window), never by polling.
 */
class SensorWatch(context: Context) {

    private val appContext = context.applicationContext
    private val attributor = SensorAttributor(appContext)
    private val device = AndroidDeviceState(appContext)
    private val settings = SettingsPreferences.getInstance(appContext)
    private val contextBuilder = SensorContextBuilder(
        device = device,
        quietHours = { QuietHours(settings.quietHoursStart.value, settings.quietHoursEnd.value) },
        openedSince = attributor::packagesOpenedSince,
        uidOf = { pkg -> runCatching { appContext.packageManager.getPackageUid(pkg, 0) }.getOrNull() },
    )

    /** What is known about uses in progress, by use id. */
    private val open = HashMap<Long, Observation>()

    private data class Observation(val attribution: Attribution, val context: SensorContext)

    private val dao = AppDatabase.getInstance(appContext).sensorEventDao()
    // Outlives stop(): a use that ended just before the VPN stopped still gets saved.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Row id per use id; each use's writes run in order under [writeLock]. */
    private val rowIds = HashMap<Long, Long>()
    private val writeLock = Mutex()

    private val notifier = SensorAlertNotifier(appContext).also { it.createChannel() }
    private val behaviorBlocker = com.privacyguard.app.core.detection.BehaviorBlocker(appContext)

    /**
     * Judges the current view of [use] and saves it: inserted on the first call,
     * updated after. Alerts when the verdict reaches HIGH. [final] once the upload
     * window after the use has passed.
     */
    private fun save(use: SensorUse, o: Observation, final: Boolean = false) {
        io.launch {
            writeLock.withLock {
                val pkg = o.attribution.packageName
                val verdict = SensorAlertRules.evaluate(
                    use = use,
                    attribution = o.attribution,
                    context = o.context,
                    appLabel = pkg?.let(::labelOf),
                    purpose = pkg?.let(::purposeOf) ?: SensorPurpose.UNKNOWN,
                    userConfirmedBefore = pkg != null && dao.expectedCount(pkg, use.sensor.name) > 0,
                    final = final,
                )
                val row = toEntity(use, o).copy(severity = verdict.severity.name, reason = verdict.reason)
                val rowId = rowIds[use.id]?.also { dao.update(row.copy(id = it)) } ?: dao.insert(row).also { rowIds[use.id] = it }
                if (final) rowIds.remove(use.id)
                if (verdict.severity >= Severity.HIGH) {
                    val title = if (use.sensor == SensorType.CAMERA) "Camera used" else "Microphone used"
                    notifier.show(use.id, rowId, use.sensor, pkg, title, verdict)
                }
                // The finished use may change the app's risk score, and with it a block.
                if (final && pkg != null) runCatching { behaviorBlocker.review(pkg) }
                    .onFailure { Log.w(TAG, "Behavior review failed for $pkg: ${it.message}") }
            }
        }
    }

    private fun labelOf(pkg: String): String? = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun purposeOf(pkg: String): SensorPurpose = runCatching {
        val info = appContext.packageManager.getApplicationInfo(pkg, 0)
        val category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) info.category else -1
        val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        SensorPurpose.of(pkg, category, system)
    }.getOrDefault(SensorPurpose.UNKNOWN)

    private fun toEntity(use: SensorUse, o: Observation) = SensorEventEntity(
        sensor = use.sensor.name,
        startTime = use.startTime,
        endTime = use.endTime,
        source = use.source,
        packageName = o.attribution.packageName,
        confidence = o.attribution.confidence.name,
        attributionMethod = o.attribution.method,
        candidates = o.attribution.candidates.take(MAX_CANDIDATES).joinToString(","),
        screenOn = o.context.screenOn,
        locked = o.context.locked,
        quietHours = o.context.quietHours,
        inCall = o.context.inCall,
        openedRecently = o.context.openedRecently == true,
        uploadBytes = o.context.uploadBytes,
        networkBurst = o.context.networkBurst,
    )

    private val monitor = SensorAccessMonitor(appContext, object : SensorSessionTracker.Listener {
        override fun onStarted(use: SensorUse) = handleStarted(use)
        override fun onEnded(use: SensorUse) = handleEnded(use)
    })

    fun start() {
        device.start()
        monitor.start()
    }

    fun stop() {
        monitor.stop()
        device.stop()
    }

    private fun handleStarted(use: SensorUse) {
        val handler = Handler(Looper.myLooper()!!)
        val first = attributor.attribute(use)
        open[use.id] = Observation(first, contextBuilder.atStart(use, first))
        log("started", use, open.getValue(use.id))
        save(use, open.getValue(use.id))
        if (first.confidence == Confidence.CONFIRMED) return
        // The camera is reported busy a moment before the app-ops record is written
        // and before a starting app shows as on screen; one re-check names it.
        handler.postDelayed({
            val previous = open[use.id] ?: return@postDelayed
            val again = attributor.attribute(use)
            if (again.confidence.ordinal < previous.attribution.confidence.ordinal) {
                open[use.id] = Observation(again, contextBuilder.atStart(use, again).copy(
                    // Screen/lock state belong to the start, not to the re-check.
                    screenOn = previous.context.screenOn, locked = previous.context.locked,
                ))
                log("re-checked", use, open.getValue(use.id))
                save(use, open.getValue(use.id))
            }
        }, SensorAttributor.STARTUP_WINDOW_MS)
    }

    private fun handleEnded(use: SensorUse) {
        val started = open.remove(use.id) ?: run {
            val a = attributor.attribute(use)
            Observation(a, contextBuilder.atStart(use, a))
        }
        // The end can confirm what the start could not (the app-ops record is written by now).
        val atEnd = attributor.attribute(use)
        val attribution = if (atEnd.confidence.ordinal < started.attribution.confidence.ordinal) atEnd else started.attribution
        save(use, Observation(attribution, started.context))
        // Wait out the upload window, then judge the whole use.
        Handler(Looper.myLooper()!!).postDelayed({
            val final = Observation(attribution, contextBuilder.withUploads(started.context, use, attribution))
            log("ended", use, final)
            save(use, final, final = true)
        }, SensorContext.BURST_WINDOW_MS)
    }

    private fun log(stage: String, use: SensorUse, o: Observation) {
        val c = o.context
        Log.i(
            TAG,
            "${use.sensor} $stage (${use.source}${use.durationMs?.let { ", ${it} ms" } ?: ""}) — " +
                "${o.attribution.packageName ?: "unknown app"} [${o.attribution.confidence}, ${o.attribution.method}] " +
                "screenOn=${c.screenOn} locked=${c.locked} hour=${c.hourOfDay} quiet=${c.quietHours} call=${c.inCall} " +
                "openedRecently=${c.openedRecently} sinceScreenOn=${c.sinceScreenOnMs} upload=${c.uploadBytes}(${c.uploadPackage})",
        )
    }

    private companion object {
        const val TAG = "SensorWatch"
        const val MAX_CANDIDATES = 12
    }
}
