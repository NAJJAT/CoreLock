/**
 * FilterRule.kt
 *
 * Defines the structure and behavior of firewall rules in PrivacyGuard
 *
 * What it does:
 * =============
 * Represents a single firewall rule that determines whether to allow or block
 * a specific connection based on:
 * - Application (package name)
 * - Domain name (with wildcard support)
 * - IP address (with CIDR support)
 *
 * Rule Priority:
 * ==============
 * Rules are evaluated in priority order (higher priority first).
 * Default priority: 0 (lowest)
 *
 * Rule Types:
 * ===========
 * 1. ALLOW - Explicitly allow matching connections
 * 2. BLOCK - Explicitly block matching connections
 *
 * Matching Rules:
 * ===============
 * - APP: Exact package name match (case-insensitive)
 * - DOMAIN: Exact or wildcard match (*.example.com)
 * - IP: Exact or CIDR match (192.168.1.0/24)
 *
 * Thread Safety:
 * ==============
 * FilterRule is immutable - all fields are 'val'.
 * This makes it safe to share across threads without synchronization.
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.filter

import java.util.UUID

// ============================================================
// Rule Type Enumeration
// ============================================================

/**
 * Type of filter rule - determines what the rule matches against
 *
 * @property APP Rule matches by application package name
 * @property DOMAIN Rule matches by domain name
 * @property IP Rule matches by IP address (supports CIDR)
 */
enum class RuleType {
    APP,
    DOMAIN,
    IP;

    /**
     * Returns a human-readable description of this rule type
     */
    fun displayName(): String = when (this) {
        APP -> "Application"
        DOMAIN -> "Domain"
        IP -> "IP Address"
    }
}

/**
 * Action to take when a rule matches
 *
 * @property ALLOW Allow the connection through the firewall
 * @property BLOCK Block the connection (prevent it from reaching the internet)
 */
enum class RuleAction {
    ALLOW,
    BLOCK;

    /**
     * Returns true if this action is ALLOW
     */
    fun isAllow(): Boolean = this == ALLOW

    /**
     * Returns true if this action is BLOCK
     */
    fun isBlock(): Boolean = this == BLOCK

    /**
     * Returns a color code for UI display
     */
    fun uiColor(): String = when (this) {
        ALLOW -> "#4CAF50"  // Green
        BLOCK -> "#F44336"  // Red
    }
}

/**
 * Rule priority levels for common use cases
 *
 * Priority determines evaluation order (higher = evaluated first)
 *
 * Example priorities:
 * - 100: Emergency/System rules (highest)
 * - 80:  User-created high priority rules
 * - 50:  Standard user rules (default)
 * - 30:  Auto-generated rules
 * - 0:   Default/Low priority
 */
object RulePriority {
    const val EMERGENCY = 100
    const val HIGH = 80
    const val NORMAL = 50
    const val AUTO = 30
    const val LOW = 0
}

// ============================================================
// FilterRule Data Class
// ============================================================

/**
 * Represents a single firewall rule
 *
 * This class is IMMUTABLE - once created, it cannot be modified.
 * To change a rule, create a new instance with updated values.
 *
 * @property id Unique identifier for the rule (UUID format)
 * @property type What kind of rule (APP, DOMAIN, IP)
 * @property value The value to match (package name, domain, or IP)
 * @property action What to do when matched (ALLOW or BLOCK)
 * @property enabled Whether the rule is active (can be toggled)
 * @property priority Higher priority rules are evaluated first
 * @property description Human-readable description (for UI)
 * @property createdAt Timestamp when rule was created (milliseconds)
 * @property lastModified Timestamp when rule was last modified (milliseconds)
 * @property hitCount Number of times this rule has matched (for analytics)
 * @property lastHit Timestamp of the last match (milliseconds)
 */
data class FilterRule(
    val id: String = UUID.randomUUID().toString(),
    val type: RuleType,
    val value: String,
    val action: RuleAction,
    val enabled: Boolean = true,
    val priority: Int = RulePriority.NORMAL,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val hitCount: Long = 0,
    val lastHit: Long = 0
) {

    /**
     * Returns a copy of this rule with updated hit statistics
     *
     * Used internally when a rule matches a connection
     */
    fun withHit(): FilterRule {
        return this.copy(
            hitCount = hitCount + 1,
            lastHit = System.currentTimeMillis()
        )
    }

    /**
     * Returns a copy of this rule with updated enabled state
     */
    fun withEnabled(enabled: Boolean): FilterRule {
        return this.copy(
            enabled = enabled,
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * Returns a copy of this rule with updated priority
     */
    fun withPriority(priority: Int): FilterRule {
        return this.copy(
            priority = priority,
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * Returns a copy of this rule with updated description
     */
    fun withDescription(description: String): FilterRule {
        return this.copy(
            description = description,
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * Returns true if this rule matches the given application package name
     *
     * @param packageName The package name to check
     * @return true if the rule type is APP and the package names match
     */
    fun matchesApp(packageName: String): Boolean {
        if (type != RuleType.APP) return false
        return value.equals(packageName, ignoreCase = true)
    }

    /**
     * Returns true if this rule matches the given domain name
     *
     * Supports wildcard patterns:
     * - "*.example.com" matches "sub.example.com" and "example.com"
     * - "example.com" matches exactly "example.com"
     *
     * @param domain The domain name to check
     * @return true if the rule type is DOMAIN and the domain matches
     */
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

    /**
     * Returns true if this rule matches the given IP address
     *
     * Supports:
     * - Exact IP: "192.168.1.1"
     * - CIDR notation: "192.168.1.0/24"
     *
     * @param ip The IP address to check (dotted decimal format)
     * @return true if the rule type is IP and the IP matches
     */
    fun matchesIp(ip: String): Boolean {
        if (type != RuleType.IP) return false

        return if (value.contains('/')) {
            matchesCidr(ip, value)
        } else {
            ip == value
        }
    }

    /**
     * Checks if an IP address matches a CIDR range
     *
     * Example: 192.168.1.100 matches 192.168.1.0/24
     *
     * @param ip The IP address to check
     * @param cidr The CIDR notation (e.g., "192.168.1.0/24")
     * @return true if the IP is within the CIDR range
     */
    private fun matchesCidr(ip: String, cidr: String): Boolean {
        try {
            val parts = cidr.split('/')
            if (parts.size != 2) return false

            val networkIp = ipToLong(parts[0])
            val maskBits = parts[1].toInt()

            // Validate mask bits (0-32)
            if (maskBits !in 0..32) return false

            val mask = if (maskBits == 0) 0 else -1L shl (32 - maskBits)
            val targetIp = ipToLong(ip)

            return (networkIp and mask) == (targetIp and mask)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Converts an IPv4 address string to a Long value
     *
     * Example: "192.168.1.1" -> 3232235521L
     *
     * @param ip IPv4 address in dotted decimal format
     * @return Long representation of the IP address
     * @throws NumberFormatException if the IP is invalid
     */
    private fun ipToLong(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) {
            throw NumberFormatException("Invalid IP address: $ip")
        }

        var result = 0L
        for (i in 0..3) {
            val part = parts[i].toLong()
            if (part !in 0..255) {
                throw NumberFormatException("Invalid IP octet: $part")
            }
            result = (result shl 8) or part
        }
        return result
    }

    /**
     * Returns a human-readable string representation of this rule
     */
    fun toDisplayString(): String {
        val status = if (enabled) "Enabled" else "Disabled"
        val typeName = type.displayName()
        val actionName = when (action) {
            RuleAction.ALLOW -> "Allow"
            RuleAction.BLOCK -> "Block"
        }

        return "[$status] $actionName $typeName: $value"
    }

    /**
     * Returns a short description for UI lists
     */
    fun toShortString(): String {
        return when (action) {
            RuleAction.ALLOW -> "✓ Allow $value"
            RuleAction.BLOCK -> "✗ Block $value"
        }
    }

    override fun toString(): String {
        return "FilterRule(id=$id, type=$type, value=$value, action=$action, enabled=$enabled, priority=$priority)"
    }
}

// ============================================================
// Rule Builder (Fluent API)
// ============================================================

/**
 * Builder for creating FilterRule instances with a fluent API
 *
 * Example usage:
 * ```
 * val rule = FilterRuleBuilder()
 *     .block()
 *     .domain("*.google.com")
 *     .withPriority(RulePriority.HIGH)
 *     .withDescription("Block all Google services")
 *     .build()
 * ```
 */
class FilterRuleBuilder {
    private var type: RuleType? = null
    private var value: String? = null
    private var action: RuleAction = RuleAction.BLOCK
    private var enabled: Boolean = true
    private var priority: Int = RulePriority.NORMAL
    private var description: String = ""

    /**
     * Set the rule to ALLOW action
     */
    fun allow(): FilterRuleBuilder {
        this.action = RuleAction.ALLOW
        return this
    }

    /**
     * Set the rule to BLOCK action
     */
    fun block(): FilterRuleBuilder {
        this.action = RuleAction.BLOCK
        return this
    }

    /**
     * Set the rule type to APP
     * @param packageName The application package name
     */
    fun app(packageName: String): FilterRuleBuilder {
        this.type = RuleType.APP
        this.value = packageName
        return this
    }

    /**
     * Set the rule type to DOMAIN
     * @param domain The domain name (supports wildcards like *.example.com)
     */
    fun domain(domain: String): FilterRuleBuilder {
        this.type = RuleType.DOMAIN
        this.value = domain
        return this
    }

    /**
     * Set the rule type to IP
     * @param ip The IP address (supports CIDR like 192.168.1.0/24)
     */
    fun ip(ip: String): FilterRuleBuilder {
        this.type = RuleType.IP
        this.value = ip
        return this
    }

    /**
     * Set whether the rule is enabled
     */
    fun setEnabled(enabled: Boolean): FilterRuleBuilder {
        this.enabled = enabled
        return this
    }

    /**
     * Set the rule priority
     */
    fun withPriority(priority: Int): FilterRuleBuilder {
        this.priority = priority
        return this
    }

    /**
     * Set the rule description
     */
    fun withDescription(description: String): FilterRuleBuilder {
        this.description = description
        return this
    }

    /**
     * Build the FilterRule instance
     * @throws IllegalStateException if type or value is not set
     */
    fun build(): FilterRule {
        requireNotNull(type) { "Rule type must be set (app, domain, or ip)" }
        requireNotNull(value) { "Rule value must be set" }

        return FilterRule(
            type = type!!,
            value = value!!,
            action = action,
            enabled = enabled,
            priority = priority,
            description = description
        )
    }
}

// ============================================================
// Rule Validation
// ============================================================

/**
 * Validates a filter rule for correctness
 *
 * Checks:
 * - Domain format (valid characters, no invalid patterns)
 * - IP format (valid IPv4 or CIDR)
 * - Package name format (basic validation)
 */
object RuleValidator {

    /**
     * Validates a rule and returns any validation errors
     *
     * @param rule The rule to validate
     * @return List of error messages (empty if valid)
     */
    fun validate(rule: FilterRule): List<String> {
        val errors = mutableListOf<String>()

        when (rule.type) {
            RuleType.APP -> validateAppValue(rule.value, errors)
            RuleType.DOMAIN -> validateDomainValue(rule.value, errors)
            RuleType.IP -> validateIpValue(rule.value, errors)
        }

        if (rule.priority !in 0..100) {
            errors.add("Priority must be between 0 and 100")
        }

        return errors
    }

    private fun validateAppValue(value: String, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors.add("Application package name cannot be empty")
        }

        // Basic package name validation (android package names have at least one dot)
        if (!value.contains('.')) {
            errors.add("Application package name should contain a dot (e.g., com.example.app)")
        }

        // Check for invalid characters
        val invalidChars = value.filter { !it.isLetterOrDigit() && it != '.' && it != '_' }
        if (invalidChars.isNotEmpty()) {
            errors.add("Invalid characters in package name: $invalidChars")
        }
    }

    private fun validateDomainValue(value: String, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors.add("Domain cannot be empty")
        }

        val normalized = value.lowercase()

        // Wildcard validation
        if (normalized.startsWith("*.")) {
            val suffix = normalized.substring(2)
            if (suffix.isBlank() || suffix.startsWith('.') || suffix.endsWith('.')) {
                errors.add("Invalid wildcard domain: $value")
            }
        } else {
            // Exact domain validation
            if (normalized.startsWith('.') || normalized.endsWith('.')) {
                errors.add("Domain cannot start or end with a dot: $value")
            }

            // Check for valid domain characters
            val invalidChars = normalized.filter {
                !it.isLetterOrDigit() && it != '.' && it != '-'
            }
            if (invalidChars.isNotEmpty()) {
                errors.add("Invalid characters in domain: $invalidChars")
            }
        }
    }

    private fun validateIpValue(value: String, errors: MutableList<String>) {
        if (value.isBlank()) {
            errors.add("IP address cannot be empty")
            return
        }

        if (value.contains('/')) {
            // CIDR validation
            val parts = value.split('/')
            if (parts.size != 2) {
                errors.add("Invalid CIDR format: $value (expected: ip/mask)")
                return
            }

            val ipPart = parts[0]
            val maskPart = parts[1]

            // Validate IP part
            if (!isValidIpv4(ipPart)) {
                errors.add("Invalid IP address in CIDR: $ipPart")
            }

            // Validate mask
            try {
                val mask = maskPart.toInt()
                if (mask !in 0..32) {
                    errors.add("CIDR mask must be between 0 and 32: $mask")
                }
            } catch (e: NumberFormatException) {
                errors.add("Invalid CIDR mask: $maskPart (must be a number)")
            }
        } else {
            // Exact IP validation
            if (!isValidIpv4(value)) {
                errors.add("Invalid IP address: $value")
            }
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false

        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
        }
    }
}

// ============================================================
// Rule Presets (Common Rules)
// ============================================================

/**
 * Common rule presets for quick creation
 */
object RulePresets {

    /**
     * Creates a rule to block all Google Analytics tracking
     */
    fun blockGoogleAnalytics(): FilterRule {
        return FilterRuleBuilder()
            .block()
            .domain("google-analytics.com")
            .withPriority(RulePriority.AUTO)
            .withDescription("Block Google Analytics tracking")
            .build()
    }

    /**
     * Creates a rule to block all Facebook tracking domains
     */
    fun blockFacebookTracking(): FilterRule {
        return FilterRuleBuilder()
            .block()
            .domain("*.facebook.com")
            .withPriority(RulePriority.AUTO)
            .withDescription("Block Facebook tracking domains")
            .build()
    }

    /**
     * Creates a rule to block all DoubleClick ads
     */
    fun blockDoubleClick(): FilterRule {
        return FilterRuleBuilder()
            .block()
            .domain("doubleclick.net")
            .withPriority(RulePriority.AUTO)
            .withDescription("Block DoubleClick ads")
            .build()
    }

    /**
     * Creates a rule to allow a specific app (bypass all blocking)
     */
    fun allowApp(packageName: String, description: String = "Allow $packageName"): FilterRule {
        return FilterRuleBuilder()
            .allow()
            .app(packageName)
            .withPriority(RulePriority.HIGH)
            .withDescription(description)
            .build()
    }

    /**
     * Creates a rule to block a specific app
     */
    fun blockApp(packageName: String, description: String = "Block $packageName"): FilterRule {
        return FilterRuleBuilder()
            .block()
            .app(packageName)
            .withPriority(RulePriority.NORMAL)
            .withDescription(description)
            .build()
    }

    /**
     * Creates a rule to block a specific domain
     */
    fun blockDomain(domain: String, description: String = "Block $domain"): FilterRule {
        return FilterRuleBuilder()
            .block()
            .domain(domain)
            .withPriority(RulePriority.NORMAL)
            .withDescription(description)
            .build()
    }

    /**
     * Creates a rule to block a specific IP address
     */
    fun blockIp(ip: String, description: String = "Block $ip"): FilterRule {
        return FilterRuleBuilder()
            .block()
            .ip(ip)
            .withPriority(RulePriority.NORMAL)
            .withDescription(description)
            .build()
    }

    /**
     * Creates a rule to block an entire IP range (CIDR)
     */
    fun blockCidr(cidr: String, description: String = "Block $cidr"): FilterRule {
        return FilterRuleBuilder()
            .block()
            .ip(cidr)
            .withPriority(RulePriority.NORMAL)
            .withDescription(description)
            .build()
    }

    /**
     * Returns a list of default blocking rules for common trackers
     */
    fun getDefaultBlockRules(): List<FilterRule> {
        return listOf(
            blockGoogleAnalytics(),
            blockDoubleClick(),
            FilterRuleBuilder().block().domain("googlesyndication.com").withDescription("Block Google Syndication").build(),
            FilterRuleBuilder().block().domain("scorecardresearch.com").withDescription("Block Scorecard Research").build(),
            FilterRuleBuilder().block().domain("outbrain.com").withDescription("Block Outbrain").build(),
            FilterRuleBuilder().block().domain("taboola.com").withDescription("Block Taboola").build(),
            FilterRuleBuilder().block().domain("amazon-adsystem.com").withDescription("Block Amazon Ads").build(),
        )
    }

    /**
     * Returns a list of default bypass rules (always allow these apps)
     */
    fun getDefaultBypassApps(): List<String> {
        return listOf(
            "com.android.chrome",           // Chrome browser
            "com.google.android.gms",       // Google Play Services
            "com.google.android.gsf",       // Google Services Framework
            "com.whatsapp",                 // WhatsApp
            "com.facebook.katana",          // Facebook (messaging)
            "org.telegram.messenger",       // Telegram
            "com.microsoft.skydrive",       // OneDrive
            "com.dropbox.android"           // Dropbox
        )
    }
}

// ============================================================
// Rule Comparison
// ============================================================

/**
 * Compares two rules to determine if they are effectively the same
 * (ignores metadata like hitCount, lastHit, lastModified)
 */
fun FilterRule.isEffectivelySame(other: FilterRule): Boolean {
    return this.type == other.type &&
            this.value == other.value &&
            this.action == other.action &&
            this.enabled == other.enabled &&
            this.priority == other.priority
}

/**
 * Sorts rules by priority (higher priority first)
 */
fun List<FilterRule>.sortByPriority(): List<FilterRule> {
    return this.sortedByDescending { it.priority }
}

/**
 * Filters only enabled rules
 */
fun List<FilterRule>.onlyEnabled(): List<FilterRule> {
    return this.filter { it.enabled }
}

/**
 * Groups rules by type
 */
fun List<FilterRule>.groupByType(): Map<RuleType, List<FilterRule>> {
    return this.groupBy { it.type }
}