package com.privacyguard.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.privacyguard.app.MainActivity
import com.privacyguard.app.R
import com.privacyguard.app.core.monitor.NetworkMonitor
import com.privacyguard.app.service.notification.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object KillSwitch {

    private const val PREF_NAME = "killswitch_prefs"
    private const val KEY_ENABLED = "killswitch_enabled"
    private const val CHANNEL_ID = "killswitch_channel"
    private const val NOTIFICATION_ID = 1003

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var notificationService: NotificationService

    @Volatile
    private var enabled = false

    @Volatile
    private var monitoring = false

    @Volatile
    private var suppressNextStop = false

    private var monitorJob: Job? = null
    private var networkMonitor: NetworkMonitor? = null
    private var monitorScope: CoroutineScope? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        notificationService = NotificationService(appContext)
        createNotificationChannel()
    }

    fun enable() {
        enabled = true
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
    }

    fun disable() {
        enabled = false
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        stopMonitoring()
    }

    fun isEnabled(): Boolean = enabled

    fun startMonitoring(context: Context) {
        if (!enabled || monitoring) return
        appContext = context.applicationContext
        monitoring = true
        suppressNextStop = false
        monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        networkMonitor = NetworkMonitor(appContext).also { monitor ->
            monitor.start { hasInternet ->
                if (!hasInternet && enabled && PrivacyVpnService.isRunning) {
                    showKillSwitchNotification(appContext, appContext.getString(R.string.kill_switch_network_lost))
                }
            }
        }

        monitorJob = monitorScope?.launch {
            var previousRunning = PrivacyVpnService.isRunning
            while (monitoring) {
                val currentRunning = PrivacyVpnService.isRunning
                if (enabled && previousRunning && !currentRunning) {
                    if (suppressNextStop) {
                        suppressNextStop = false
                    } else {
                        handleUnexpectedVpnStop()
                    }
                }
                previousRunning = currentRunning
                delay(1000)
            }
        }
    }

    fun stopMonitoring(userInitiated: Boolean = false) {
        if (userInitiated) {
            suppressNextStop = true
        }
        monitoring = false
        monitorJob?.cancel()
        monitorJob = null
        networkMonitor?.stop()
        networkMonitor = null
        monitorScope?.cancel()
        monitorScope = null
    }

    private fun handleUnexpectedVpnStop() {
        notificationService.killSwitchActivated()
        showKillSwitchNotification(appContext, appContext.getString(R.string.kill_switch_triggered_desc))
        monitorScope?.launch {
            delay(2500)
            val restartIntent = Intent(appContext, PrivacyVpnService::class.java)
            appContext.startService(restartIntent)
        }
    }

    private fun showKillSwitchNotification(context: Context, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.kill_switch_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (!::appContext.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kill Switch",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }
}
