package com.privacyguard.vpn.mitm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiemEndpointTest {

    @Test fun acceptsHttps() {
        assertNotNull(SiemEndpoint.validate("https://siem.example.com/api"))
        assertNotNull(SiemEndpoint.validate("  HTTPS://siem.example.com:8443  "))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(SiemEndpoint.validate("http://siem.example.com"))
        assertNull(SiemEndpoint.validate("siem.example.com"))
        assertNull(SiemEndpoint.validate("https://"))
        assertNull(SiemEndpoint.validate("https://user:pw@siem.example.com"))
        assertNull(SiemEndpoint.validate(""))
        assertNull(SiemEndpoint.validate("ftp://siem.example.com"))
    }

    @Test fun signsOnlyWithSeparateKey() {
        assertNull(SiemEndpoint.signature("{}", "", "token"))
        assertNull(SiemEndpoint.signature("{}", "token", "token"))
        // RFC 4231-style sanity check: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            SiemEndpoint.signature("The quick brown fox jumps over the lazy dog", "key", "token"),
        )
    }

    @Test fun parsesPins() {
        val good = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertEquals(SiemEndpoint.Pin.None, SiemEndpoint.parsePin("  "))
        assertEquals(SiemEndpoint.Pin.Valid(good), SiemEndpoint.parsePin(" $good "))
        // Malformed pins must be Invalid, not None, so shipping fails closed.
        assertEquals(SiemEndpoint.Pin.Invalid, SiemEndpoint.parsePin("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertEquals(SiemEndpoint.Pin.Invalid, SiemEndpoint.parsePin("sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        assertEquals(SiemEndpoint.Pin.Invalid, SiemEndpoint.parsePin("sha256/not base64!"))
        assertEquals(SiemEndpoint.Pin.Invalid, SiemEndpoint.parsePin("sha256/AAAA"))
    }

    @Test fun tagsEndpointsStably() {
        fun tag(s: String) = SiemEndpoint.tag(SiemEndpoint.validate(s)!!)
        assertEquals(tag("https://siem.example.com/api"), tag("HTTPS://SIEM.example.com/api/"))
        assertNotEquals(tag("https://siem.example.com/api"), tag("https://other.example.com/api"))
        assertNotEquals(tag("https://siem.example.com/api"), tag("https://siem.example.com:8443/api"))
        assertNotEquals(tag("https://siem.example.com/api"), tag("https://siem.example.com/other"))
        // The tag sits inside "batch_<ts>_<tag>_<count>.json", so it must not contain '_'.
        assertTrue(tag("https://siem.example.com/api").matches(Regex("[0-9a-f]{16}")))
    }

    @Test fun escapesControlCharacters() {
        assertEquals("a\\\"b\\\\c\\n\\t\\u0001", SiemEndpoint.escapeJson("a\"b\\c\n\t\u0001"))
    }
}
