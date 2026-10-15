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
 * Always-on VPN (V-18).
 *
 * Two alarms, neither of which wakes the phone while protection is healthy:
 *
 * - **Restart**: [ProtectionWatcher] sees the VPN process die at once and calls
 *   [onProtectionProcessDied], which sets one exact allow-while-idle alarm
 *   [RESTART_DELAY_MS] ahead. Since Android 12 only an exact alarm lets a background
 *   app start a foreground service, and the VPN service is one. Exact alarms need
 *   "Alarms & reminders" access (not pre-granted on Android 14+); without it the
 *   watcher posts "Protection stopped" at once instead.
 * - **Backup**: for when the watcher died too (the whole app killed at once). The
 *   service keeps pushing a non-wakeup alarm [BACKUP_MS] ahead; a non-wakeup alarm
 *   never wakes the phone, it is delivered the next time the phone wakes anyway.
 *   Allow-while-idle, because Power saving defers a plain alarm of an app without a
 *   process indefinitely (Android 13: `battery_saver=+364d` in dumpsys alarm).
 *
 * Either alarm's receiver starts the service again if protection should be on and
 * is not, the Settings switch "Automatic restart after a crash" is on, and the
 * restart budget allows it; otherwise it posts "Protection stopped". A deliberate
 * stop disarms both.
 */
object ProtectionWatchdog {
    const val RESTART_DELAY_MS = 1_000L
    const val BACKUP_MS = 15 * 60_000L
    const val BACKUP_REARM_MS = 5 * 60_000L

    /** Restarts from the alarm in [RESTART_WINDOW_MS]: a start that keeps crashing must not loop. */
    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_MS = 30 * 60_000L

    private const val TAG = "ProtectionWatchdog"
    private const val PREFS = "privacyguard_watchdog"
    private const val KEY_RESTARTS = "restarts"
    private const val REQUEST_BACKUP = 0
    private const val REQUEST_RESTART = 1

    enum class Outcome { RESTARTED, NO_CONSENT, DISABLED, TOO_MANY_RESTARTS, REFUSED }

    /** Exact alarms allowed: needed for a restart from the background on Android 12+. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Called by the service while it starts or runs; see [BACKUP_REARM_MS]. */
    fun armBackup(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + BACKUP_MS
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pending(context, REQUEST_BACKUP))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pending(context, REQUEST_BACKUP))
            }
        }.onFailure { Log.w(TAG, "backup alarm failed: ${it.javaClass.simpleName}") }
    }

    /** A deliberate stop: no restart, no "Protection stopped". */
    fun disarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, REQUEST_BACKUP))
        am.cancel(pending(context, REQUEST_RESTART))
    }

    /**
     * In the `:watchdog` process, right after the VPN process died. Must stay light:
     * no SettingsPreferences (it unwraps Keystore secrets); the receiver, in a fresh
     * main process, checks the setting.
     */
    fun onProtectionProcessDied(context: Context) {
        if (!canScheduleExact(context)) {
            // No restart is possible from the background: say so now, not later.
            com.privacyguard.platform.android.NotificationHelper(context).postProtectionStopped()
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending(context, REQUEST_RESTART))
        }.onFailure {
            Log.w(TAG, "restart alarm failed: ${it.javaClass.simpleName}")
            com.privacyguard.platform.android.NotificationHelper(context).postProtectionStopped()
        }
    }

    /** Protection should be on (not stopped on purpose) and no service is running or starting it. */
    fun protectionLost(shouldBeActive: Boolean, running: Boolean): Boolean = shouldBeActive && !running

    /** Starts the VPN service again if consent, the setting, the restart budget and Android allow it. */
    fun tryRestart(context: Context, now: Long = System.currentTimeMillis()): Outcome {
        // Another VPN app took the permission: starting would only fail.
        if (VpnService.prepare(context) != null) {
            BootReceiver.markVpnStopped(context)
            return Outcome.NO_CONSENT
        }
        if (!com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(context).autoRestart.value) {
            return Outcome.DISABLED
        }
        if (!tryAcquireRestart(context, now)) return Outcome.TOO_MANY_RESTARTS
        return try {
            ContextCompat.startForegroundService(
                context, com.privacyguard.platform.android.PrivacyVpnService.startIntent(context))
            Outcome.RESTARTED
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one: not an exact alarm.
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

    // Foreground broadcast queue: on the background one the receiver ran 26 s after
    // the alarm went off, with protection down all that time.
    private fun pending(context: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, ProtectionWatchdogReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only the app's own alarms reach it. Runs in the main process. */
class ProtectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldBeActive = BootReceiver.shouldBeActive(context)
        val service = com.privacyguard.platform.android.PrivacyVpnService
        if (!ProtectionWatchdog.protectionLost(shouldBeActive, service.isRunning || service.isCreated)) return
        val outcome = ProtectionWatchdog.tryRestart(context)
        Log.w("ProtectionWatchdog", "Protection lost with the process; restart: $outcome")
        if (outcome != ProtectionWatchdog.Outcome.RESTARTED) {
            com.privacyguard.platform.android.NotificationHelper(context).postProtectionStopped()
        }
    }
}
