package com.privacyguard.vpn.forwarder

import com.privacyguard.app.adprivacy.AdSdkCatalog
import com.privacyguard.app.adprivacy.AdSdkPolicy
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsBlockDecision.Reason
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import kotlin.random.Random

class NameBlockPolicyTest {

    private fun lists(vararg names: String) = DomainFilter().apply { rebuild(names.toList()) }

    // ── Trailing dot (regression) ────────────────────────────────────────────

    @Test
    fun user_allow_matches_a_name_with_a_trailing_dot() {
        val engine = FilterEngine().apply { addRule(FilterRule.allowDomain("ads.example.com")) }
        val policy = NameBlockPolicy(engine, domainFilter = lists("ads.example.com"))
        // Before: the rule did not match "ads.example.com.", the list did, so it was blocked.
        assertNull(policy.reasonFor("ads.example.com.", null))
        assertNull(policy.reasonFor("ADS.Example.com.", null))
    }

    @Test
    fun user_block_matches_a_name_with_a_trailing_dot() {
        val engine = FilterEngine().apply { addRule(FilterRule.blockDomain("tracker.example", source = FilterRule.Source.USER)) }
        val policy = NameBlockPolicy(engine)
        // Before: no rule matched and no list had it, so it resolved.
        assertEquals(Reason.USER_RULE, policy.reasonFor("tracker.example.", null))
        assertEquals(Reason.USER_RULE, policy.reasonFor(" Tracker.EXAMPLE. ", null))
    }

    @Test
    fun ad_sdk_policy_sees_the_normalized_name() {
        val policy = NameBlockPolicy(FilterEngine(), adSdkPolicy = AdSdkPolicy(enabled = { true }))
        assertEquals(Reason.AD_SDK, policy.reasonFor("SDK.AppsFlyer.com.", "com.example.game"))
    }

    @Test
    fun user_allows_finds_the_allow_rule_for_a_doh_name() {
        val engine = FilterEngine().apply {
            addRule(FilterRule.allowDomain("*.dns.google"))
            addRule(FilterRule.allowDomain("doh.example.net").copy(matchPackage = "com.example.browser"))
            addRule(FilterRule.blockDomain("dns.quad9.net", source = FilterRule.Source.USER))
        }
        val policy = NameBlockPolicy(engine)
        assertTrue(policy.userAllows("DNS.Google.", null))
        assertTrue(policy.userAllows("doh.example.net", "com.example.browser"))
        assertFalse("other app", policy.userAllows("doh.example.net", "com.other"))
        assertFalse("a block rule", policy.userAllows("dns.quad9.net", null))
        assertFalse("no rule", policy.userAllows("cloudflare-dns.com", null))
    }

    @Test
    fun whitelist_mode_blocks_names_without_an_allow_rule() {
        val engine = FilterEngine(defaultAction = FilterRule.Action.DENY).apply {
            addRule(FilterRule.allowDomain("*.example.com"))
            addRule(FilterRule.allowDomain("app-only.example.org").copy(matchPackage = "com.example.app"))
        }
        val policy = NameBlockPolicy(engine)
        assertNull(policy.reasonFor("cdn.example.com.", null))
        assertNull(policy.reasonFor("app-only.example.org", "com.example.app"))
        assertEquals(Reason.USER_RULE, policy.reasonFor("app-only.example.org", "com.other"))
        assertEquals(Reason.USER_RULE, policy.reasonFor("news.example.net", null))
    }

    // ── DNS and the shared policy agree (property test) ──────────────────────

    /**
     * Random names, rules and lists: DnsHandler's decision, the policy's decision for
     * the same name in any spelling (case, trailing dot, as SNI may arrive), and an
     * independent model of the documented precedence must all be the same.
     */
    @Test
    fun dns_and_sni_decisions_match_a_model_of_the_precedence() {
        val rnd = Random(20261021)
        val labels = listOf("a", "ads", "cdn", "x", "graph", "sdk", "m")
        val bases = listOf("example.com", "example.org", "bad.example", "facebook.com", "appsflyer.com",
            "doubleclick.net", "branch.io", "app.link", "adjust.com")
        val packages = listOf(null, "com.example.game", "com.facebook.katana", "com.google.android.gm")
        fun randomName(): String {
            var n = bases.random(rnd)
            repeat(rnd.nextInt(0, 3)) { n = labels.random(rnd) + "." + n }
            return n
        }
        fun spell(n: String): String {
            val cased = n.map { if (rnd.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
            return if (rnd.nextBoolean()) "$cased." else cased
        }

        repeat(400) { round ->
            val listNames = List(rnd.nextInt(0, 6)) { (if (rnd.nextInt(4) == 0) "*." else "") + randomName() }
            val securityNames = List(rnd.nextInt(0, 3)) { randomName() }
            val randomRules = List(rnd.nextInt(0, 5)) {
                val pattern = (if (rnd.nextInt(3) == 0) "*." else "") + randomName()
                val base = if (rnd.nextBoolean()) FilterRule.allowDomain(pattern)
                // Same priority as an Allow, as every screen creates user rules, so ties happen.
                else FilterRule.blockDomain(pattern, source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY)
                base.copy(
                    matchPackage = if (rnd.nextInt(4) == 0) packages.filterNotNull().random(rnd) else null,
                    overridesSecurity = base.action == FilterRule.Action.ALLOW && rnd.nextBoolean(),
                )
            }
            // Random patterns almost never collide, so add an opposite twin of one rule
            // (same name, same app) often enough that equally specific rules really meet.
            val rules = if (randomRules.isNotEmpty() && rnd.nextInt(3) == 0) {
                val r = randomRules.random(rnd)
                val opposite = if (r.action == FilterRule.Action.ALLOW) FilterRule.Action.DENY else FilterRule.Action.ALLOW
                randomRules + r.copy(id = r.id + "-twin", action = opposite, overridesSecurity = opposite == FilterRule.Action.ALLOW && rnd.nextBoolean())
            } else randomRules
            val sdkOn = rnd.nextBoolean()
            // Allow by default (blacklist mode) or Deny by default (whitelist mode).
            val denyByDefault = rnd.nextBoolean()

            val engine = FilterEngine(
                defaultAction = if (denyByDefault) FilterRule.Action.DENY else FilterRule.Action.ALLOW,
            ).apply { setRules(rules) }
            val levelList = lists(*listNames.toTypedArray())
            val securityList = lists(*securityNames.toTypedArray())
            val sdk = AdSdkPolicy(enabled = { sdkOn })
            val policy = NameBlockPolicy(engine, levelList, securityList, sdk)
            val handler = DnsHandler(
                filterEngine = engine, tunWriter = TunWriter(TunInterface(FileDescriptor())),
                protectSocket = { false }, protectTcpSocket = { false },
                domainFilter = levelList, securityFilter = securityList, adSdkPolicy = sdk,
            )
            try {
                repeat(25) {
                    val name = randomName()
                    val pkg = packages.random(rnd)
                    val expected = model(name, pkg, rules, listNames, securityNames, sdkOn, denyByDefault)
                    val context = "round $round: $name pkg=$pkg rules=$rules lists=$listNames security=$securityNames sdk=$sdkOn denyByDefault=$denyByDefault"
                    assertEquals("dns $context", expected, handler.blockReasonFor(name, pkg))
                    val sni = spell(name)
                    assertEquals("sni '$sni' $context", expected, policy.reasonFor(sni, pkg))
                }
            } finally {
                handler.shutdown()
            }
        }
    }

    /** The precedence of [DnsBlockDecision], written out without the production code. */
    private fun model(
        host: String, pkg: String?, rules: List<FilterRule>,
        listNames: List<String>, securityNames: List<String>, sdkOn: Boolean, denyByDefault: Boolean,
    ): Reason? {
        fun covers(entry: String, h: String): Boolean {
            val e = entry.removePrefix("*.")
            return h == e || h.endsWith(".$e")
        }
        fun ruleMatches(r: FilterRule): Boolean {
            if (r.matchPackage != null && r.matchPackage != pkg) return false
            val p = r.matchDomain!!
            return if (p.startsWith("*.")) covers(p, host) else host == p
        }
        // FilterEngine's order, written out: priority, then app-specific before every app,
        // exact before wildcard and longer wildcard before shorter, then Block before Allow.
        val rule = rules.filter(::ruleMatches).minWithOrNull(
            compareBy<FilterRule> { it.priority }
                .thenBy { if (it.matchPackage != null) 0 else 1 }
                .thenBy { if (it.matchDomain!!.startsWith("*.")) 1 else 0 }
                .thenByDescending { it.matchDomain!!.length }
                .thenBy { if (it.action == FilterRule.Action.DENY) 0 else 1 },
        )
        if (rule?.action == FilterRule.Action.DENY) return Reason.USER_RULE
        val allow = rule?.action == FilterRule.Action.ALLOW
        if (securityNames.any { covers(it, host) } && !(allow && rule!!.overridesSecurity)) return Reason.SECURITY_LIST
        if (allow) return null
        val keepOpen = AdSdkCatalog.neverBlock.any { covers(it.domain, host) }
        if (!keepOpen && listNames.any { covers(it, host) }) return Reason.BLOCKLIST
        if (!keepOpen && sdkOn) {
            val sdk = AdSdkCatalog.sdks.firstOrNull { s -> s.domains.any { covers(it, host) } }
            if (sdk != null && (pkg == null || sdk.ownerPackagePrefixes.none { pkg.startsWith(it) })) return Reason.AD_SDK
        }
        // Whitelist mode: a name no rule matched (for this app) is blocked as by a user rule.
        return if (rule == null && denyByDefault) Reason.USER_RULE else null
    }
}
