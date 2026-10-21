package com.privacyguard.core.filter

import com.privacyguard.core.metadata.EncryptionStatus
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

    /**
     * The rules in priority order, never changed in place: every change builds a new
     * list and swaps it in, so a lookup sees the old rules or the new ones, never none.
     * (Clearing and refilling a shared list let packets through unfiltered for a moment
     * on every reload.) Writers are serialized by [lock].
     */
    @Volatile private var rules: List<FilterRule> = emptyList()
    private val lock = Any()

    val allRules: List<FilterRule> get() = rules
    val ruleCount: Int             get() = rules.size

    /** True when a name no rule matches is blocked (whitelist mode). */
    val blocksByDefault: Boolean get() = defaultAction == FilterRule.Action.DENY

    // sortedBy is stable: rules of equal priority keep their order.
    fun addRule(rule: FilterRule) = synchronized(lock) { rules = (rules + rule).sortedBy { it.priority } }

    fun setRules(newRules: List<FilterRule>) = synchronized(lock) { rules = newRules.sortedBy { it.priority } }

    /** Replaces the rule with [rule]'s id (or adds it) in one step. */
    fun upsertRule(rule: FilterRule) = synchronized(lock) {
        rules = (rules.filterNot { it.id == rule.id } + rule).sortedBy { it.priority }
    }

    fun removeRule(id: String): Boolean = synchronized(lock) {
        val kept = rules.filterNot { it.id == id }
        (kept.size != rules.size).also { rules = kept }
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        if (rules.none { it.id == id }) return false
        rules = rules.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        true
    }

    fun clearRules() = synchronized(lock) { rules = emptyList() }

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
        uid:          Int,
        pkg:          String?,
        domain:       String?,
        ip:           String,
        port:         Int,
        protocol:     Int,
        encStatus:    EncryptionStatus = EncryptionStatus.UNKNOWN,
        isBackground: Boolean = false,
    ): Decision {
        totalEvaluations.incrementAndGet()

        for (rule in rules) {
            if (!rule.isEnabled)              continue
            if (!isActiveForLevel(rule))       continue
            if (rule.matchBackground != null && rule.matchBackground != isBackground) continue
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

    /**
     * The first enabled rule (in priority order) that names this domain, for this
     * app or for every app. DNS uses it to let the user's Allow beat the lists.
     */
    fun domainRuleFor(domain: String, pkg: String?): FilterRule? {
        for (rule in rules) {
            if (!rule.isEnabled || !isActiveForLevel(rule)) continue
            if (rule.matchDomain == null) continue
            if (rule.matches(-1, pkg, domain, "0.0.0.0", 0, 0)) return rule
        }
        return null
    }

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
     * True when the first rule naming [ip] that applies to every app is a user Allow: it
     * opens a DoH address that DnsBypassGuard would stop. App-specific rules do not count
     * here (the guard runs before the owning app is known).
     */
    fun userAllowsAddress(ip: String, port: Int, protocol: Int): Boolean {
        for (rule in rules) {
            if (!rule.isEnabled || !isActiveForLevel(rule)) continue
            if (rule.matchIp == null || rule.matchUid != null || rule.matchPackage != null || rule.matchBackground != null) continue
            if (rule.matches(-1, null, null, ip, port, protocol)) {
                return rule.action == FilterRule.Action.ALLOW && rule.source == FilterRule.Source.USER
            }
        }
        return false
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