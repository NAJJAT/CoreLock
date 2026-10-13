package com.privacyguard.vpn.interception

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import java.net.Socket

/** Consumer builds never inspect HTTPS payloads. */
object TlsInterceptionFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, db: AppDatabase, protect: (Socket) -> Boolean): TlsInterception =
        TlsInterception.None

    /** HTTPS inspection does not exist in this build; there is nothing to consent to. */
    @Suppress("UNUSED_PARAMETER")
    fun recordConsent(context: Context) = Unit

    /** Whether HTTPS payloads are being decrypted. Never, in this build. */
    @Suppress("UNUSED_PARAMETER")
    fun isInspectionActive(context: Context): Boolean = false
}
