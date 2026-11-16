package com.privacyguard.app.core.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.privacyguard.platform.android.NotificationHelper

/**
 * Whether CoreLock can tell the user that protection stopped (N-4). "Protection stopped"
 * is a notification on [NotificationHelper.CHANNEL_ALERTS], posted by the watcher when the
 * VPN process dies (ProtectionWatchdog). With the app's notifications off (Android 13+ asks
 * at first start; any version can turn them off in settings) or that channel turned off, a
 * crash ends protection with no signal at all, so Home says so before the user relies on it.
 */
object NotificationCheck {
    private const val TAG = "NotificationCheck"

    /**
     * [alertsImportance]: the alerts channel's importance, or null when there is no channel
     * yet (before the first notification, or Android 7). A channel the user set to low or
     * silent still shows the notice in the shade; only "off" (NONE) hides it.
     */
    fun blocked(appEnabled: Boolean, alertsImportance: Int?): Boolean =
        !appEnabled || alertsImportance == NotificationManager.IMPORTANCE_NONE

    fun isBlocked(context: Context): Boolean {
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(NotificationHelper.CHANNEL_ALERTS)?.importance
        } else null
        return blocked(appEnabled, importance)
    }

    /** This app's notification settings; the app's info page where that screen is missing. */
    fun openSettings(context: Context) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in intents) {
            val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "settings screen not available: ${it.javaClass.simpleName}") }
                .isSuccess
            if (opened) return
        }
    }
}
