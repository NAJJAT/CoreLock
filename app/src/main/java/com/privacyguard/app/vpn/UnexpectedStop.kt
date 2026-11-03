package com.privacyguard.app.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why protection last stopped when nobody asked it to, so Home can say so in red
 * (DESIGN_SPEC_1.1.md §6.1). A record only: it describes a stop that already happened and
 * never starts, stops or restarts anything. The next start clears it.
 *
 * Stored, not only in memory, so the reason is still shown after the app process was
 * closed. A crash needs no reason here: the process dies before it could write one, and
 * Home sees "should be on, but nothing runs" (BootReceiver) instead.
 */
object UnexpectedStop {

    enum class Reason {
        /** Android gave the VPN to another VPN app (onRevoke, and our consent is gone). */
        OTHER_VPN,
        /** The packet path kept failing (restart budget used up), or the tunnel could not be rebuilt. */
        PACKET_PATH_FAILED,
    }

    private const val PREFS_NAME = "privacyguard_unexpected_stop"
    private const val KEY_REASON = "reason"

    private val _reason = MutableStateFlow<Reason?>(null)
    @Volatile private var loaded = false

    /** The current reason, or null when the last stop was asked for (or protection runs). */
    fun reason(context: Context): StateFlow<Reason?> {
        load(context)
        return _reason.asStateFlow()
    }

    fun record(context: Context, reason: Reason) {
        load(context)
        prefs(context).edit().putString(KEY_REASON, reason.name).apply()
        _reason.value = reason
    }

    fun clear(context: Context) {
        load(context)
        if (_reason.value == null) return
        prefs(context).edit().remove(KEY_REASON).apply()
        _reason.value = null
    }

    /**
     * onRevoke also runs when the user disconnects in Android's VPN settings or turns
     * Always-on off: the user's own choice, not an unexpected stop. Only a revoke after
     * which another app holds the VPN consent counts.
     */
    fun forRevoke(consentStillOurs: Boolean): Reason? = if (consentStillOurs) null else Reason.OTHER_VPN

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _reason.value = prefs(context).getString(KEY_REASON, null)
                ?.let { stored -> Reason.entries.firstOrNull { it.name == stored } }
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
