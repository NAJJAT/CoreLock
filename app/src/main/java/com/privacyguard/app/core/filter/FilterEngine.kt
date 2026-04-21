/**
 * FilterEngine.kt
 *
 * 🔴 CRITICAL: This is the DECISION ENGINE of PrivacyGuard
 *
 * What it does:
 * =============
 * Every packet that passes through PrivacyGuard is evaluated by this engine.
 * It decides: ALLOW or BLOCK based on:
 * - App rules (block specific apps entirely)
 * - Domain rules (block specific domains/websites)
 * - IP rules (block specific IP addresses)
 * - Blocklist rules (known tracker/malware domains)
 * - User custom rules
 *
 * Decision Hierarchy (first match wins):
 * ========================================
 * 1. Bypass list (always allow - for banking apps)
 * 2. Emergency rules (highest priority, system-level)
 * 3. User ALLOW rules (explicit allow, high priority)
 * 4. User BLOCK rules (explicit block)
 * 5. IP block list (specific IP addresses)
 * 6. Domain block list (specific domains with wildcards)
 * 7. App block list (entire applications)
 * 8. Blocklist sources (StevenBlack, EasyList, etc.)
 * 9. Default action (allow)
 *
 * Performance Requirements:
 * =========================
 * - Decision must complete in < 10 microseconds
 * - Called for every packet (1000+ times per second)
 * - Blocklist lookups must be O(1) (HashSet)
 *
 * Thread Safety:
 * ==============
 * - Rules are stored in CopyOnWriteArrayList (thread-safe for iteration)
 * - Blocklists use AtomicReference for atomic updates
 * - Reads are lock-free (volatile + immutable snapshots)
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.filter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// ============================================================
// Blocking Mode
// ============================================================

/**
 * Blocking mode levels - controls how aggressive the filtering is
 *
 * @property MINIMAL Block only known malware/phishing domains (safe for all apps)
 * @property STANDARD Block ads + trackers (default, may break some sites)
 * @property STRICT Block everything in blocklist (may break many sites)
 * @property CUSTOM User-defined custom rules override everything
 */
enum class BlockingMode {
    MINIMAL,
    STANDARD,
    STRICT,
    CUSTOM
}

// ============================================================
// Filter Statistics
// ============================================================

/**
 * Statistics for the filter engine
 *
 * @property totalEvaluations Total number of evaluation requests
 * @property totalBlocks Total number of block decisions
 * @property totalAllows Total number of allow decisions
 * @property totalBypass Total number of bypass decisions
 * @property blockRate Percentage of packets blocked
 * @property activeRules Number of active user-defined rules
 * @property bypassedApps Number of apps in bypass list
 * @property blocklistSize Number of domains in blocklist
 */
data class FilterStats(
    val totalEvaluations: Long,
    val totalBlocks: Long,
    val totalAllows: Long,
    val totalBypass: Long,
    val blockRate: Double,
    val activeRules: Int,
    val bypassedApps: Int,
    val blocklistSize: Int
)

/**
 * Blocklist statistics
 *
 * @property size Number of domains in blocklist
 * @property lastUpdate Timestamp of last update
 * @property ageHours Age of blocklist in hours
 * @property sources Breakdown by source
 */
data class BlocklistStats(
    val size: Int,
    val lastUpdate: Long,
    val ageHours: Long,
    val sources: Map<BlocklistSource, Int>
)

// ============================================================
// FilterEngine - Main Decision Engine
// ============================================================

/**
 * FilterEngine - The decision engine for PrivacyGuard
 *
 * This class evaluates every packet and decides whether to allow or block it.
 *
 * Thread Safety:
 * ==============
 * - Rules are stored in CopyOnWriteArrayList (thread-safe for iteration)
 * - Blocklists use AtomicReference for atomic updates
 * - Statistics use AtomicLong for thread-safe increments
 * - Reads are lock-free where possible
 *
 * Performance:
 * ============
 * - Domain lookups: O(1) using HashSet
 * - IP lookups: O(1) using HashSet (exact match only, CIDR is slower)
 * - App lookups: O(1) using ConcurrentHashMap
 */
class FilterEngine {

    // ============================================================
    // Rule Storage
    // ============================================================

    // User-defined rules (apps, domains, IPs)
    private val rules = CopyOnWriteArrayList<FilterRule>()

    // Quick lookup maps for O(1) performance
    private val appRules = ConcurrentHashMap<String, FilterRule>()
    private val exactDomainRules = ConcurrentHashMap<String, FilterRule>()
    private val wildcardDomainRules = CopyOnWriteArrayList<FilterRule>()
    private val exactIpRules = ConcurrentHashMap<String, FilterRule>()
    private val cidrIpRules = CopyOnWriteArrayList<FilterRule>()

    // Bypass apps (always allowed, even if other rules match)
    private val bypassApps = ConcurrentHashMap.newKeySet<String>()

    // Emergency rules (highest priority, cannot be overridden by user)
    private val emergencyRules = CopyOnWriteArrayList<FilterRule>()

    // ============================================================
    // Blocklist Storage
    // ============================================================

    // AtomicReference for thread-safe blocklist updates
    private val blocklistRef = AtomicReference<Set<String>>(emptySet())
    private val blocklistEntriesRef = AtomicReference<Map<String, BlocklistEntry>>(emptyMap())

    // Blocklist statistics
    private var blocklistLastUpdate: Long = 0
    private var blocklistSize: Int = 0
    private val blocklistLock = ReentrantReadWriteLock()

    // ============================================================
    // Settings
    // ============================================================

    // Default action when no rules match
    @Volatile
    var defaultAction: RuleAction = RuleAction.ALLOW

    // Blocking mode (minimal, standard, strict)
    @Volatile
    var blockingMode: BlockingMode = BlockingMode.STANDARD

    // Whether to log blocked connections
    @Volatile
    var logBlocked: Boolean = true

    // Whether to log allowed connections (for debugging)
    @Volatile
    var logAllowed: Boolean = false

    // ============================================================
    // Statistics
    // ============================================================

    private val totalEvaluations = AtomicLong(0)
    private val totalBlocks = AtomicLong(0)
    private val totalAllows = AtomicLong(0)
    private val totalBypass = AtomicLong(0)

    // ============================================================
    // Rule Management
    // ============================================================

    /**
     * Adds a new filter rule
     *
     * @param rule The rule to add
     * @return true if added, false if duplicate ID exists
     */
    fun addRule(rule: FilterRule): Boolean {
        // Check for duplicate ID
        if (findRule(rule.id) != null) {
            return false
        }

        // Validate rule before adding
        val errors = RuleValidator.validate(rule)
        if (errors.isNotEmpty()) {
            android.util.Log.w("FilterEngine", "Invalid rule: ${errors.joinToString()}")
            return false
        }

        rules.add(rule)

        // Add to quick lookup maps for performance
        when (rule.type) {
            RuleType.APP -> {
                appRules[rule.value] = rule
            }
            RuleType.DOMAIN -> {
                if (rule.value.startsWith("*.")) {
                    wildcardDomainRules.add(rule)
                } else {
                    exactDomainRules[rule.value] = rule
                }
            }
            RuleType.IP -> {
                if (rule.value.contains('/')) {
                    cidrIpRules.add(rule)
                } else {
                    exactIpRules[rule.value] = rule
                }
            }
        }

        return true
    }

    /**
     * Adds an emergency rule (highest priority, cannot be overridden)
     * These are system-level rules for critical functionality
     */
    fun addEmergencyRule(rule: FilterRule): Boolean {
        emergencyRules.add(rule)
        return true
    }

    /**
     * Removes a filter rule by ID
     */
    fun removeRule(ruleId: String): Boolean {
        val rule = findRule(ruleId) ?: return false

        rules.remove(rule)

        // Remove from quick lookup maps
        when (rule.type) {
            RuleType.APP -> appRules.remove(rule.value, rule)
            RuleType.DOMAIN -> {
                if (rule.value.startsWith("*.")) {
                    wildcardDomainRules.remove(rule)
                } else {
                    exactDomainRules.remove(rule.value, rule)
                }
            }
            RuleType.IP -> {
                if (rule.value.contains('/')) {
                    cidrIpRules.remove(rule)
                } else {
                    exactIpRules.remove(rule.value, rule)
                }
            }
        }

        return true
    }

    /**
     * Updates an existing rule
     */
    fun updateRule(rule: FilterRule): Boolean {
        removeRule(rule.id)
        return addRule(rule)
    }

    /**
     * Finds a rule by ID
     */
    fun findRule(ruleId: String): FilterRule? {
        return rules.find { it.id == ruleId }
    }

    /**
     * Returns all rules
     */
    fun getAllRules(): List<FilterRule> {
        return rules.toList()
    }

    /**
     * Returns all enabled rules, sorted by priority
     */
    fun getEnabledRules(): List<FilterRule> {
        return rules.filter { it.enabled }.sortedByDescending { it.priority }
    }

    /**
     * Clears all user rules (does not clear emergency rules)
     */
    fun clearRules() {
        rules.clear()
        appRules.clear()
        exactDomainRules.clear()
        wildcardDomainRules.clear()
        exactIpRules.clear()
        cidrIpRules.clear()
        bypassApps.clear()
    }

    // ============================================================
    // Bypass Management
    // ============================================================

    /**
     * Adds an app to the bypass list (always allowed)
     * Used for banking apps, critical services
     */
    fun addBypassApp(packageName: String) {
        bypassApps.add(packageName)
    }

    /**
     * Removes an app from the bypass list
     */
    fun removeBypassApp(packageName: String) {
        bypassApps.remove(packageName)
    }

    /**
     * Returns true if the app is bypassed
     */
    fun isAppBypassed(packageName: String): Boolean {
        return bypassApps.contains(packageName)
    }

    /**
     * Returns all bypassed apps
     */
    fun getBypassApps(): Set<String> {
        return bypassApps.toSet()
    }

    // ============================================================
    // Blocklist Management
    // ============================================================

    /**
     * Updates the blocklist with new entries
     *
     * This is called weekly from WorkManager in the background
     *
     * @param entries List of blocklist entries from various sources
     */
    fun updateBlocklist(entries: List<BlocklistEntry>) {
        blocklistLock.write {
            val domains = mutableSetOf<String>()
            val entriesMap = mutableMapOf<String, BlocklistEntry>()

            for (entry in entries) {
                val normalized = entry.normalizedDomain()
                domains.add(normalized)
                entriesMap[normalized] = entry
            }

            blocklistRef.set(domains)
            blocklistEntriesRef.set(entriesMap)
            blocklistSize = domains.size
            blocklistLastUpdate = System.currentTimeMillis()
        }
    }

    /**
     * Returns true if a domain is in the blocklist
     */
    fun isDomainBlocklisted(domain: String): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        return blocklistRef.get().contains(normalized)
    }

    /**
     * Returns the blocklist entry for a domain, or null if not blocked
     */
    fun getBlocklistEntry(domain: String): BlocklistEntry? {
        val normalized = domain.lowercase().trimEnd('.')
        return blocklistEntriesRef.get()[normalized]
    }

    /**
     * Returns blocklist statistics
     */
    fun getBlocklistStats(): BlocklistStats {
        blocklistLock.read {
            val sources = blocklistEntriesRef.get().values
                .groupBy { it.source }
                .mapValues { it.value.size }

            return BlocklistStats(
                size = blocklistSize,
                lastUpdate = blocklistLastUpdate,
                ageHours = if (blocklistLastUpdate > 0) {
                    (System.currentTimeMillis() - blocklistLastUpdate) / (1000 * 60 * 60)
                } else 0,
                sources = sources
            )
        }
    }

    // ============================================================
    // Decision Making - Main Entry Point
    // ============================================================

    /**
     * Evaluates whether a connection should be allowed or blocked
     *
     * This is the MAIN ENTRY POINT for all filtering decisions.
     * Called for every packet (1000+ times per second).
     *
     * Decision order (first match wins):
     * 1. Emergency rules (system-level, highest priority)
     * 2. Bypass apps (always allow)
     * 3. User ALLOW rules (explicit allow)
     * 4. User BLOCK rules (explicit block)
     * 5. IP block rules
     * 6. Domain block rules
     * 7. App block rules
     * 8. Blocklist (external sources)
     * 9. Default action
     *
     * @param packageName The app package name (or null if unknown)
     * @param domain The destination domain (or null if not available)
     * @param ip The destination IP address (or null if not available)
     * @return FilterDecision with action and reason
     */
    fun evaluate(
        packageName: String?,
        domain: String?,
        ip: String?
    ): FilterDecision {
        totalEvaluations.incrementAndGet()

        // Level 0: Emergency rules (highest priority, system-level)
        for (rule in emergencyRules) {
            if (rule.enabled && evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalBypass.incrementAndGet()
                return FilterDecision.blockByRule(rule)
            }
        }

        // Level 1: Bypass apps (always allow, for banking/critical apps)
        if (packageName != null && bypassApps.contains(packageName)) {
            totalBypass.incrementAndGet()
            totalAllows.incrementAndGet()
            return FilterDecision.allow("Bypassed app: $packageName")
        }

        // Level 2: User ALLOW rules (explicit allow, high priority)
        val userAllowRules = rules.filter { it.enabled && it.action == RuleAction.ALLOW }
        for (rule in userAllowRules.sortedByDescending { it.priority }) {
            if (evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalAllows.incrementAndGet()
                recordRuleHit(rule)
                return FilterDecision.allow("Allowed by rule: ${rule.description.ifEmpty { rule.value }}", rule)
            }
        }

        // Level 3: User BLOCK rules (explicit block)
        val userBlockRules = rules.filter { it.enabled && it.action == RuleAction.BLOCK }
        for (rule in userBlockRules.sortedByDescending { it.priority }) {
            if (evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalBlocks.incrementAndGet()
                recordRuleHit(rule)
                return FilterDecision.block("Blocked by rule: ${rule.description.ifEmpty { rule.value }}", rule)
            }
        }

        // Level 4: IP block rules (from optimized lookup)
        if (ip != null) {
            // Exact IP match
            val exactIpRule = exactIpRules[ip]
            if (exactIpRule != null && exactIpRule.enabled) {
                totalBlocks.incrementAndGet()
                recordRuleHit(exactIpRule)
                return FilterDecision.blockByRule(exactIpRule)
            }

            // CIDR match (slower, check only if needed)
            for (rule in cidrIpRules) {
                if (rule.enabled && rule.matchesIp(ip)) {
                    totalBlocks.incrementAndGet()
                    recordRuleHit(rule)
                    return FilterDecision.blockByRule(rule)
                }
            }
        }

        // Level 5: Domain block rules
        if (domain != null) {
            // Exact domain match
            val exactDomainRule = exactDomainRules[domain]
            if (exactDomainRule != null && exactDomainRule.enabled) {
                totalBlocks.incrementAndGet()
                recordRuleHit(exactDomainRule)
                return FilterDecision.blockByRule(exactDomainRule)
            }

            // Wildcard domain match (*.google.com)
            for (rule in wildcardDomainRules) {
                if (rule.enabled && rule.matchesDomain(domain)) {
                    totalBlocks.incrementAndGet()
                    recordRuleHit(rule)
                    return FilterDecision.blockByRule(rule)
                }
            }
        }

        // Level 6: App block rules
        if (packageName != null) {
            val appRule = appRules[packageName]
            if (appRule != null && appRule.enabled) {
                totalBlocks.incrementAndGet()
                recordRuleHit(appRule)
                return FilterDecision.blockByRule(appRule)
            }
        }

        // Level 7: Blocklist (external sources based on blocking mode)
        if (domain != null && blockingMode != BlockingMode.MINIMAL) {
            val blocklistEntry = getBlocklistEntry(domain)
            if (blocklistEntry != null) {
                // In standard mode, block only trackers and ads
                // In strict mode, block everything in blocklist
                val shouldBlock = when (blockingMode) {
                    BlockingMode.STRICT -> true
                    BlockingMode.STANDARD -> blocklistEntry.category in setOf(
                        BlocklistCategory.ADVERTISING,
                        BlocklistCategory.ANALYTICS
                    )
                    else -> false
                }

                if (shouldBlock) {
                    totalBlocks.incrementAndGet()
                    return FilterDecision.blockByBlocklist(blocklistEntry)
                }
            }
        }

        // Level 8: Default action
        if (defaultAction == RuleAction.ALLOW) {
            totalAllows.incrementAndGet()
            return FilterDecision.allow("Default allow")
        } else {
            totalBlocks.incrementAndGet()
            return FilterDecision.block("Default block")
        }
    }

    /**
     * Evaluates if a rule matches the given connection parameters
     */
    private fun evaluateRuleMatch(
        rule: FilterRule,
        packageName: String?,
        domain: String?,
        ip: String?
    ): Boolean {
        return when (rule.type) {
            RuleType.APP -> packageName != null && rule.matchesApp(packageName)
            RuleType.DOMAIN -> domain != null && rule.matchesDomain(domain)
            RuleType.IP -> ip != null && rule.matchesIp(ip)
        }
    }

    /**
     * Records a hit on a rule (updates statistics)
     */
    private fun recordRuleHit(rule: FilterRule) {
        // Update the rule in the list (creates new instance)
        val updatedRule = rule.withHit()
        val index = rules.indexOf(rule)
        if (index >= 0) {
            rules[index] = updatedRule

            // Also update quick lookup maps
            when (rule.type) {
                RuleType.APP -> appRules[rule.value] = updatedRule
                RuleType.DOMAIN -> {
                    if (rule.value.startsWith("*.")) {
                        val idx = wildcardDomainRules.indexOf(rule)
                        if (idx >= 0) wildcardDomainRules[idx] = updatedRule
                    } else {
                        exactDomainRules[rule.value] = updatedRule
                    }
                }
                RuleType.IP -> {
                    if (rule.value.contains('/')) {
                        val idx = cidrIpRules.indexOf(rule)
                        if (idx >= 0) cidrIpRules[idx] = updatedRule
                    } else {
                        exactIpRules[rule.value] = updatedRule
                    }
                }
            }
        }
    }

    // ============================================================
    // Convenience Methods
    // ============================================================

    /**
     * Evaluates a connection by domain only
     */
    fun evaluateByDomain(domain: String): FilterDecision {
        return evaluate(null, domain, null)
    }

    /**
     * Evaluates a connection by IP only
     */
    fun evaluateByIp(ip: String): FilterDecision {
        return evaluate(null, null, ip)
    }

    /**
     * Evaluates a connection by app only
     */
    fun evaluateByApp(packageName: String): FilterDecision {
        return evaluate(packageName, null, null)
    }

    // ============================================================
    // Statistics
    // ============================================================

    /**
     * Returns filter statistics
     */
    fun getStats(): FilterStats {
        val evaluations = totalEvaluations.get()
        val blocks = totalBlocks.get()
        val allows = totalAllows.get()
        val bypass = totalBypass.get()

        return FilterStats(
            totalEvaluations = evaluations,
            totalBlocks = blocks,
            totalAllows = allows,
            totalBypass = bypass,
            blockRate = if (evaluations > 0) blocks.toDouble() / evaluations else 0.0,
            activeRules = rules.count { it.enabled },
            bypassedApps = bypassApps.size,
            blocklistSize = blocklistSize
        )
    }

    /**
     * Resets all statistics
     */
    fun resetStats() {
        totalEvaluations.set(0)
        totalBlocks.set(0)
        totalAllows.set(0)
        totalBypass.set(0)
    }

    // ============================================================
    // Import/Export
    // ============================================================

    /**
     * Exports all rules to JSON format
     */
    fun exportRules(): String {
        val rulesJson = rules.joinToString(",\n  ") { rule ->
            """
            {
              "id": "${rule.id}",
              "type": "${rule.type.name}",
              "value": "${rule.value}",
              "action": "${rule.action.name}",
              "enabled": ${rule.enabled},
              "priority": ${rule.priority},
              "description": "${rule.description.replace("\"", "\\\"")}"
            }
            """.trimIndent()
        }

        return """
        {
          "version": 1,
          "rules": [
            $rulesJson
          ]
        }
        """.trimIndent()
    }
}

// ============================================================
// BlocklistEntry Extension
// ============================================================

/**
 * Normalizes a domain for blocklist storage
 */
fun BlocklistEntry.normalizedDomain(): String {
    var result = domain.lowercase()
    if (result.endsWith('.')) {
        result = result.dropLast(1)
    }
    return result
}