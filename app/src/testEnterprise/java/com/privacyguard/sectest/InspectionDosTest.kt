package com.privacyguard.sectest

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.fuzz.Fuzz
import com.privacyguard.vpn.mitm.PayloadParser
import com.privacyguard.vpn.mitm.PiiRedactor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Security test pass (2026-10-14). Decrypted bodies are attacker-controlled (any
 * app, or any server an app talks to). MitmTlsInterception processes every message
 * on ONE capture worker, and redactForExport always runs PiiRedactor on the body,
 * so a slow regex stalls inspection for every app.
 */
class InspectionDosTest {

    private val session = Session(SessionKey.of("10.0.0.2", 40000, "93.184.216.34", 443, IpPacket.PROTO_TCP), ownerPackage = "com.example")

    private fun timed(label: String, limitMs: Long, block: () -> Unit): Long {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val t0 = System.nanoTime()
        val f = ex.submit(block)
        return try {
            f.get(limitMs, TimeUnit.MILLISECONDS)
            ((System.nanoTime() - t0) / 1_000_000).also { println("$label: $it ms") }
        } catch (e: TimeoutException) {
            f.cancel(true)
            println("$label: still running after $limitMs ms (abandoned)")
            Long.MAX_VALUE
        } finally { ex.shutdownNow() }
    }

    @Test
    fun piiRedactorIsLinearOnHostileBodies() {
        val r = PiiRedactor()
        val size = 64 * 1024   // PayloadParser's MAX_BODY_SIZE; MITM messages go up to 2 MB
        val cases = mapOf(
            "benign text" to "hello world ".repeat(size / 12),
            "email: 'a.' repeated" to "a.".repeat(size / 2),
            "email: 'a@a.' no TLD" to ("a@" + "a.".repeat(size / 2)),
            "card: '1-' repeated" to "1-".repeat(size / 2) + "x",
            "card: '1 ' + dashes" to ("1" + " -".repeat(40)).repeat(size / 81),
            "phone: digit runs" to "1234567 ".repeat(size / 8),
        )
        val slow = mutableListOf<String>()
        for ((name, body) in cases) {
            val ms = timed("PiiRedactor $name (${body.length} chars)", 20_000) { r.redact(body) }
            if (ms > 2_000) slow += "$name=${if (ms == Long.MAX_VALUE) ">20s" else "${ms}ms"}"
        }
        assertTrue("ReDoS in PiiRedactor on 64 KB bodies: $slow", slow.isEmpty())
    }

    @Test
    fun payloadParserFuzz() {
        val seeds = listOf(
            "POST /api HTTP/1.1\r\nHost: a.example\r\nContent-Type: application/json\r\nContent-Length: 30\r\n\r\n{\"password\":\"x\",\"email\":\"a@b.c\"}".toByteArray(),
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n\r\n5\r\nhello\r\n0\r\n\r\n".toByteArray(),
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray() + ByteArray(9).also { it[3] = 4 },
        )
        val parser = PayloadParser(PiiRedactor())
        Fuzz.run("PayloadParser", seeds, iterations = 10_000) { raw ->
            val p = parser.parse(raw, "OUTBOUND", session)
            parser.redactForExport(p)
        }
    }
}
