package com.privacyguard.app.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Pause protection for 5 minutes" (owner's decision D1, DESIGN_SPEC_1.1.md §8).
 *
 * A pause is the normal deliberate stop, plus a stored end time. The stop path is the
 * existing one on purpose: it disarms the crash watchdog, so the pause is never taken for
 * a crash. Protection comes back by itself, and every way back is independent:
 *
 * 1. an exact allow-while-idle alarm at the end time ([PauseReceiver]); since Android 12
 *    only an exact alarm lets a background app start the VPN's foreground service;
 * 2. "Resume now" in the notification, which starts the service directly;
 * 3. boot and app update ([BootReceiver]): a pause never outlives a restart;
 * 4. every start of the app process ([recheck]): an end time that passed resumes now, a
 *    future one re-arms the alarm (a force stop deletes an app's alarms).
 *
 * The end time is stored synchronously before the stop, so a process death right after
 * still knows. It is wall-clock time, so it survives a reboot, and it is never more than
 * [PAUSE_MS] ahead: moving the clock back can't stretch a pause.
 *
 * Not offered where the return can't be guaranteed ([availability]): without exact-alarm
 * access, and under Always-on VPN (Android restarts the VPN itself, and with "Block
 * connections without VPN" a pause would cut the internet instead).
 */
object ProtectionPause {

    const val PAUSE_MS = 5 * 60_000L

    private const val TAG = "ProtectionPause"
    private const val PREFS = "privacyguard_pause"
    private const val KEY_UNTIL = "until"
    private const val REQUEST_RESUME = 0
    internal const val ACTION_RESUME = "com.privacyguard.action.RESUME_AFTER_PAUSE"

    enum class Availability { AVAILABLE, NEEDS_EXACT_ALARMS, ALWAYS_ON, NOT_RUNNING }

    /** What to do with a stored pause at [now]. */
    sealed interface Decision {
        data object None : Decision
        data object ResumeNow : Decision
        /** Still paused; [until] is never more than [PAUSE_MS] after now. */
        data class Wait(val until: Long) : Decision
    }

    /** Pure: [until] null means no pause. Kept apart from Android so it can be tested. */
    fun decide(until: Long?, now: Long): Decision = when {
        until == null -> Decision.None
        until <= now -> Decision.ResumeNow
        else -> Decision.Wait(minOf(until, now + PAUSE_MS))
    }

    /** Pure: whether a pause can be promised to end by itself. */
    fun availabilityOf(running: Boolean, sdk: Int, canScheduleExact: Boolean, alwaysOn: Boolean): Availability = when {
        !running -> Availability.NOT_RUNNING
        alwaysOn -> Availability.ALWAYS_ON
        sdk >= Build.VERSION_CODES.S && !canScheduleExact -> Availability.NEEDS_EXACT_ALARMS
        else -> Availability.AVAILABLE
    }

    private val _until = MutableStateFlow<Long?>(null)
    @Volatile private var loaded = false

    /** The end of the current pause, or null. For Home and the fix flow. */
    fun pausedUntil(context: Context): StateFlow<Long?> {
        load(context)
        return _until.asStateFlow()
    }

    fun availability(context: Context): Availability = availabilityOf(
        running = PrivacyVpnService.isRunning,
        sdk = Build.VERSION.SDK_INT,
        canScheduleExact = ProtectionWatchdog.canScheduleExact(context),
        alwaysOn = KillSwitch.alwaysOn,
    )

    /** Pauses now; false (nothing changed) unless [availability] is AVAILABLE. */
    fun pause(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        if (availability(app) != Availability.AVAILABLE) return false
        val until = now + PAUSE_MS
        // Order matters: the way back exists before protection goes down.
        store(app, until)
        if (!arm(app, until)) {
            store(app, null)
            return false
        }
        NotificationHelper(app).postPaused(until)
        app.startService(PrivacyVpnService.stopIntent(app))
        return true
    }

    /**
     * Protection came up, by any path (alarm, Resume now, the user's switch, boot): the
     * pause is over. Called by the service once the tunnel is up.
     */
    fun onProtectionStarted(context: Context) {
        load(context)
        if (_until.value == null) return
        end(context.applicationContext)
    }

    /** The user turned protection off while paused: stay off, no automatic return. */
    fun cancel(context: Context) {
        load(context)
        if (_until.value == null) return
        end(context.applicationContext)
    }

    /** At every app process start: resume an overdue pause, re-arm a running one. */
    fun recheck(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        load(app)
        when (val d = decide(_until.value, now)) {
            Decision.None -> Unit
            Decision.ResumeNow -> resume(app)
            is Decision.Wait -> {
                if (d.until != _until.value) store(app, d.until)
                if (!arm(app, d.until)) resume(app)
            }
        }
    }

    /** Ends the pause and starts protection; if Android refuses, says so (never silent). */
    fun resume(context: Context) {
        val app = context.applicationContext
        end(app)
        if (VpnService.prepare(app) != null) {
            // Another VPN app holds the permission now: starting would only fail.
            NotificationHelper(app).postProtectionStopped()
            return
        }
        try {
            ContextCompat.startForegroundService(app, PrivacyVpnService.startIntent(app))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is one: tell the user instead.
            Log.w(TAG, "resume refused: ${e.javaClass.simpleName}")
            NotificationHelper(app).postProtectionStopped()
        } catch (e: SecurityException) {
            Log.w(TAG, "resume refused: ${e.javaClass.simpleName}")
            NotificationHelper(app).postProtectionStopped()
        }
    }

    private fun end(app: Context) {
        store(app, null)
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app))
        NotificationHelper(app).cancelPaused()
    }

    /** False when no alarm could be set: the caller must not rely on one. */
    private fun arm(app: Context, until: Long): Boolean {
        val am = app.getSystemService(AlarmManager::class.java) ?: return false
        if (!ProtectionWatchdog.canScheduleExact(app)) return false
        return runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, alarmIntent(app))
        }.onFailure { Log.w(TAG, "resume alarm failed: ${it.javaClass.simpleName}") }.isSuccess
    }

    @android.annotation.SuppressLint("ApplySharedPref")  // deliberate, see below
    private fun store(app: Context, until: Long?) {
        // commit, not apply: the stop that follows may be the last thing this process does.
        val edit = prefs(app).edit()
        if (until == null) edit.remove(KEY_UNTIL) else edit.putLong(KEY_UNTIL, until)
        edit.commit()
        _until.value = until
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context.applicationContext)
            _until.value = if (p.contains(KEY_UNTIL)) p.getLong(KEY_UNTIL, 0L) else null
            loaded = true
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tests only: forget the cached state (the object outlives one Robolectric app). */
    internal fun resetForTest() { loaded = false; _until.value = null }

    // Explicit and immutable; the receiver is not exported, so only this alarm reaches it.
    // Foreground queue: on the background one a receiver ran 26 s late (ProtectionWatchdog).
    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_RESUME,
        Intent(app, PauseReceiver::class.java).setAction(ACTION_RESUME).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only [ProtectionPause]'s own alarm reaches it. */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProtectionPause.ACTION_RESUME) return
        // Through recheck, not resume: a pause cancelled meanwhile stays cancelled.
        ProtectionPause.recheck(context)
    }
}
