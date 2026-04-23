package com.privacyguard.core.filter

import com.privacyguard.core.metadata.EncryptionStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The core decision engine.
 *
 * Updated to receive [EncryptionStatus] on every evaluation so that
 * encryption-enforcement rules (e.g. [FilterRule.blockCleartext]) can fire
 * without any payload inspection — the port number and optional TLS ClientHello
 * byte inspection is sufficient to classify the session.
 *
 * Rule evaluation order: ascending [FilterRule.priority] → first match wins.
 * Default action when no rule matches: [defaultAction] (ALLOW = blacklist mode).
 */
class FilterEngine(
    private val defaultAction: FilterRule.Action = FilterRule.Action.ALLOW,
    @Volatile var blockLevel: BlockLevel = BlockLevel.STANDARD,
) {
    enum class BlockLevel { MINIMAL, STANDARD, STRICT }

    // ─────────────────────────────────────────────────────────────────────────
    // Rule Storage
    // ─────────────────────────────────────────────────────────────────────────

    private val rules = CopyOnWriteArrayList<FilterRule>()

    val allRules: List<FilterRule> get() = rules.toList()
    val ruleCount: Int             get() = rules.size

    fun addRule(rule: FilterRule) { rules.add(rule); sortRules() }

    fun setRules(newRules: List<FilterRule>) {
        rules.clear()
        rules.addAll(newRules.sortedBy { it.priority })
    }

    fun removeRule(id: String): Boolean = rules.removeIf { it.id == id }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val idx = rules.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return false
        rules[idx] = rules[idx].copy(isEnabled = enabled)
        return true
    }

    fun clearRules() = rules.clear()

    private fun sortRules() {
        val sorted = rules.sortedBy { it.priority }
        rules.clear()
        rules.addAll(sorted)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Decision
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Evaluates all rules against the given connection parameters.
     *
     * @param uid        Android UID (-1 if unknown)
     * @param pkg        package name (null if unknown)
     * @param domain     hostname from SNI or DNS (null if unavailable)
     * @param ip         destination IP address
     * @param port       destination port
     * @param protocol   IP protocol (6=TCP, 17=UDP)
     * @param encStatus  encryption classification — defaults to UNKNOWN so that
     *                   callers without classification info still work correctly.
     */
    fun evaluate(
        uid:       Int,
        pkg:       String?,
        domain:    String?,
        ip:        String,
        port:      Int,
        protocol:  Int,
        encStatus: EncryptionStatus = EncryptionStatus.UNKNOWN,
    ): Decision {
        totalEvaluations.incrementAndGet()

        for (rule in rules) {
            if (!rule.isEnabled)              continue
            if (!isActiveForLevel(rule))       continue
            if (!rule.matches(uid, pkg, domain, ip, port, protocol, encStatus)) continue

            return when (rule.action) {
                FilterRule.Action.DENY  -> {
                    blockedCount.incrementAndGet()
                    Decision(FilterRule.Action.DENY, rule)
                }
                FilterRule.Action.ALLOW -> Decision(FilterRule.Action.ALLOW, rule)
            }
        }

        return Decision(defaultAction, null)
    }

    /** Convenience overload accepting a [ConnectionContext]. */
    fun evaluate(ctx: ConnectionContext): Decision =
        evaluate(ctx.uid, ctx.pkg, ctx.domain, ctx.ip, ctx.port, ctx.protocol, ctx.encStatus)

    // ─────────────────────────────────────────────────────────────────────────
    // Quick checks (used by DNS intercept and connection guard)
    // ─────────────────────────────────────────────────────────────────────────

    fun isDomainBlocked(domain: String): Boolean {
        for (rule in rules) {
            if (!rule.isEnabled || !isActiveForLevel(rule)) continue
            if (rule.matchDomain == null) continue
            if (rule.matches(-1, null, domain, "0.0.0.0", 0, 0)) {
                return rule.action == FilterRule.Action.DENY
            }
        }
        return defaultAction == FilterRule.Action.DENY
    }

    fun isIpBlocked(ip: String): Boolean {
        for (rule in rules) {
            if (!rule.isEnabled || !isActiveForLevel(rule)) continue
            if (rule.matchIp == null) continue
            if (rule.matches(-1, null, null, ip, 0, 0)) {
                return rule.action == FilterRule.Action.DENY
            }
        }
        return defaultAction == FilterRule.Action.DENY
    }

    /**
     * Returns true if cleartext enforcement is active (any enabled DENY rule
     * with [matchEncryption] = CLEARTEXT).
     */
    fun isCleartextBlocked(): Boolean =
        rules.any { it.isEnabled && it.action == FilterRule.Action.DENY &&
                    it.matchEncryption == EncryptionStatus.CLEARTEXT }

    // ─────────────────────────────────────────────────────────────────────────
    // Block Level
    // ─────────────────────────────────────────────────────────────────────────

    private fun isActiveForLevel(rule: FilterRule): Boolean = when (rule.source) {
        FilterRule.Source.SYSTEM,
        FilterRule.Source.USER      -> true
        FilterRule.Source.BLOCKLIST -> blockLevel >= BlockLevel.STANDARD
        FilterRule.Source.COMMUNITY -> blockLevel >= BlockLevel.STRICT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    private val totalEvaluations = AtomicLong(0)
    private val blockedCount     = AtomicLong(0)

    data class Stats(
        val totalEvaluations: Long,
        val blockedCount:     Long,
        val allowedCount:     Long,
        val loadedRules:      Int,
    )

    fun stats() = Stats(
        totalEvaluations = totalEvaluations.get(),
        blockedCount     = blockedCount.get(),
        allowedCount     = totalEvaluations.get() - blockedCount.get(),
        loadedRules      = rules.size,
    )

    fun resetStats() { totalEvaluations.set(0); blockedCount.set(0) }

    // ─────────────────────────────────────────────────────────────────────────
    // Data Classes
    // ─────────────────────────────────────────────────────────────────────────

    data class Decision(
        val action:      FilterRule.Action,
        val matchedRule: FilterRule?,
    ) {
        val isBlocked: Boolean get() = action == FilterRule.Action.DENY
        val isAllowed: Boolean get() = action == FilterRule.Action.ALLOW
        val isDefault: Boolean get() = matchedRule == null
    }

    data class ConnectionContext(
        val uid:       Int,
        val pkg:       String?,
        val domain:    String?,
        val ip:        String,
        val port:      Int,
        val protocol:  Int,
        val encStatus: EncryptionStatus = EncryptionStatus.UNKNOWN,
    )
}