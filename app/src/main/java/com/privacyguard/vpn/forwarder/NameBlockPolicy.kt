package com.privacyguard.vpn.forwarder

import com.privacyguard.app.adprivacy.AdSdkPolicy
import com.privacyguard.app.adprivacy.AdSdkVerdict
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.vpn.firewall.DomainFilter

/**
 * Why a host name is blocked for an app, or null. The one decision for DNS names,
 * CNAME targets, TLS SNI and QUIC SNI, so they can never disagree.
 *
 * Ad-SDK policy is per app (DomainFilter is not): it blocks SDK hosts in other
 * apps, and keeps login/push/deep-link hosts open even when a downloaded list
 * contains them. Precedence: see [DnsBlockDecision].
 */
class NameBlockPolicy(
    private val filterEngine: FilterEngine,
    private val domainFilter: DomainFilter? = null,
    private val securityFilter: DomainFilter? = null,
    private val adSdkPolicy: AdSdkPolicy? = null,
) {

    fun reasonFor(name: String, ownerPackage: String?): DnsBlockDecision.Reason? {
        // User rules compare names as written, so "ads.example.com." skipped a rule for
        // "ads.example.com" while the lists (which trim the dot) still matched it.
        val host = normalize(name)
        val sdkVerdict = adSdkPolicy?.decide(ownerPackage, host)
        val userRule = filterEngine.domainRuleFor(host, ownerPackage)
        return DnsBlockDecision.decide(
            userRule = userRule,
            onSecurityList = securityFilter?.isBlocked(host) == true,
            onLevelBlocklist = sdkVerdict !is AdSdkVerdict.KeepOpen && domainFilter?.isBlocked(host) == true,
            adSdkBlocks = sdkVerdict is AdSdkVerdict.Block,
        ) ?: if (userRule == null && filterEngine.isDomainBlocked(host)) DnsBlockDecision.Reason.USER_RULE else null
    }

    companion object {
        /** As DomainFilter stores names: trimmed, lower case, no trailing dot. */
        fun normalize(name: String): String = name.trim().lowercase().trimEnd('.')
    }
}
