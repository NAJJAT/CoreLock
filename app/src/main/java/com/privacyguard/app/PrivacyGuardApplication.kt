package com.privacyguard.app

import android.app.Application
import com.privacyguard.app.utils.LocaleHelper

class PrivacyGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val languageCode = LocaleHelper.getSavedLanguage(this)
        LocaleHelper.setAppLocale(this, languageCode)
    }
}
