package com.privacyguard.core.metadata

/**
 * Codes for behavioral risk signals detected by [MetadataEngine].
 */
enum class RiskCode {
    KNOWN_TRACKER,
    HIGH_FREQUENCY,
    BACKGROUND_ONLY,
    CLEARTEXT,
    WEAK_TLS,
    DNS_TUNNELING,
    DGA_DOMAIN,
    EXCESSIVE_DNS,
    UNUSUAL_PORT,
    BEACON_PATTERN,
}
