package com.privacyguard.vpn.firewall

import org.junit.Assert.assertFalse
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
}
