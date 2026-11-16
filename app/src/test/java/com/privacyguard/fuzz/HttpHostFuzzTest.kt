package com.privacyguard.fuzz

import com.privacyguard.core.http.HttpRequestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The cleartext HTTP Host reader (F-8) on hostile bytes: never throws, and any name it
 * returns is a lowercase hostname of at most 253 characters that appears in the input.
 * Plus a round trip: a well-formed request built around a random name gives that name.
 */
class HttpHostFuzzTest {

    private val seeds = listOf(
        "GET / HTTP/1.1\r\nHost: ads.example.com\r\n\r\n",
        "POST http://t.example/x HTTP/1.1\r\nHost: t.example\r\nContent-Length: 3\r\n\r\nabc",
        "CONNECT t.example:443 HTTP/1.1\r\nHost: t.example:443\r\n\r\n",
        "HEAD / HTTP/1.0\r\nUser-Agent: x\r\n\r\n",
    ).map { it.toByteArray(Charsets.ISO_8859_1) }

    @Test fun hostile_bytes_never_give_a_bad_name() = Fuzz.run("http-host", seeds, rngSeed = 0x4877) { data ->
        val r = HttpRequestHost.read(data)
        if (r is HttpRequestHost.Result.Host) {
            val n = r.name
            assertTrue(n, n.isNotEmpty() && n.length <= 253 && n == n.lowercase() && !n.endsWith("."))
            assertTrue(n, n.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' })
            assertTrue(n, String(data, Charsets.ISO_8859_1).lowercase().contains(n))
        }
    }

    @Test fun built_requests_round_trip() {
        val rng = Random(0x4878)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-"
        repeat(5_000) {
            val labels = List(rng.nextInt(1, 5)) { String(CharArray(rng.nextInt(1, 20)) { alphabet[rng.nextInt(alphabet.length)] }) }
            val name = labels.joinToString(".")
            val pad = "X-P: " + "q".repeat(rng.nextInt(0, 500))
            val req = if (rng.nextBoolean()) "GET /p HTTP/1.1\r\n$pad\r\nHost: ${name.uppercase()}\r\n\r\n"
                      else "GET /p HTTP/1.1\r\nHost: $name:${rng.nextInt(1, 65_536)}\r\n$pad\r\n\r\n"
            val r = HttpRequestHost.read(req.toByteArray(Charsets.ISO_8859_1))
            val isIpv4 = labels.size == 4 && labels.all { l -> l.all { it.isDigit() } && l.length <= 3 && l.toInt() <= 255 }
            val expected = if (isIpv4) HttpRequestHost.Result.NoHost
                           else HttpRequestHost.Result.Host(name)
            assertEquals(req, expected, r)
        }
    }
}
