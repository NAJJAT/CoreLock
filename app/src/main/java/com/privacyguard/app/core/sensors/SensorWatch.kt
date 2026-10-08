package com.privacyguard.app.core.sensors

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.privacyguard.app.data.local.preferences.SettingsPreferences

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
        // Wait out the upload window, then judge the whole use.
        Handler(Looper.myLooper()!!).postDelayed({
            val final = Observation(attribution, contextBuilder.withUploads(started.context, use, attribution))
            log("ended", use, final)
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
    }
}
