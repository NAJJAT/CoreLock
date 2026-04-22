/**
 * BlocklistTypes.kt
 * 
 * Blocklist-related data classes for PrivacyGuard
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.filter

// ============================================================
// Blocklist Enums (NO RuleAction here)
// ============================================================

enum class BlocklistSource {
    STEVENBLACK, EASYLIST, EASYPRIVACY, DISCONNECT, PETER_LOWE, CUSTOM, COMMUNITY
}

enum class BlocklistCategory {
    ADVERTISING, ANALYTICS, MALWARE, PHISHING, CRYPTO_MINING, SOCIAL, OTHER
}

// ============================================================
// BlocklistEntry
// ============================================================

data class BlocklistEntry(
    val domain: String,
    val source: BlocklistSource,
    val category: BlocklistCategory,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun normalizedDomain(): String {
        var result = domain.lowercase()
        if (result.endsWith('.')) result = result.dropLast(1)
        return result
    }
}

// ============================================================
// FilterDecision (references RuleAction from FilterRule.kt)
// ============================================================

data class FilterDecision(
    val action: RuleAction,
    val reason: String,
    val matchedRule: FilterRule? = null,
    val matchedBlocklist: BlocklistEntry? = null
) {
    companion object {
        fun allow(reason: String = "Default allow"): FilterDecision {
            return FilterDecision(RuleAction.ALLOW, reason)
        }
        fun block(reason: String, rule: FilterRule? = null, blocklist: BlocklistEntry? = null): FilterDecision {
            return FilterDecision(RuleAction.BLOCK, reason, rule, blocklist)
        }
        fun blockByRule(rule: FilterRule): FilterDecision {
            return FilterDecision(RuleAction.BLOCK, "Blocked by rule: ${rule.description.ifEmpty { rule.value }}", rule)
        }
        fun blockByBlocklist(entry: BlocklistEntry): FilterDecision {
            return FilterDecision(RuleAction.BLOCK, "Blocked by ${entry.source}: ${entry.domain}", null, entry)
        }
    }
}