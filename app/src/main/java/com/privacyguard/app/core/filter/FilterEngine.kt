/**
 * FilterEngine.kt
 * 
 * Decision engine for PrivacyGuard
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

enum class BlockingMode {
    MINIMAL, STANDARD, STRICT, CUSTOM
}

// ============================================================
// FilterStats
// ============================================================

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

// ============================================================
// FilterEngine
// ============================================================

class FilterEngine {

    private val rules = CopyOnWriteArrayList<FilterRule>()
    private val appRules = ConcurrentHashMap<String, FilterRule>()
    private val exactDomainRules = ConcurrentHashMap<String, FilterRule>()
    private val wildcardDomainRules = CopyOnWriteArrayList<FilterRule>()
    private val exactIpRules = ConcurrentHashMap<String, FilterRule>()
    private val cidrIpRules = CopyOnWriteArrayList<FilterRule>()
    private val bypassApps = ConcurrentHashMap.newKeySet<String>()
    private val emergencyRules = CopyOnWriteArrayList<FilterRule>()

    private val blocklistRef = AtomicReference<Set<String>>(emptySet())
    private val blocklistEntriesRef = AtomicReference<Map<String, BlocklistEntry>>(emptyMap())
    private var blocklistLastUpdate: Long = 0
    private var blocklistSize: Int = 0
    private val blocklistLock = ReentrantReadWriteLock()

    @Volatile var defaultAction: RuleAction = RuleAction.ALLOW
    @Volatile var blockingMode: BlockingMode = BlockingMode.STANDARD
    @Volatile var logBlocked: Boolean = true
    @Volatile var logAllowed: Boolean = false

    private val totalEvaluations = AtomicLong(0)
    private val totalBlocks = AtomicLong(0)
    private val totalAllows = AtomicLong(0)
    private val totalBypass = AtomicLong(0)

    // ============================================================
    // Rule Management
    // ============================================================

    fun addRule(rule: FilterRule): Boolean {
        if (findRule(rule.id) != null) return false
        rules.add(rule)
        when (rule.type) {
            RuleType.APP -> appRules[rule.value] = rule
            RuleType.DOMAIN -> {
                if (rule.value.startsWith("*.")) wildcardDomainRules.add(rule)
                else exactDomainRules[rule.value] = rule
            }
            RuleType.IP -> {
                if (rule.value.contains('/')) cidrIpRules.add(rule)
                else exactIpRules[rule.value] = rule
            }
        }
        return true
    }

    fun addEmergencyRule(rule: FilterRule): Boolean {
        emergencyRules.add(rule)
        return true
    }

    fun findRule(ruleId: String): FilterRule? = rules.find { it.id == ruleId }

    fun getAllRules(): List<FilterRule> = rules.toList()

    fun removeRule(ruleId: String): Boolean {
        val rule = findRule(ruleId) ?: return false
        rules.remove(rule)
        when (rule.type) {
            RuleType.APP -> appRules.remove(rule.value)
            RuleType.DOMAIN -> {
                if (rule.value.startsWith("*.")) wildcardDomainRules.remove(rule)
                else exactDomainRules.remove(rule.value)
            }
            RuleType.IP -> {
                if (rule.value.contains('/')) cidrIpRules.remove(rule)
                else exactIpRules.remove(rule.value)
            }
        }
        return true
    }

    fun addBypassApp(packageName: String) { bypassApps.add(packageName) }

    fun removeBypassApp(packageName: String) { bypassApps.remove(packageName) }

    fun isAppBypassed(packageName: String): Boolean = bypassApps.contains(packageName)

    fun getBypassApps(): Set<String> = bypassApps.toSet()

    // ============================================================
    // Blocklist Management
    // ============================================================

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

    fun isDomainBlocklisted(domain: String): Boolean {
        return blocklistRef.get().contains(domain.lowercase().trimEnd('.'))
    }

    fun getBlocklistEntry(domain: String): BlocklistEntry? {
        return blocklistEntriesRef.get()[domain.lowercase().trimEnd('.')]
    }

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
    // Decision Making
    // ============================================================

    fun evaluate(packageName: String?, domain: String?, ip: String?): FilterDecision {
        totalEvaluations.incrementAndGet()

        // Emergency rules
        for (rule in emergencyRules) {
            if (rule.enabled && evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalBypass.incrementAndGet()
                return FilterDecision.blockByRule(rule)
            }
        }

        // Bypass apps
        if (packageName != null && bypassApps.contains(packageName)) {
            totalBypass.incrementAndGet()
            totalAllows.incrementAndGet()
            return FilterDecision.allow("Bypassed app: $packageName")
        }

        // User ALLOW rules
        for (rule in rules.filter { it.enabled && it.action == RuleAction.ALLOW }.sortedByDescending { it.priority }) {
            if (evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalAllows.incrementAndGet()
                return FilterDecision.allow("Allowed by rule: ${rule.value}")
            }
        }

        // User BLOCK rules
        for (rule in rules.filter { it.enabled && it.action == RuleAction.BLOCK }.sortedByDescending { it.priority }) {
            if (evaluateRuleMatch(rule, packageName, domain, ip)) {
                totalBlocks.incrementAndGet()
                return FilterDecision.block("Blocked by rule: ${rule.value}")
            }
        }

        // IP block rules
        if (ip != null) {
            exactIpRules[ip]?.let {
                totalBlocks.incrementAndGet()
                return FilterDecision.blockByRule(it)
            }
            for (rule in cidrIpRules) {
                if (rule.enabled && rule.matchesIp(ip)) {
                    totalBlocks.incrementAndGet()
                    return FilterDecision.blockByRule(rule)
                }
            }
        }

        // Domain block rules
        if (domain != null) {
            exactDomainRules[domain]?.let {
                totalBlocks.incrementAndGet()
                return FilterDecision.blockByRule(it)
            }
            for (rule in wildcardDomainRules) {
                if (rule.enabled && rule.matchesDomain(domain)) {
                    totalBlocks.incrementAndGet()
                    return FilterDecision.blockByRule(rule)
                }
            }
        }

        // App block rules
        if (packageName != null) {
            appRules[packageName]?.let {
                totalBlocks.incrementAndGet()
                return FilterDecision.blockByRule(it)
            }
        }

        // Blocklist
        if (domain != null && blockingMode != BlockingMode.MINIMAL) {
            getBlocklistEntry(domain)?.let { entry ->
                val shouldBlock = when (blockingMode) {
                    BlockingMode.STRICT -> true
                    BlockingMode.STANDARD -> entry.category in setOf(BlocklistCategory.ADVERTISING, BlocklistCategory.ANALYTICS)
                    else -> false
                }
                if (shouldBlock) {
                    totalBlocks.incrementAndGet()
                    return FilterDecision.blockByBlocklist(entry)
                }
            }
        }

        // Default action
        return if (defaultAction == RuleAction.ALLOW) {
            totalAllows.incrementAndGet()
            FilterDecision.allow("Default allow")
        } else {
            totalBlocks.incrementAndGet()
            FilterDecision.block("Default block")
        }
    }

    private fun evaluateRuleMatch(rule: FilterRule, packageName: String?, domain: String?, ip: String?): Boolean {
        return when (rule.type) {
            RuleType.APP -> packageName != null && rule.matchesApp(packageName)
            RuleType.DOMAIN -> domain != null && rule.matchesDomain(domain)
            RuleType.IP -> ip != null && rule.matchesIp(ip)
        }
    }

    fun evaluateByDomain(domain: String): FilterDecision = evaluate(null, domain, null)

    fun evaluateByIp(ip: String): FilterDecision = evaluate(null, null, ip)

    fun evaluateByApp(packageName: String): FilterDecision = evaluate(packageName, null, null)

    // ============================================================
    // Statistics
    // ============================================================

    fun getStats(): FilterStats {
        val evaluations = totalEvaluations.get()
        val blocks = totalBlocks.get()
        return FilterStats(
            totalEvaluations = evaluations,
            totalBlocks = blocks,
            totalAllows = totalAllows.get(),
            totalBypass = totalBypass.get(),
            blockRate = if (evaluations > 0) blocks.toDouble() / evaluations else 0.0,
            activeRules = rules.count { it.enabled },
            bypassedApps = bypassApps.size,
            blocklistSize = blocklistSize
        )
    }

    fun resetStats() {
        totalEvaluations.set(0)
        totalBlocks.set(0)
        totalAllows.set(0)
        totalBypass.set(0)
    }

    fun clearRules() {
        rules.clear()
        appRules.clear()
        exactDomainRules.clear()
        wildcardDomainRules.clear()
        exactIpRules.clear()
        cidrIpRules.clear()
        bypassApps.clear()
    }

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
// BlocklistStats
// ============================================================

data class BlocklistStats(
    val size: Int,
    val lastUpdate: Long,
    val ageHours: Long,
    val sources: Map<BlocklistSource, Int>
)