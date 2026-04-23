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
}
