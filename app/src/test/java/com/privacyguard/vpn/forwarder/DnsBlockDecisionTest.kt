package com.privacyguard.vpn.forwarder

import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsBlockDecision.Reason
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

/** A user's Allow beats ad and tracker lists, but malware only after a confirmed warning. */
class DnsBlockDecisionTest {

    private val allow = FilterRule.allowDomain("x.example")
    private val allowRisky = allow.copy(overridesSecurity = true)
    private val block = FilterRule.blockDomain("x.example", source = FilterRule.Source.USER)

    @Test
    fun allow_overrides_ad_and_tracker_lists() {
        assertNull(DnsBlockDecision.decide(allow, onSecurityList = false, onLevelBlocklist = true, adSdkBlocks = false))
    }

    @Test
    fun allow_overrides_the_ad_sdk_policy() {
        assertNull(DnsBlockDecision.decide(allow, onSecurityList = false, onLevelBlocklist = false, adSdkBlocks = true))
    }

    @Test
    fun plain_allow_does_not_open_a_malware_host() {
        assertEquals(Reason.SECURITY_LIST, DnsBlockDecision.decide(allow, onSecurityList = true, onLevelBlocklist = true, adSdkBlocks = false))
    }

    @Test
    fun allow_after_the_warning_opens_a_malware_host() {
        assertNull(DnsBlockDecision.decide(allowRisky, onSecurityList = true, onLevelBlocklist = true, adSdkBlocks = true))
    }

    @Test
    fun user_block_always_wins() {
        assertEquals(Reason.USER_RULE, DnsBlockDecision.decide(block, onSecurityList = false, onLevelBlocklist = false, adSdkBlocks = false))
    }

    @Test
    fun without_a_rule_the_lists_apply() {
        assertEquals(Reason.SECURITY_LIST, DnsBlockDecision.decide(null, true, true, false))
        assertEquals(Reason.BLOCKLIST, DnsBlockDecision.decide(null, false, true, false))
        assertEquals(Reason.AD_SDK, DnsBlockDecision.decide(null, false, false, true))
        assertNull(DnsBlockDecision.decide(null, false, false, false))
    }

    @Test
    fun security_lookup_covers_parents_and_wildcards() {
        val c = BlocklistRepo.securityCandidates("A.Bad.Example.")
        assertTrue(c.containsAll(listOf("a.bad.example", "bad.example", "*.bad.example", "*.a.bad.example")))
        assertTrue("a bare TLD is never a candidate", "example" !in c && "*.example" !in c)
    }

    @Test
    fun dns_handler_applies_the_precedence() {
        val engine = FilterEngine()
        val lists = DomainFilter().apply { rebuild(listOf("ads.example", "bad.example")) }
        val security = DomainFilter().apply { rebuild(listOf("bad.example")) }
        val blocked = mutableListOf<String>()
        val handler = DnsHandler(
            filterEngine = engine,
            tunWriter = TunWriter(TunInterface(FileDescriptor())),
            protectSocket = { false }, protectTcpSocket = { false },
            domainFilter = lists, securityFilter = security,
        ).apply { queryListener = DnsHandler.QueryListener { _, d, wasBlocked -> if (wasBlocked) blocked += d } }
        engine.addRule(FilterRule.allowDomain("ads.example"))
        engine.addRule(FilterRule.allowDomain("bad.example"))

        listOf("ads.example", "bad.example").forEach { handler.handle(ip, query(it)) }
        assertEquals(listOf("bad.example"), blocked)
        handler.shutdown()
    }

    private val ip = IpPacket(4, 20, 0, 40, 1, false, false, 0, 64, 17, 0, "10.0.0.2", "10.0.0.1", ByteArray(0), ByteArray(40))
    private fun query(name: String) = DnsPacket.buildQuery(name, id = 9).let { UdpPacket(40000, 53, 8 + it.size, 0, it) }
}
