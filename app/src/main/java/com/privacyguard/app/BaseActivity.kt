package com.privacyguard.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.privacyguard.app.utils.LocaleHelper

open class BaseActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleHelper.getSavedLanguage(newBase)
        val context = LocaleHelper.updateResources(newBase, languageCode)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val languageCode = LocaleHelper.getSavedLanguage(this)
        LocaleHelper.setAppLocale(this, languageCode)
    }
}
