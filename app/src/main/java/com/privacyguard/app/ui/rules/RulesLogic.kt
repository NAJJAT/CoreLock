package com.privacyguard.app.ui.rules

import com.privacyguard.core.filter.FilterRule
import java.net.IDN
import java.util.Locale

/**
 * Pure decisions of Settings › Rules and allowed sites (DESIGN_SPEC_1.1.md §5.6, R16):
 * what the user typed in the add sheet, and how a rule is described in its row.
 * No Android types, so it runs on the JVM.
 */
object RulesLogic {

    /** What a new rule is about. The add sheet offers these three, in this order. */
    enum class Kind { SITE, APP, ADDRESS }

    /** How a rule row describes the rule's effect. Words first; colour only repeats them. */
    enum class Effect { BLOCK, BLOCK_IN_BACKGROUND, ALLOW, ALLOW_DESPITE_MALWARE }

    fun effectOf(rule: FilterRule): Effect = when {
        rule.action == FilterRule.Action.DENY && rule.matchBackground == true -> Effect.BLOCK_IN_BACKGROUND
        rule.action == FilterRule.Action.DENY -> Effect.BLOCK
        rule.overridesSecurity -> Effect.ALLOW_DESPITE_MALWARE
        else -> Effect.ALLOW
    }

    /**
     * The site name a rule should match, or null when [input] is not one. People paste
     * addresses from the browser, so a scheme, a path, a port and a user part are cut
     * off ("https://Ads.Example.com:443/x" → "ads.example.com"). The result is
     * normalized as every name the engine matches (CLAUDE.md §5.3: lower case, no
     * trailing dot) and in ASCII, because the engine only ever sees the ASCII form of an
     * international name. A leading "*." is kept: it is the wildcard the rules support.
     *
     * Public suffixes ("*.xyz") are accepted on purpose, unlike for downloaded lists: a
     * user may want to block a whole top-level domain, and it is their own rule.
     */
    fun parseSite(input: String): String? {
        var s = input.trim()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        // One colon is a port; more than one is an IPv6 address, which is not a site.
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        s = s.trimEnd('.').lowercase(Locale.ROOT)
        val wildcard = s.startsWith("*.")
        val name = if (wildcard) s.removePrefix("*.") else s
        if (name.isEmpty() || name.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val ascii = if (name.all { it.code < 0x80 }) name
            else runCatching { IDN.toASCII(name, 0).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (ascii.length > MAX_NAME) return null
        val labels = ascii.split('.')
        if (labels.any { !isLdhLabel(it) }) return null
        // "1.2.3.4" is an address; the Address kind handles it, with its own checks.
        if (ascii.all { it.isDigit() || it == '.' }) return null
        return if (wildcard) "*.$ascii" else ascii
    }

    /**
     * An Android package name: at least two parts, each starting with a letter, then
     * letters, digits or "_" (the rule Android itself applies to an application ID).
     */
    fun isValidPackage(input: String): Boolean {
        val parts = input.trim().split('.')
        return parts.size >= 2 && parts.all { part ->
            part.isNotEmpty() && part.length <= MAX_LABEL &&
                part[0].isAsciiLetter() && part.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '_' }
        }
    }

    /** True when the add sheet may create a rule from [input] of [kind]. */
    fun isValid(kind: Kind, input: String): Boolean = when (kind) {
        Kind.SITE -> parseSite(input) != null
        Kind.APP -> isValidPackage(input)
        Kind.ADDRESS -> RulesViewModel.isValidIpOrCidr(input.trim())
    }

    /** Shown as an error only once something was typed: an empty field is not a mistake yet. */
    fun showsError(kind: Kind, input: String): Boolean = input.isNotBlank() && !isValid(kind, input)

    /**
     * The search of the rules list: the label and every matched value. [appName] lets
     * "WhatsApp" find a rule whose label was written in another language.
     */
    fun matches(rule: FilterRule, query: String, appName: String?): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOfNotNull(rule.label, rule.matchDomain, rule.matchPackage, rule.matchIp, appName)
            .any { it.contains(q, ignoreCase = true) }
    }

    private fun isLdhLabel(label: String): Boolean =
        label.length in 1..MAX_LABEL && label.first() != '-' && label.last() != '-' &&
            label.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '-' }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit() = this in '0'..'9'

    private const val MAX_LABEL = 63
    private const val MAX_NAME = 253
}
