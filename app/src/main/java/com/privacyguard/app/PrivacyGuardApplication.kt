package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.core.app.AppResolver
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.utils.NotificationHelper
import com.privacyguard.app.utils.LocaleHelper
import com.privacyguard.app.workers.BlocklistUpdateWorker

class PrivacyGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val languageCode = LocaleHelper.getSavedLanguage(this)
        LocaleHelper.setAppLocale(this, languageCode)
        AppResolver.initialize(this)
        NotificationHelper.createNotificationChannels(this)
        BlocklistManager.initialize(this)
        BlocklistUpdateWorker.schedulePeriodic(this)
    }
}
