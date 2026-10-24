package com.privacyguard.core.session

/**
 * What stopped a connection. Stored by [name] in ConnectionEntity.blockSource; null
 * there means a rule, a port policy, or a row written before this was recorded.
 * New values need no migration: the column is plain text, and a version that does
 * not know a value reads it as null ([fromStored]).
 */
enum class BlockSource {
    /**
     * The DNS blocklist answered "not found". Blocked lookups are dns_queries rows,
     * which carry no source column, so this is never stored on a connection: a name
     * blocked at DNS never becomes one. It names the layer where blocks are counted.
     */
    DNS,
    /** The TLS server name (SNI) on TCP is blocked: the name was reached without our DNS. */
    SNI,
    /**
     * The server name in a QUIC Initial (UDP/443) is blocked. Kept apart from [SNI] so
     * TCP and QUIC blocks can be told apart; rows written before this value existed
     * say SNI with protocol UDP ([fromStored] reads those as QUIC).
     */
    QUIC,
    /**
     * An encrypted DNS (DoH) endpoint that would skip the blocklist: by server name, by a
     * listed address without one, or a built-in resolver address. Over TCP or QUIC.
     */
    DOH,
    /** DNS over TLS / QUIC (port 853), which would skip the blocklist. */
    DOT,
    ;

    companion object {
        /** The stored value back as a source; null for unknown or missing values. */
        fun fromStored(value: String?): BlockSource? = entries.firstOrNull { it.name == value }

        /** [fromStored] for a connection row: older QUIC blocks were stored as SNI. */
        fun fromStored(value: String?, protocol: String?): BlockSource? {
            val source = fromStored(value)
            return if (source == SNI && protocol == "UDP") QUIC else source
        }
    }
}
