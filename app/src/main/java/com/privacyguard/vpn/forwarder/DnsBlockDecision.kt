package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterRule

/**
 * Whether a DNS name is answered NXDOMAIN, and why. Order of precedence:
 *
 *  1. The user's Block rule.
 *  2. Malware and phishing lists. A user Allow rule overrides them only when the
 *     user confirmed the warning shown when creating it ([FilterRule.overridesSecurity]).
 *  3. The user's Allow rule: beats the ad, tracker and telemetry lists and the
 *     Ad-SDK policy.
 *  4. Ad, tracker and telemetry lists for the protection level, and the Ad-SDK policy.
 */
object DnsBlockDecision {

    enum class Reason { USER_RULE, SECURITY_LIST, BLOCKLIST, AD_SDK }

    /** Returns why the name is blocked, or null to resolve it. */
    fun decide(
        userRule: FilterRule?,
        onSecurityList: Boolean,
        onLevelBlocklist: Boolean,
        adSdkBlocks: Boolean,
    ): Reason? {
        if (userRule?.action == FilterRule.Action.DENY) return Reason.USER_RULE
        val userAllows = userRule?.action == FilterRule.Action.ALLOW
        if (onSecurityList && !(userAllows && userRule!!.overridesSecurity)) return Reason.SECURITY_LIST
        if (userAllows) return null
        if (onLevelBlocklist) return Reason.BLOCKLIST
        if (adSdkBlocks) return Reason.AD_SDK
        return null
    }
}
