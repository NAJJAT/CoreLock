package com.privacyguard.app.core.power

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android may stop protection in the background to save battery. The VPN runs
 * as a foreground service, which stock Android keeps alive, but some phones still stop
 * optimized apps, and "Restricted" background use stops them on any phone.
 *
 * Read only: asking for the exemption directly (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
 * is limited by Play policy, so the user is sent to the system screen instead.
 */
object BatteryCheck {

    enum class State {
        /** Android 9+ "Restricted" background use: protection can be stopped on any phone. */
        RESTRICTED,
        /** Battery optimization on (the Android default): some phones stop the app. */
        OPTIMIZED,
        /** "Unrestricted" / not optimized. */
        UNRESTRICTED,
    }

    fun state(context: Context): State {
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        }.getOrDefault(false)
        if (restricted) return State.RESTRICTED
        val ignoring = runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        return if (ignoring) State.UNRESTRICTED else State.OPTIMIZED
    }

    /**
     * Opens PrivacyGuard's own app info (Battery is one tap from there on every version),
     * else the list of optimized apps, else Settings.
     */
    fun openSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }
}
