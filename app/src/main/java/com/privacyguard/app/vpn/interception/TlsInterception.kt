package com.privacyguard.vpn.interception

import com.privacyguard.core.session.Session

/**
 * The only seam between the shared VPN pipeline and HTTPS payload inspection.
 *
 * The real implementation (MITM engine, CA, payload logging, SIEM shipping)
 * lives in the enterprise source set. Consumer builds get [None], so none of
 * that code, nor the CA key it generates, exists in the consumer APK.
 * Each flavor supplies a `TlsInterceptionFactory` that returns one of these.
 */
interface TlsInterception {

    /** True when inspection is switched on; gates all capture work. */
    val isEnabled: Boolean

    /** Drop UDP/443 so QUIC apps fall back to TCP/TLS, where inspection works. */
    val blockQuic: Boolean

    /** Whether a new TLS session to [sni] should be redirected to the local MITM server. */
    fun shouldIntercept(ownerPackage: String?, sni: String): Boolean

    /**
     * Starts a local TLS server for [session] that accepts only the loopback
     * peer bound to [clientPort]. Returns that server's port, or -1 on failure.
     */
    fun intercept(session: Session, clientPort: Int): Int

    /** Records that [sni] rejected interception, so it is passed through next time. */
    fun markPinned(sni: String)

    /** Logs a metadata-only entry for a TLS session that is not being decrypted. */
    fun logNotDecrypted(session: Session, sizeBytes: Int)

    /** Feeds plaintext HTTP bytes for [session]; complete messages are logged and shipped. */
    fun capture(direction: String, bytes: ByteArray, session: Session)

    /** Inspection is not part of this build. */
    object None : TlsInterception {
        override val isEnabled = false
        override val blockQuic = false
        override fun shouldIntercept(ownerPackage: String?, sni: String) = false
        override fun intercept(session: Session, clientPort: Int) = -1
        override fun markPinned(sni: String) = Unit
        override fun logNotDecrypted(session: Session, sizeBytes: Int) = Unit
        override fun capture(direction: String, bytes: ByteArray, session: Session) = Unit
    }
}
