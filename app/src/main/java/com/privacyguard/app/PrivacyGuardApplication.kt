package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.utils.NotificationHelper

class PrivacyGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        BlocklistManager.initialize(this)
    }
}
