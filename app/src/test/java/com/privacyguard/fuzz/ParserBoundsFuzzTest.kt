package com.privacyguard.fuzz

import com.privacyguard.core.tls.ClientHelloParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property fuzzing for the parser fixes of the 1.3 bug-fix round: besides "never throws",
 * each target checks that what a parser returns is what a strict reader (a server, a
 * resolver) would read too. Fixed seeds on pull requests; the nightly job runs these 50x
 * longer with new seeds (its `*Fuzz*` filter picks this class up).
 */
class ParserBoundsFuzzTest {

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun hello(extensions: ByteArray): ByteArray {
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) +
            u16(extensions.size) + extensions
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        return byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs
    }

    private fun sniExt(name: String, extraLen: Int = 0): ByteArray {
        val n = name.toByteArray()
        return u16(0) + u16(n.size + 5 + extraLen) + u16(n.size + 3) + byteArrayOf(0) + u16(n.size) + n
    }

    /** Where the first SNI extension's data is, walked independently of the parser; null if none. */
    private fun sniExtensionSpan(d: ByteArray): IntRange? {
        fun u16At(i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
        var p = 5 + 4 + 2 + 32
        if (p >= d.size) return null
        p += 1 + (d[p].toInt() and 0xFF)
        if (p + 2 > d.size) return null
        p += 2 + u16At(p)
        if (p >= d.size) return null
        p += 1 + (d[p].toInt() and 0xFF)
        if (p + 2 > d.size) return null
        val extEnd = minOf(d.size, p + 2 + u16At(p)); p += 2
        while (p + 4 <= extEnd) {
            val type = u16At(p); val len = u16At(p + 2); p += 4
            if (type == 0) return p until minOf(p + len, extEnd)
            p += len
        }
        return null
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test fun sniNameStaysInsideItsExtension() {
        val seeds = listOf(
            hello(sniExt("news.example.org")),
            hello(sniExt("a.example.com") + u16(0x0010) + u16(5) + u16(3) + byteArrayOf(2) + "h2".toByteArray()),
            // The name length reaches into the next extension, whose header spells a name.
            hello(u16(0) + u16(5) + u16(0x13) + byteArrayOf(0) + u16(16) + "evil.example.com".toByteArray()),
        )
        Fuzz.run("ClientHello SNI bounds", seeds, rngSeed = 0x5A1) { data ->
            val sni = ClientHelloParser.parse(data)?.sni ?: return@run
            val span = sniExtensionSpan(data)
            assertTrue("a name came back but there is no SNI extension", span != null)
            val bytes = sni.toByteArray(Charsets.US_ASCII)
            val lower = data.copyOf().also { b -> for (i in b.indices) if (b[i] in 'A'.code.toByte()..'Z'.code.toByte()) b[i] = (b[i] + 32).toByte() }
            val at = lower.indexOf(bytes, span!!.first)
            assertTrue("name \"$sni\" lies outside its extension $span", at >= 0 && at + bytes.size - 1 <= span.last)
        }
    }

    @Test fun dnsNamesStayWithinRfcLimits() {
        val q = com.privacyguard.core.packet.DnsPacket.buildQuery("ads.example.com", 0x1234)
        val target = com.privacyguard.core.packet.DnsPacket.encodeName("trk.example.net")
        val answer = q.copyOf().also { it[2] = 0x81.toByte(); it[7] = 1 } +
            byteArrayOf(0xC0.toByte(), 0x0C) + u16(5) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(target.size) + target
        Fuzz.run("DNS name limits", listOf(q, answer), rngSeed = 0xD45) { data ->
            val p = com.privacyguard.core.packet.DnsPacket.parse(data) ?: return@run
            val names = p.questions.map { it.name } + p.answers.map { it.name } + p.cnameRecords + p.serviceTargets
            for (n in names) {
                // 255 wire bytes = at most 253 characters in dotted form.
                assertTrue("name of ${n.length} characters", n.length <= 253)
                assertTrue("label over 63 bytes in \"$n\"", n.split('.').all { it.length <= 63 })
            }
        }
    }
}
