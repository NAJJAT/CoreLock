package com.privacyguard.app

import android.content.Context
import com.privacyguard.vpn.mitm.CaManager
import com.privacyguard.vpn.mitm.MitmConfig

object FlavorRetention {
    /**
     * Decrypted payloads are the most sensitive data the app holds: keep them for
     * the MDM-controlled MITM retention period, and none at all once MITM is off.
     */
    fun payloadCutoffMs(context: Context): Long {
        val config = MitmConfig(context)
        if (!config.isEnabled) return Long.MAX_VALUE
        return System.currentTimeMillis() - config.retentionDays.toLong() * 86_400_000L
    }

    /**
     * With MITM off, the CA key is deleted so it can never sign again (M-7). The MITM
     * screen does this itself; this also covers MITM being turned off by MDM.
     */
    suspend fun afterPrune(context: Context) {
        if (!MitmConfig(context).isEnabled) CaManager(context).reset()
    }
}
