package com.privacyguard.app.ui.rules

import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus

/**
 * Which rules to suggest from the last 7 days of traffic. Suggestions are narrow:
 * block an app's unencrypted connections, or block it while in the background,
 * never the whole app (that would cut off a messenger for one plain-HTTP image).
 */
object RuleSuggestions {

    enum class Kind { CLEARTEXT, BACKGROUND }

    data class Candidate(val kind: Kind, val packageName: String, val count: Int) {
        val id: String get() = "suggest:${kind.name.lowercase()}:$packageName"
    }

    /** Unencrypted internet connections in 7 days before suggesting to block them. */
    const val MIN_CLEARTEXT = 3
    /** Background connections in 7 days (about 15 a day) before suggesting a background block. */
    const val MIN_BACKGROUND = 100
    const val MAX_SUGGESTIONS = 5

    fun build(
        cleartextByPackage: Map<String, Int>,
        backgroundByPackage: Map<String, Int>,
        rules: List<FilterRule>,
        dismissed: Set<String>,
    ): List<Candidate> {
        val active = rules.filter { it.isEnabled && it.action == FilterRule.Action.DENY }
        // Only a rule that blocks cleartext for every app covers everyone; a weak-TLS
        // rule or one app's rule does not.
        val cleartextForAll = active.any {
            it.matchEncryption == EncryptionStatus.CLEARTEXT && it.matchPackage.isNullOrBlank() &&
                it.matchDomain == null && it.matchIp == null && it.matchBackground == null
        }
        fun wholeApp(pkg: String) = active.any {
            it.matchPackage == pkg && it.matchEncryption == null && it.matchBackground == null &&
                it.matchDomain == null && it.matchIp == null && it.matchPort == null
        }
        fun cleartextFor(pkg: String) = cleartextForAll || active.any {
            it.matchPackage == pkg && it.matchEncryption == EncryptionStatus.CLEARTEXT && it.matchBackground == null
        }
        fun backgroundFor(pkg: String) = active.any {
            it.matchPackage == pkg && it.matchBackground == true && it.matchEncryption == null && it.matchDomain == null
        }

        val cleartext = cleartextByPackage
            .filter { (pkg, n) -> pkg.isNotBlank() && n >= MIN_CLEARTEXT && !wholeApp(pkg) && !cleartextFor(pkg) }
            .entries.sortedByDescending { it.value }.take(3)
            .map { Candidate(Kind.CLEARTEXT, it.key, it.value) }
        val background = backgroundByPackage
            .filter { (pkg, n) -> pkg.isNotBlank() && n >= MIN_BACKGROUND && !wholeApp(pkg) && !backgroundFor(pkg) }
            .entries.sortedByDescending { it.value }.take(2)
            .map { Candidate(Kind.BACKGROUND, it.key, it.value) }
        return (cleartext + background).filter { it.id !in dismissed }.take(MAX_SUGGESTIONS)
    }

    /** The rule a suggestion creates. */
    fun ruleFor(c: Candidate, id: String, label: String) = FilterRule(
        id = id,
        label = label,
        action = FilterRule.Action.DENY,
        source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY,
        matchPackage = c.packageName,
        matchEncryption = if (c.kind == Kind.CLEARTEXT) EncryptionStatus.CLEARTEXT else null,
        matchBackground = if (c.kind == Kind.BACKGROUND) true else null,
    )
}
