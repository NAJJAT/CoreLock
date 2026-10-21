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
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileDescriptor
import kotlin.random.Random

class NameBlockPolicyTest {

    private fun lists(vararg names: String) = DomainFilter(useNativeBloom = false).apply { rebuild(names.toList()) }

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
            val rules = List(rnd.nextInt(0, 5)) {
                val pattern = (if (rnd.nextInt(3) == 0) "*." else "") + randomName()
                val base = if (rnd.nextBoolean()) FilterRule.allowDomain(pattern)
                else FilterRule.blockDomain(pattern, source = FilterRule.Source.USER)
                base.copy(
                    matchPackage = if (rnd.nextInt(4) == 0) packages.filterNotNull().random(rnd) else null,
                    overridesSecurity = base.action == FilterRule.Action.ALLOW && rnd.nextBoolean(),
                )
            }
            val sdkOn = rnd.nextBoolean()

            val engine = FilterEngine().apply { setRules(rules) }
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
                    val expected = model(name, pkg, rules, listNames, securityNames, sdkOn)
                    val context = "round $round: $name pkg=$pkg rules=$rules lists=$listNames security=$securityNames sdk=$sdkOn"
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
        listNames: List<String>, securityNames: List<String>, sdkOn: Boolean,
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
        // FilterEngine keeps rules in priority order, lowest first (stable for ties).
        val rule = rules.sortedBy { it.priority }.firstOrNull(::ruleMatches)
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
        return null
    }
}
