package com.privacyguard.core.metadata

/**
 * TLS protocol version as extracted from the ClientHello record layer.
 * No decryption is performed — only handshake metadata.
 */
enum class TlsVersion(val isSecure: Boolean) {
    UNKNOWN(false),
    TLS_1_0(false),
    TLS_1_1(false),
    TLS_1_2(true),
    TLS_1_3(true),
}
