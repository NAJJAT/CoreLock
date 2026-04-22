/**
 * FilterRule.kt
 * 
 * Defines firewall rules for PrivacyGuard
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.filter

import java.util.UUID

// ============================================================
// Enums
// ============================================================

enum class RuleType {
    APP, DOMAIN, IP
}

enum class RuleAction {
    ALLOW, BLOCK
}

// ============================================================
// FilterRule Data Class
// ============================================================

data class FilterRule(
    val id: String = UUID.randomUUID().toString(),
    val type: RuleType,
    val value: String,
    val action: RuleAction,
    val enabled: Boolean = true,
    val priority: Int = 50,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val hitCount: Long = 0,
    val lastHit: Long = 0
) {
    fun matchesApp(packageName: String): Boolean {
        if (type != RuleType.APP) return false
        return value.equals(packageName, ignoreCase = true)
    }

    fun matchesDomain(domain: String): Boolean {
        if (type != RuleType.DOMAIN) return false
        val normalizedDomain = domain.lowercase().trimEnd('.')
        val normalizedValue = value.lowercase().trimEnd('.')
        return when {
            normalizedValue.startsWith("*.") -> {
                val suffix = normalizedValue.substring(2)
                normalizedDomain.endsWith(suffix) || normalizedDomain == suffix
            }
            else -> normalizedDomain == normalizedValue
        }
    }

    fun matchesIp(ip: String): Boolean {
        if (type != RuleType.IP) return false
        return if (value.contains('/')) matchesCidr(ip, value) else ip == value
    }

    private fun matchesCidr(ip: String, cidr: String): Boolean {
        try {
            val parts = cidr.split('/')
            if (parts.size != 2) return false
            val networkIp = ipToLong(parts[0])
            val maskBits = parts[1].toInt()
            if (maskBits !in 0..32) return false
            val mask = if (maskBits == 0) 0 else -1L shl (32 - maskBits)
            val targetIp = ipToLong(ip)
            return (networkIp and mask) == (targetIp and mask)
        } catch (e: Exception) { return false }
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split('.')
        var result = 0L
        for (i in 0..3) result = (result shl 8) or parts[i].toLong()
        return result
    }

    fun withHit(): FilterRule = copy(hitCount = hitCount + 1, lastHit = System.currentTimeMillis())
}