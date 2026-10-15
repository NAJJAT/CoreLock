package com.privacyguard.app.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts protection when it died with the app process (crash, low-memory kill),
 * which nothing inside the process can do, and which Android does not always
 * repair: on a test phone it did not restart the service after a crash, even with
 * Always-on VPN.
 *
 * A deadman alarm: while the service starts or runs, it pushes the alarm
 * [DEADMAN_MS] ahead every [REARM_MS]. If the process dies the alarm goes off, and
 * if protection should be on but is not running, the receiver starts it again. When
 * that is not possible (consent gone, too many restarts, Android refuses the start)
 * it posts "Protection stopped. Tap to restart." A deliberate stop disarms it.
 *
 * Allow-while-idle, because Power saving defers a plain alarm of an app without a
 * process indefinitely (Android 13: `battery_saver=+364d` in dumpsys alarm), and
 * Doze defers it too. Exact when the app may set exact alarms, because since
 * Android 12 only an exact alarm lets a background app start a foreground service,
 * and the VPN service is one. Without that permission (not pre-granted on Android
 * 14+) the alarm can only post the notification. Allow-while-idle alarms are rate
 * limited (under Power saving about one per 9 minutes), which also spaces restarts.
 */
object ProtectionWatchdog {
    const val DEADMAN_MS = 30_000L
    const val REARM_MS = 10_000L

    /** Restarts from the alarm in [RESTART_WINDOW_MS]: a start that keeps crashing must not loop. */
    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_MS = 30 * 60_000L

    private const val TAG = "ProtectionWatchdog"
    private const val PREFS = "privacyguard_watchdog"
    private const val KEY_RESTARTS = "restarts"

    enum class Outcome { RESTARTED, NO_CONSENT, TOO_MANY_RESTARTS, REFUSED }

    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + DEADMAN_MS
        runCatching {
            if (canScheduleExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
            }
        }.onFailure { Log.w(TAG, "arm failed: ${it.javaClass.simpleName}") }
    }

    fun disarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    /** Protection should be on (not stopped on purpose) and nothing is running it. */
    fun protectionLost(shouldBeActive: Boolean, running: Boolean): Boolean = shouldBeActive && !running

    /** Starts the VPN service again if consent, the restart budget and Android allow it. */
    fun tryRestart(context: Context, now: Long = System.currentTimeMillis()): Outcome {
        // Another VPN app took the permission: starting would only fail.
        if (VpnService.prepare(context) != null) {
            BootReceiver.markVpnStopped(context)
            return Outcome.NO_CONSENT
        }
        if (!tryAcquireRestart(context, now)) return Outcome.TOO_MANY_RESTARTS
        return try {
            ContextCompat.startForegroundService(
                context, com.privacyguard.platform.android.PrivacyVpnService.startIntent(context))
            Outcome.RESTARTED
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one: no exact alarm.
            Log.w(TAG, "restart refused: ${e.javaClass.simpleName}")
            Outcome.REFUSED
        } catch (e: SecurityException) {
            Log.w(TAG, "restart refused: ${e.javaClass.simpleName}")
            Outcome.REFUSED
        }
    }

    /** Kept in preferences: every restart after a crash runs in a new process. */
    private fun tryAcquireRestart(context: Context, now: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = prefs.getString(KEY_RESTARTS, null).orEmpty().split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it in 0 until RESTART_WINDOW_MS }
        if (recent.size >= MAX_RESTARTS) return false
        prefs.edit().putString(KEY_RESTARTS, (recent + now).joinToString(",")).apply()
        return true
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    // Foreground broadcast queue: on the background one the receiver ran 26 s after
    // the alarm went off, with protection down all that time.
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ProtectionWatchdogReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only the app's own alarm reaches it. */
class ProtectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldBeActive = BootReceiver.shouldBeActive(context)
        val running = com.privacyguard.platform.android.PrivacyVpnService.isRunning
        if (!ProtectionWatchdog.protectionLost(shouldBeActive, running)) return
        val outcome = ProtectionWatchdog.tryRestart(context)
        Log.w("ProtectionWatchdog", "Protection lost with the process; restart: $outcome")
        if (outcome != ProtectionWatchdog.Outcome.RESTARTED) {
            com.privacyguard.platform.android.NotificationHelper(context).postProtectionStopped()
        }
    }
}
