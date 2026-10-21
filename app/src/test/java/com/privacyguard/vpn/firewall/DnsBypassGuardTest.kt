package com.privacyguard.vpn.firewall

import com.privacyguard.core.session.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsBypassGuardTest {

    @Test fun blocksDotPort() {
        assertTrue(DnsBypassGuard.isEncryptedDnsPort(853))
        assertFalse(DnsBypassGuard.isEncryptedDnsPort(53))
        assertFalse(DnsBypassGuard.isEncryptedDnsPort(443))
    }

    @Test fun matchesDohHostsAndSubdomains() {
        assertTrue(DnsBypassGuard.isDohHost("dns.google"))
        assertTrue(DnsBypassGuard.isDohHost("mozilla.cloudflare-dns.com"))
        assertTrue(DnsBypassGuard.isDohHost("DNS.Quad9.net."))
        assertTrue(DnsBypassGuard.isDohHost("abc123.dns.nextdns.io"))
    }

    @Test fun doesNotMatchLookalikesOrNormalSites() {
        assertFalse(DnsBypassGuard.isDohHost("google.com"))
        assertFalse(DnsBypassGuard.isDohHost("evilcloudflare-dns.com"))
        assertFalse(DnsBypassGuard.isDohHost("www.cloudflare.com"))
        assertFalse(DnsBypassGuard.isDohHost(null))
        assertFalse(DnsBypassGuard.isDohHost(""))
    }

    @Test fun dohByIpOnlyOn443() {
        assertTrue(DnsBypassGuard.isDohAddress("1.1.1.1", 443))
        assertFalse(DnsBypassGuard.isDohAddress("1.1.1.1", 53))
        assertFalse(DnsBypassGuard.isDohAddress("142.250.80.46", 443))
    }

    @Test fun dohByIpv6Address() {
        // The form Ipv6Packet.parseAddress produces for the IPv6 path.
        val google = com.privacyguard.core.packet.Ipv6Packet.parseAddress(
            byteArrayOf(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte()), 0,
        )
        assertTrue(DnsBypassGuard.isDohAddress(google, 443))
        assertTrue(DnsBypassGuard.isDohAddress("2606:4700:4700::1111", 443))
        assertFalse(DnsBypassGuard.isDohAddress("2606:4700:4700::1111", 80))
        assertFalse(DnsBypassGuard.isDohAddress("2a00:1450:4001::200e", 443))
    }

    @Test fun plainDnsOutsideTheTunnelResolver() {
        assertTrue(DnsBypassGuard.isUnfilteredPlainDns(53))
        assertFalse(DnsBypassGuard.isUnfilteredPlainDns(443))
        assertFalse(DnsBypassGuard.isUnfilteredPlainDns(5353))
    }

    // ── Downloaded lists ─────────────────────────────────────────────────────

    /** The guard is process-wide: leave it with the built-in lists only. */
    @org.junit.After fun resetDownloadedLists() = DnsBypassGuard.update(emptyList(), emptyList())

    @Test fun downloadedListIsUsed() {
        assertFalse(DnsBypassGuard.isDohHost("doh.example-resolver.net"))
        DnsBypassGuard.update(listOf("example-resolver.net"), listOf("203.0.113.53"))
        assertTrue(DnsBypassGuard.isDohHost("doh.example-resolver.net"))
        assertTrue(DnsBypassGuard.isDohHost("Example-Resolver.NET."))
        assertFalse(DnsBypassGuard.isDohHost("badexample-resolver.net"))
        assertTrue("built-in still applies", DnsBypassGuard.isDohHost("dns.google"))
        assertTrue(DnsBypassGuard.isListedDohAddress("203.0.113.53", 443))
        assertFalse(DnsBypassGuard.isListedDohAddress("203.0.113.53", 80))
        assertFalse("a downloaded address is not dropped at the SYN", DnsBypassGuard.isDohAddress("203.0.113.53", 443))
    }

    @Test fun builtInFallbackWhenTheDownloadIsEmpty() {
        DnsBypassGuard.update(listOf("example-resolver.net"), listOf("203.0.113.53"))
        DnsBypassGuard.update(emptyList(), emptyList())   // failed download / lists switched off
        assertFalse(DnsBypassGuard.isDohHost("doh.example-resolver.net"))
        assertFalse(DnsBypassGuard.isListedDohAddress("203.0.113.53", 443))
        assertTrue(DnsBypassGuard.isDohHost("dns.google"))
        assertTrue(DnsBypassGuard.isDohHost("mozilla.cloudflare-dns.com"))
        assertTrue(DnsBypassGuard.isDohAddress("1.1.1.1", 443))
    }

    // ── Before any data (both TCP and UDP call this) ─────────────────────────

    @Test fun dotIsAlwaysStoppedAndBuiltInDohUnlessAllowed() {
        DnsBypassGuard.update(emptyList(), listOf("203.0.113.53"))
        val none: (String) -> Boolean = { false }
        val all: (String) -> Boolean = { true }
        assertEquals(BlockSource.DOT, DnsBypassGuard.blockedBeforeData("198.51.100.7", 853, none))
        assertEquals("an address Allow does not open DoT", BlockSource.DOT, DnsBypassGuard.blockedBeforeData("1.1.1.1", 853, all))
        assertEquals(BlockSource.DOH, DnsBypassGuard.blockedBeforeData("1.1.1.1", 443, none))
        assertEquals(BlockSource.DOH, DnsBypassGuard.blockedBeforeData("2606:4700:4700::1111", 443, none))
        assertNull("user Allow", DnsBypassGuard.blockedBeforeData("1.1.1.1", 443, all))
        assertNull("downloaded: judged at the ClientHello", DnsBypassGuard.blockedBeforeData("203.0.113.53", 443, none))
        assertNull(DnsBypassGuard.blockedBeforeData("1.1.1.1", 80, none))
    }

    // ── ClientHello decision for listed addresses ────────────────────────────

    private val listedIp = "203.0.113.53"
    private fun hello(sni: String?, ip: String = listedIp, allowName: Boolean = false, allowIp: Boolean = false) =
        DnsBypassGuard.blocksClientHello(sni, ip, 443, userAllowsName = { allowName }, userAllowsAddress = { allowIp })

    @Test fun clientHelloToAListedAddressIsJudgedByItsName() {
        DnsBypassGuard.update(listOf("example-resolver.net"), listOf(listedIp))
        assertTrue("DoH name", hello("doh.example-resolver.net"))
        assertFalse("normal site on a shared address", hello("www.example.org"))
        assertTrue("no name to a listed address", hello(null))
        assertTrue("blank name counts as none", hello(""))
        assertFalse("no name to an unlisted address", hello(null, ip = "198.51.100.7"))
        assertTrue("DoH name to any address", hello("dns.google", ip = "198.51.100.7"))
    }

    @Test fun userAllowOverridesDohBlocks() {
        DnsBypassGuard.update(listOf("example-resolver.net"), listOf(listedIp))
        assertFalse(hello("doh.example-resolver.net", allowName = true))
        assertFalse(hello("dns.google", allowName = true))
        assertFalse(hello(null, allowIp = true))
        assertTrue("an address Allow does not open a DoH name", hello("dns.google", allowIp = true))
    }
}
