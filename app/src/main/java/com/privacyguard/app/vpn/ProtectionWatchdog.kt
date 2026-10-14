package com.privacyguard.app.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Tells the user when protection died with the app process (crash, low-memory
 * kill), which nothing inside the process can report, and which Android does not
 * always repair: on a test phone it did not restart the service after a crash,
 * even with Always-on VPN.
 *
 * A deadman alarm: while the service starts or runs, it pushes the alarm
 * [DEADMAN_MS] ahead every [REARM_MS]. If the process dies the alarm goes off, and
 * if protection should be on but is not running, "Protection stopped. Tap to
 * restart." is posted. A deliberate stop disarms it.
 *
 * The alarm is inexact (no exact-alarm permission): it fires 30 to 60 s after the
 * last push, later if the phone is in Doze.
 */
object ProtectionWatchdog {
    const val DEADMAN_MS = 30_000L
    const val REARM_MS = 10_000L
    private const val WINDOW_MS = 30_000L

    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + DEADMAN_MS, WINDOW_MS, pending(context))
        }
    }

    fun disarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    /** Protection should be on (not stopped on purpose) and nothing is running it. */
    fun shouldNotify(shouldBeActive: Boolean, running: Boolean): Boolean = shouldBeActive && !running

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ProtectionWatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not exported: only the app's own alarm reaches it. */
class ProtectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldBeActive = BootReceiver.shouldBeActive(context)
        val running = com.privacyguard.platform.android.PrivacyVpnService.isRunning
        if (ProtectionWatchdog.shouldNotify(shouldBeActive, running)) {
            com.privacyguard.platform.android.NotificationHelper(context).postProtectionStopped()
        }
    }
}
