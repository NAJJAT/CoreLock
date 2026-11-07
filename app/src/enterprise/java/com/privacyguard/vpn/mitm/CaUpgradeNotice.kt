package com.privacyguard.vpn.mitm

import android.content.Context
import android.util.Log
import java.security.cert.X509Certificate

/**
 * One Home card after the update from 1.1 (owner's decision, 2026-11-05): 1.2 replaces
 * the CA of 1.1 ("PrivacyGuard Enterprise CA", [CaPolicy]), so for users who had
 * inspection on it stops working until they install the new one. The card says so and
 * installs it. Nobody else sees it, and once dismissed or done it never comes back.
 *
 * Pending is set from two places, whichever runs first after the update: the app's start
 * ([checkAtStart], the 1.1 CA is still stored) and [CaManager] replacing the 1.1 CA
 * ([recordReplaced]). The rules are pure, so they are tested on the JVM.
 */
object CaUpgradeNotice {

    private const val TAG = "CaUpgradeNotice"
    private const val KEY = "ca_upgrade_notice"

    enum class State(val stored: String) {
        NONE("none"), PENDING("pending"), DONE("done");

        companion object {
            fun parse(value: String?): State = values().firstOrNull { it.stored == value } ?: NONE
        }
    }

    private fun isLegacy(ca: CaPolicy.CaInfo?) =
        ca != null && ca.cn == CaPolicy.LEGACY_CN && ca.org == CaPolicy.LEGACY_ORG

    /** At app start: the 1.1 CA is still stored and inspection is on. */
    internal fun atStart(state: State, inspectionOn: Boolean, storedCa: CaPolicy.CaInfo?): State =
        if (state == State.NONE && inspectionOn && isLegacy(storedCa)) State.PENDING else state

    /** [CaManager] just replaced [oldCa]. */
    internal fun onReplaced(state: State, inspectionOn: Boolean, oldCa: CaPolicy.CaInfo): State =
        if (state == State.NONE && inspectionOn && isLegacy(oldCa)) State.PENDING else state

    /** Shown while pending, inspection is on, and Android doesn't trust the new CA yet. */
    fun show(state: State, inspectionOn: Boolean, newCaTrusted: Boolean): Boolean =
        state == State.PENDING && inspectionOn && !newCaTrusted

    /** Once Android trusts the new CA, the notice is finished for good. */
    fun afterCheck(state: State, newCaTrusted: Boolean): State =
        if (state == State.PENDING && newCaTrusted) State.DONE else state

    // ── Stored state (mitm_prefs) ─────────────────────────────────────────────

    internal fun read(context: Context): State =
        State.parse(MitmConfig.prefs(context).getString(KEY, null))

    private fun write(context: Context, state: State) {
        MitmConfig.prefs(context).edit().putString(KEY, state.stored).apply()
    }

    private fun inspectionOn(context: Context) = MitmConfig.enabledIn(context)

    /** From the app's start, off the main thread. */
    fun checkAtStart(context: Context) {
        val before = read(context)
        if (before != State.NONE) return
        val after = atStart(before, inspectionOn(context), CaManager.storedCertificate(context)?.let(CaManager::infoOf))
        if (after != before) { write(context, after); Log.i(TAG, "Notice pending: the 1.1 CA is replaced") }
    }

    internal fun recordReplaced(context: Context, oldCa: X509Certificate) {
        val before = read(context)
        val after = onReplaced(before, inspectionOn(context), CaManager.infoOf(oldCa))
        if (after != before) { write(context, after); Log.i(TAG, "Notice pending: the 1.1 CA was replaced") }
    }

    /** For Home, off the main thread: true while the card should show. */
    fun due(context: Context, newCaTrusted: () -> Boolean): Boolean {
        val state = read(context)
        if (state != State.PENDING) return false
        val trusted = newCaTrusted()
        val next = afterCheck(state, trusted)
        if (next != state) write(context, next)
        return show(next, inspectionOn(context), trusted)
    }

    fun dismiss(context: Context) = write(context, State.DONE)

    /** "Install certificate" on the card: the HTTPS-inspection screen starts the install once. */
    @Volatile private var installRequested = false
    fun requestInstall() { installRequested = true }
    fun consumeInstallRequest(): Boolean = installRequested.also { installRequested = false }
}
