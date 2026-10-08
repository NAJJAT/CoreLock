package com.privacyguard.core.metadata

/**
 * Encryption classification of a network connection.
 * Derived from port number and TLS ClientHello inspection — no decryption.
 */
enum class EncryptionStatus {
    /** Not yet classified (first packet not yet inspected). */
    UNKNOWN,
    /** Plaintext connection (e.g. HTTP on port 80). */
    CLEARTEXT,
    /** TLS with deprecated version (1.0 or 1.1). */
    WEAK_TLS,
    /** TLS (version undetermined but encrypted). */
    TLS,
    /** TLS 1.2 confirmed via ClientHello. */
    TLS_1_2,
    /** TLS 1.3 confirmed via supported_versions extension. */
    TLS_1_3,
    /** QUIC / HTTP3 (UDP port 443). */
    QUIC,
    ;

    companion object {
        private val SECURE = setOf(TLS.name, TLS_1_2.name, TLS_1_3.name, QUIC.name)

        /** Encrypted with a current protocol (stored status names, as in ConnectionEntity). */
        fun isSecure(status: String?): Boolean = status in SECURE

        /**
         * Share of connections with known encryption that use a current protocol:
         * secure / (secure + weak TLS + cleartext). UNKNOWN is left out — it is
         * often an app's own encrypted protocol (WhatsApp) that we cannot verify,
         * and counting it as either side would misstate the result.
         */
        fun secureShare(statuses: List<String>): Float {
            var secure = 0
            var classified = 0
            for (s in statuses) {
                when {
                    s in SECURE -> { secure++; classified++ }
                    s == CLEARTEXT.name || s == WEAK_TLS.name -> classified++
                }
            }
            return if (classified == 0) 0f else secure.toFloat() / classified
        }
    }
}
