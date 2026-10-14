package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecureSecretStore
import com.privacyguard.app.core.utils.NotificationHelper
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
    }

    override fun onCreate() {
        super.onCreate()
        com.privacyguard.app.core.utils.ByteFormat.appContext = this
        SecureSecretStore.getInstance(this).bootstrap()
        AppSecurityMonitor.refresh(this)
        NotificationHelper.createNotificationChannels(this)
        BlocklistManager.initialize(this)
        // Country table for the Stats map and connection details (loads on its own thread).
        com.privacyguard.app.core.geoip.GeoIpResolver.init(this)
        FlavorStartup.onCreate(this)
        PruneWorker.schedule(this)
    }
}
