package com.privacyguard.app.core.blocklist

enum class BlocklistSource {
    STEVENBLACK,
    EASYLIST,
    EASYPRIVACY,
    DISCONNECT,
    PETER_LOWE,
    CUSTOM,
    COMMUNITY;

    fun displayName(): String = when (this) {
        STEVENBLACK -> "StevenBlack Unified"
        EASYLIST -> "EasyList"
        EASYPRIVACY -> "EasyPrivacy"
        DISCONNECT -> "Disconnect.me"
        PETER_LOWE -> "Peter Lowe's List"
        CUSTOM -> "Custom Rules"
        COMMUNITY -> "Community Reported"
    }

    fun downloadUrl(): String? = when (this) {
        STEVENBLACK -> "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        EASYLIST -> "https://easylist.to/easylist/easylist.txt"
        EASYPRIVACY -> "https://easylist.to/easylist/easyprivacy.txt"
        DISCONNECT -> "https://s3.amazonaws.com/lists.disconnect.me/simple_tracking.txt"
        PETER_LOWE -> "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
        CUSTOM, COMMUNITY -> null
    }
}

enum class BlocklistCategory {
    ADVERTISING,
    ANALYTICS,
    TRACKING,
    MALWARE,
    PHISHING,
    SOCIAL,
    OTHER
}

data class BlocklistEntry(
    val domain: String,
    val source: BlocklistSource,
    val category: BlocklistCategory,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = true
) {
    fun normalizedDomain(): String {
        var result = domain.lowercase().trim()
        if (result.startsWith("www.")) {
            result = result.removePrefix("www.")
        }
        if (result.endsWith(".")) {
            result = result.dropLast(1)
        }
        return result
    }

    fun matches(candidate: String): Boolean {
        val normalizedCandidate = candidate.trim().lowercase()
        val normalizedDomain = normalizedDomain()

        if (normalizedCandidate == normalizedDomain) return true
        if (normalizedCandidate.endsWith(".$normalizedDomain")) return true

        if (normalizedDomain.startsWith("*.")) {
            val suffix = normalizedDomain.removePrefix("*.")
            return normalizedCandidate == suffix || normalizedCandidate.endsWith(".$suffix")
        }

        return false
    }
}
