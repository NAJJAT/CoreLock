package com.privacyguard.app.core.blocklist

data class BlocklistEntry(
    val domain:      String,
    val source:      BlocklistSource     = BlocklistSource.CUSTOM,
    val category:    BlocklistCategory   = BlocklistCategory.OTHER,
    val lastUpdated: Long                = System.currentTimeMillis(),
    val isEnabled:   Boolean             = true,
)
