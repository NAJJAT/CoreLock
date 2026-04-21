/**
 * DomainFilter.kt
 * 
 * Domain name filtering for PrivacyGuard
 * 
 * What it does:
 * =============
 * Checks domain names against blocklists and user rules.
 * This is the PRIMARY blocking mechanism for trackers and ads.
 * 
 * Why DNS blocking is effective:
 * ==============================
 * 1. Every connection starts with a DNS lookup
 * 2. Blocking at DNS layer prevents the connection entirely
 * 3. No bandwidth wasted on blocked requests
 * 4. Works for all protocols (HTTP, HTTPS, FTP, etc.)
 * 
 * Matching Methods:
 * =================
 * - Exact match: "google.com" matches exactly "google.com"
 * - Subdomain match: ".google.com" matches "mail.google.com", "www.google.com"
 * - Wildcard match: "*.google.com" matches "mail.google.com"
 * - Suffix match: "google.com" also matches "www.google.com" (configurable)
 * 
 * Performance Requirements:
 * =========================
 * - Lookup must be O(1) or O(log n)
 * - Called for every DNS query (100+ times per second)
 * - Blocklist must be loaded efficiently
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.firewall

import com.privacyguard.app.core.filter.BlocklistCategory
import com.privacyguard.app.core.filter.BlocklistEntry
import com.privacyguard.app.core.filter.BlocklistSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// ============================================================
// Domain Filter Result
// ============================================================

/**
 * Result of domain filter check
 */
data class DomainFilterResult(
    val isBlocked: Boolean,
    val reason: String,
    val matchedRule: String? = null,
    val matchedBlocklist: BlocklistEntry? = null
) {
    companion object {
        fun allowed(): DomainFilterResult = DomainFilterResult(false, "Domain not in blocklist")
        fun blockedByRule(rule: String): DomainFilterResult = DomainFilterResult(true, "Blocked by user rule: $rule", rule)
        fun blockedByBlocklist(entry: BlocklistEntry): DomainFilterResult = 
            DomainFilterResult(true, "Blocked by ${entry.source}: ${entry.domain}", null, entry)
        fun blockedByWildcard(wildcard: String): DomainFilterResult = 
            DomainFilterResult(true, "Blocked by wildcard rule: $wildcard", wildcard)
    }
}

// ============================================================
// DomainFilter - Main Implementation
// ============================================================

/**
 * Domain filter - checks domain names against blocklists
 */
class DomainFilter {
    
    companion object {
        private const val TAG = "DomainFilter"
        
        // Common TLDs for suffix matching
        private val COMMON_TLDS = setOf(
            "com", "org", "net", "edu", "gov", "io", "co", "uk", "de", "fr", "jp", "cn", "ru", "br", "in"
        )
    }
    
    // ============================================================
    // Blocklist Storage
    // ============================================================
    
    // Exact domain blocklist (O(1) lookup)
    private val exactBlocklist = ConcurrentHashMap<String, BlocklistEntry>()
    
    // Subdomain blocklist (e.g., ".google.com" blocks all subdomains)
    private val subdomainBlocklist = ConcurrentHashMap<String, BlocklistEntry>()
    
    // Wildcard rules from user
    private val userWildcardRules = CopyOnWriteArrayList<String>()
    
    // User exact rules (allow/block)
    private val userExactAllow = ConcurrentHashMap<String, String>()
    private val userExactBlock = ConcurrentHashMap<String, String>()
    
    // Statistics
    private val totalLookups = AtomicLong(0)
    private val exactHits = AtomicLong(0)
    private val subdomainHits = AtomicLong(0)
    private val wildcardHits = AtomicLong(0)
    private val allowHits = AtomicLong(0)
    
    private val blocklistLock = ReentrantReadWriteLock()
    
    // ============================================================
    // Blocklist Management
    // ============================================================
    
    /**
     * Updates the domain blocklist with new entries
     * 
     * @param entries List of blocklist entries
     */
    fun updateBlocklist(entries: List<BlocklistEntry>) {
        blocklistLock.write {
            exactBlocklist.clear()
            subdomainBlocklist.clear()
            
            for (entry in entries) {
                val domain = entry.normalizedDomain()
                
                // Check if this is a subdomain rule (starts with .)
                if (domain.startsWith('.')) {
                    subdomainBlocklist[domain] = entry
                } else {
                    exactBlocklist[domain] = entry
                }
            }
            
            android.util.Log.d(TAG, "Blocklist updated: ${exactBlocklist.size} exact, ${subdomainBlocklist.size} subdomain")
        }
    }
    
    /**
     * Adds a user block rule for a domain
     */
    fun addUserBlockRule(domain: String, description: String = "User blocked") {
        val normalized = domain.lowercase().trim()
        if (normalized.startsWith("*.")) {
            userWildcardRules.add(normalized.substring(2))
        } else if (normalized.startsWith('.')) {
            subdomainBlocklist[normalized] = BlocklistEntry(
                domain = normalized,
                source = BlocklistSource.CUSTOM,
                category = BlocklistCategory.OTHER
            )
        } else {
            userExactBlock[normalized] = description
        }
    }
    
    /**
     * Adds a user allow rule for a domain (bypasses blocklist)
     */
    fun addUserAllowRule(domain: String, description: String = "User allowed") {
        val normalized = domain.lowercase().trim()
        userExactAllow[normalized] = description
    }
    
    /**
     * Removes a user rule
     */
    fun removeUserRule(domain: String) {
        val normalized = domain.lowercase().trim()
        userExactAllow.remove(normalized)
        userExactBlock.remove(normalized)
        userWildcardRules.removeAll { it == normalized }
    }
    
    /**
     * Clears all user rules
     */
    fun clearUserRules() {
        userExactAllow.clear()
        userExactBlock.clear()
        userWildcardRules.clear()
    }
    
    // ============================================================
    // Domain Checking
    // ============================================================
    
    /**
     * Checks if a domain should be blocked
     * 
     * This is the MAIN entry point for DNS blocking decisions.
     * 
     * Order of checks (first match wins):
     * 1. User allow list (explicit allow)
     * 2. User block list (explicit block)
     * 3. Exact blocklist match
     * 4. Subdomain blocklist match (.example.com)
     * 5. User wildcard rules (*.example.com)
     * 6. Not blocked (allowed)
     * 
     * @param domain Domain name to check
     * @return DomainFilterResult with decision and reason
     */
    fun checkDomain(domain: String): DomainFilterResult {
        totalLookups.incrementAndGet()
        
        val normalized = domain.lowercase().trimEnd('.')
        
        // Level 1: User allow list (explicit allow, highest priority)
        if (userExactAllow.containsKey(normalized)) {
            allowHits.incrementAndGet()
            return DomainFilterResult.allowed()
        }
        
        // Level 2: User block list (explicit block)
        userExactBlock[normalized]?.let { description ->
            exactHits.incrementAndGet()
            return DomainFilterResult.blockedByRule(description)
        }
        
        // Level 3: Exact blocklist match
        exactBlocklist[normalized]?.let { entry ->
            exactHits.incrementAndGet()
            return DomainFilterResult.blockedByBlocklist(entry)
        }
        
        // Level 4: Subdomain blocklist match (.example.com)
        for ((subdomainRule, entry) in subdomainBlocklist) {
            if (normalized.endsWith(subdomainRule) || normalized == subdomainRule.substring(1)) {
                subdomainHits.incrementAndGet()
                return DomainFilterResult.blockedByBlocklist(entry)
            }
        }
        
        // Level 5: User wildcard rules (*.example.com)
        for (wildcard in userWildcardRules) {
            if (normalized == wildcard || normalized.endsWith(".$wildcard")) {
                wildcardHits.incrementAndGet()
                return DomainFilterResult.blockedByWildcard("*.$wildcard")
            }
        }
        
        // Level 6: Not blocked
        return DomainFilterResult.allowed()
    }
    
    /**
     * Checks if a domain is in the blocklist (without user rules)
     * Used for statistics and reporting
     */
    fun isInBlocklist(domain: String): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        
        if (exactBlocklist.containsKey(normalized)) return true
        
        for (subdomainRule in subdomainBlocklist.keys) {
            if (normalized.endsWith(subdomainRule) || normalized == subdomainRule.substring(1)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Gets the blocklist entry for a domain (if any)
     */
    fun getBlocklistEntry(domain: String): BlocklistEntry? {
        val normalized = domain.lowercase().trimEnd('.')
        
        exactBlocklist[normalized]?.let { return it }
        
        for ((subdomainRule, entry) in subdomainBlocklist) {
            if (normalized.endsWith(subdomainRule) || normalized == subdomainRule.substring(1)) {
                return entry
            }
        }
        
        return null
    }
    
    // ============================================================
    // Domain Extraction
    // ============================================================
    
    /**
     * Extracts the main domain from a full domain name
     * 
     * Example: "mail.google.com" -> "google.com"
     * 
     * @param fullDomain Full domain name
     * @return Main domain (e.g., google.com)
     */
    fun extractMainDomain(fullDomain: String): String {
        val parts = fullDomain.lowercase().split('.')
        if (parts.size <= 2) return fullDomain
        
        // Check for known TLDs with 2 parts (e.g., co.uk)
        for (i in parts.indices.reversed()) {
            val candidate = parts.subList(maxOf(0, i - 1), i + 1).joinToString(".")
            if (COMMON_TLDS.contains(candidate.substringAfterLast('.'))) {
                return candidate
            }
        }
        
        // Return last two parts
        return parts.takeLast(2).joinToString(".")
    }
    
    // ============================================================
    // Statistics
    // ============================================================
    
    /**
     * Returns filter statistics
     */
    fun getStats(): DomainFilterStats {
        return DomainFilterStats(
            exactBlocklistSize = exactBlocklist.size,
            subdomainBlocklistSize = subdomainBlocklist.size,
            userExactRules = userExactBlock.size + userExactAllow.size,
            userWildcardRules = userWildcardRules.size,
            totalLookups = totalLookups.get(),
            exactHits = exactHits.get(),
            subdomainHits = subdomainHits.get(),
            wildcardHits = wildcardHits.get(),
            allowHits = allowHits.get(),
            blockRate = if (totalLookups.get() > 0) {
                (exactHits.get() + subdomainHits.get() + wildcardHits.get()).toDouble() / totalLookups.get()
            } else 0.0
        )
    }
    
    /**
     * Resets statistics
     */
    fun resetStats() {
        totalLookups.set(0)
        exactHits.set(0)
        subdomainHits.set(0)
        wildcardHits.set(0)
        allowHits.set(0)
    }
}

/**
 * Domain filter statistics
 */
data class DomainFilterStats(
    val exactBlocklistSize: Int,
    val subdomainBlocklistSize: Int,
    val userExactRules: Int,
    val userWildcardRules: Int,
    val totalLookups: Long,
    val exactHits: Long,
    val subdomainHits: Long,
    val wildcardHits: Long,
    val allowHits: Long,
    val blockRate: Double
)