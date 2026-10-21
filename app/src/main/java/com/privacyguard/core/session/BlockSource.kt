package com.privacyguard.core.session

/**
 * Where a connection was stopped, when it was not by DNS. Stored by [name] in
 * ConnectionEntity.blockSource; null there means a rule, a port policy, or a row
 * written before this was recorded.
 */
enum class BlockSource {
    /** The TLS or QUIC server name (SNI) is blocked: the name was reached without our DNS. */
    SNI,
    /** The server name is an encrypted DNS (DoH) endpoint that would skip the blocklist. */
    DOH,
    ;

    companion object {
        /** The stored value back as a source; null for unknown or missing values. */
        fun fromStored(value: String?): BlockSource? = entries.firstOrNull { it.name == value }
    }
}
