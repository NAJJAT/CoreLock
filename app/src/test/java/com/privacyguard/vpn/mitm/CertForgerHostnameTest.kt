package com.privacyguard.vpn.mitm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertForgerHostnameTest {

    @Test fun acceptsNormalHosts() {
        assertTrue(CertForger.isValidHostname("example.com"))
        assertTrue(CertForger.isValidHostname("api.eu-1.Example.co.uk"))
        assertTrue(CertForger.isValidHostname("xn--bcher-kva.example"))
    }

    @Test fun rejectsDnInjectionAndWildcards() {
        assertFalse(CertForger.isValidHostname("evil.com, O=Google"))
        assertFalse(CertForger.isValidHostname("evil.com+CN=bank.com"))
        assertFalse(CertForger.isValidHostname("*.example.com"))
        assertFalse(CertForger.isValidHostname("exa mple.com"))
    }

    @Test fun rejectsMalformedNames() {
        assertFalse(CertForger.isValidHostname(""))
        assertFalse(CertForger.isValidHostname("localhost"))
        assertFalse(CertForger.isValidHostname("192.168.1.1"))
        assertFalse(CertForger.isValidHostname("-bad.example.com"))
        assertFalse(CertForger.isValidHostname("bad-.example.com"))
        assertFalse(CertForger.isValidHostname("a..example.com"))
        assertFalse(CertForger.isValidHostname("a".repeat(64) + ".com"))
        assertFalse(CertForger.isValidHostname(("a".repeat(60) + ".").repeat(5) + "com"))
    }
}
