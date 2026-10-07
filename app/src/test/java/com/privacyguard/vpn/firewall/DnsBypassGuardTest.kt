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
}
