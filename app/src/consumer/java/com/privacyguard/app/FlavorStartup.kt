package com.privacyguard.app

import android.app.Application

/** Consumer builds have no managed configuration or other flavor-specific startup work. */
object FlavorStartup {
    fun onCreate(app: Application) = Unit
}
