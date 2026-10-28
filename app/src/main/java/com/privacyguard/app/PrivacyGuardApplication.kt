package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecureSecretStore
import com.privacyguard.app.workers.BlocklistUpdateWorker
import com.privacyguard.app.workers.PruneWorker

class PrivacyGuardApplication : Application() {
    companion object {
        /**
         * Process-wide scope for work that must outlive one component, such as the
         * VPN service saving its last history rows while it shuts down.
         */
        val appScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
                com.privacyguard.vpn.WorkerGuard.coroutineHandler,
        )

        const val WATCHDOG_PROCESS_SUFFIX = ":watchdog"

        fun isWatchdogProcess(processName: String?): Boolean =
            processName?.endsWith(WATCHDOG_PROCESS_SUFFIX) == true
    }

    private fun currentProcessName(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()

    override fun onCreate() {
        super.onCreate()
        // The :watchdog process (ProtectionWatcherService) only waits for the VPN
        // process to die and then sets one alarm or posts one notification. None of
        // the setup below belongs there: no Keystore or SQLCipher, no database, no
        // blocklists, no GeoIP, no workers.
        if (isWatchdogProcess(currentProcessName())) return
        com.privacyguard.app.core.utils.ByteFormat.appContext = this
        SecureSecretStore.getInstance(this).bootstrap()
        AppSecurityMonitor.refresh(this)
        removeUnusedNotificationChannels()
        BlocklistManager.initialize(this)
        // Country table for the Stats map and connection details (loads on its own thread).
        com.privacyguard.app.core.geoip.GeoIpResolver.init(this)
        FlavorStartup.onCreate(this)
        PruneWorker.schedule(this)
        BlocklistUpdateWorker.schedulePeriodic(this)
    }

    /**
     * Earlier builds created three channels that nothing ever posted to; they only
     * cluttered Android's notification settings. Deleting a missing channel is a no-op.
     */
    private fun removeUnusedNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        listOf("privacyguard_high", "privacyguard_normal", "privacyguard_low")
            .forEach { runCatching { manager.deleteNotificationChannel(it) } }
    }
}
