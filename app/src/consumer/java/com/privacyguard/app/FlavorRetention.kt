package com.privacyguard.app

import android.content.Context

object FlavorRetention {
    /**
     * Consumer builds never capture payloads, so any rows left by a build from
     * before the enterprise split are deleted outright.
     */
    fun payloadCutoffMs(context: Context): Long = Long.MAX_VALUE

    suspend fun afterPrune(context: Context) = Unit
}
