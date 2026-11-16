package com.privacyguard.core.http

import com.privacyguard.core.http.HttpRequestHost.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRequestHostTest {

    private fun read(s: String) = HttpRequestHost.read(s.toByteArray(Charsets.ISO_8859_1))

    @Test fun reads_the_host_header() =
        assertEquals(Result.Host("ads.example.com"), read("GET /a HTTP/1.1\r\nHost: ads.example.com\r\nAccept: */*\r\n\r\n"))

    @Test fun host_is_normalized() =
        assertEquals(Result.Host("ads.example.com"), read("POST / HTTP/1.1\r\nhOsT:\tADS.Example.COM.:8080 \r\n\r\n"))

    @Test fun absolute_form_target_wins_and_must_agree_with_host() {
        assertEquals(Result.Host("t.example"), read("GET http://t.example/x HTTP/1.1\r\n\r\n"))
        assertEquals(Result.Host("t.example"), read("GET http://T.example:80/x HTTP/1.1\r\nHost: t.example\r\n\r\n"))
        assertEquals(Result.Unreadable, read("GET http://tracker.example/x HTTP/1.1\r\nHost: allowed.example\r\n\r\n"))
    }

    @Test fun connect_uses_its_authority() =
        assertEquals(Result.Host("t.example"), read("CONNECT t.example:443 HTTP/1.1\r\nHost: t.example:443\r\n\r\n"))

    @Test fun two_host_headers_are_unreadable() =
        assertEquals(Result.Unreadable, read("GET / HTTP/1.1\r\nHost: a.example\r\nHost: b.example\r\n\r\n"))

    @Test fun no_host_or_an_address_is_no_name() {
        assertEquals(Result.NoHost, read("GET / HTTP/1.0\r\n\r\n"))
        assertEquals(Result.NoHost, read("GET / HTTP/1.1\r\nHost: 93.184.216.34\r\n\r\n"))
        assertEquals(Result.NoHost, read("GET / HTTP/1.1\r\nHost: [2001:db8::1]:80\r\n\r\n"))
        assertEquals(Result.NoHost, read("GET / HTTP/1.1\r\nHost:\r\n\r\n"))
    }

    @Test fun a_host_that_is_no_name_is_unreadable() {
        assertEquals(Result.Unreadable, read("GET / HTTP/1.1\r\nHost: a b.example\r\n\r\n"))
        assertEquals(Result.Unreadable, read("GET / HTTP/1.1\r\nHost: " + "a".repeat(254) + "\r\n\r\n"))
        assertEquals(Result.Unreadable, read("GET / HTTP/1.1\r\nHost: x.example:99999\r\n\r\n"))
    }

    @Test fun incomplete_headers_need_more_and_the_cap_is_unreadable() {
        assertEquals(Result.NeedMore, read("GE"))
        assertEquals(Result.NeedMore, read("GET / HTTP/1.1\r\nHost: a.example\r\n"))
        val huge = "GET / HTTP/1.1\r\nX-Pad: " + "p".repeat(HttpRequestHost.MAX_HEADER_BYTES) + "\r\nHost: a.example\r\n\r\n"
        assertEquals(Result.Unreadable, read(huge))
    }

    @Test fun other_protocols_are_not_http() {
        assertEquals(Result.NotHttp, read("SSH-2.0-OpenSSH_9.6\r\n"))
        assertEquals(Result.NotHttp, read("GETX / HTTP/1.1\r\n\r\n"))
        assertEquals(Result.NotHttp, HttpRequestHost.read(byteArrayOf(0x16, 3, 1, 0, 5)))
        assertEquals(Result.NotHttp, HttpRequestHost.read(ByteArray(0)))
    }
}
