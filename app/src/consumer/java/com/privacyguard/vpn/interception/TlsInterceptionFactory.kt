package com.privacyguard.vpn.interception

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import java.net.Socket

/** Consumer builds never inspect HTTPS payloads. */
object TlsInterceptionFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, db: AppDatabase, protect: (Socket) -> Boolean): TlsInterception =
        TlsInterception.None

    /** Whether HTTPS payloads are being decrypted. Never, in this build. */
    @Suppress("UNUSED_PARAMETER")
    fun isInspectionActive(context: Context): Boolean = false
}
