package com.privacyguard.app.core.blocklist

object BlocklistParser {

    fun parseStevenBlack(content: String, source: BlocklistSource): List<BlocklistEntry> {
        val now = System.currentTimeMillis()
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val domain = when {
                    line.startsWith("0.0.0.0") -> line.substringAfter("0.0.0.0").trim().split(Regex("\\s+")).firstOrNull()
                    line.startsWith("127.0.0.1") -> line.substringAfter("127.0.0.1").trim().split(Regex("\\s+")).firstOrNull()
                    line.startsWith("::1") -> line.substringAfter("::1").trim().split(Regex("\\s+")).firstOrNull()
                    else -> null
                }
                domain?.takeIf(::isValidDomain)?.let {
                    BlocklistEntry(
                        domain = it,
                        source = source,
                        category = detectCategory(it),
                        lastUpdated = now
                    )
                }
            }
            .toList()
    }

    fun parseEasyList(content: String, source: BlocklistSource): List<BlocklistEntry> {
        val now = System.currentTimeMillis()
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("[") }
            .mapNotNull { line ->
                var domain = line
                if (domain.startsWith("||")) domain = domain.removePrefix("||")
                if (domain.endsWith("^")) domain = domain.dropLast(1)
                if (domain.endsWith("/")) domain = domain.dropLast(1)
                domain = domain.substringBefore("^").substringBefore("/").trim()
                domain.takeIf {
                    it.isNotBlank() && !it.contains("*") && !it.contains("~") && isValidDomain(it)
                }?.let {
                    BlocklistEntry(
                        domain = it,
                        source = source,
                        category = detectCategory(it),
                        lastUpdated = now
                    )
                }
            }
            .toList()
    }

    fun parseDisconnect(content: String, source: BlocklistSource): List<BlocklistEntry> {
        val now = System.currentTimeMillis()
        val regex = Regex("\"domain\":\"([^\"]+)\"")
        return regex.findAll(content).mapNotNull { match ->
            val domain = match.groupValues[1]
            domain.takeIf(::isValidDomain)?.let {
                BlocklistEntry(
                    domain = it,
                    source = source,
                    category = BlocklistCategory.TRACKING,
                    lastUpdated = now
                )
            }
        }.toList()
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank() || domain.length > 255 || !domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        return domain.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '*' }
    }

    private fun detectCategory(domain: String): BlocklistCategory {
        val lower = domain.lowercase()
        return when {
            "ad" in lower || "banner" in lower || "doubleclick" in lower -> BlocklistCategory.ADVERTISING
            "analytics" in lower || "metric" in lower || "stat" in lower -> BlocklistCategory.ANALYTICS
            "malware" in lower || "virus" in lower -> BlocklistCategory.MALWARE
            "phish" in lower -> BlocklistCategory.PHISHING
            "facebook" in lower || "twitter" in lower || "instagram" in lower -> BlocklistCategory.SOCIAL
            else -> BlocklistCategory.TRACKING
        }
    }
}
