package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecureSecretStore
import com.privacyguard.app.core.utils.NotificationHelper
import com.privacyguard.app.workers.PruneWorker

class PrivacyGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SecureSecretStore.getInstance(this).bootstrap()
        AppSecurityMonitor.refresh(this)
        NotificationHelper.createNotificationChannels(this)
        BlocklistManager.initialize(this)
        FlavorStartup.onCreate(this)
        PruneWorker.schedule(this)
    }
}
