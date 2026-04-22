package com.privacyguard.app.core.blocklist

enum class BlocklistSource {
    BUILTIN,
    CUSTOM
}

enum class BlocklistCategory {
    ADVERTISING,
    ANALYTICS,
    TRACKING,
    MALWARE,
    SOCIAL
}

data class BlocklistEntry(
    val domain: String,
    val source: BlocklistSource,
    val category: BlocklistCategory
) {
    fun matches(candidate: String): Boolean {
        val normalizedCandidate = candidate.trim().lowercase()
        val normalizedDomain = domain.trim().lowercase()
        return normalizedCandidate == normalizedDomain ||
            normalizedCandidate.endsWith(".$normalizedDomain")
    }
}
