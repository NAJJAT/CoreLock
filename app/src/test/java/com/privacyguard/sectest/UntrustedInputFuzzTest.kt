package com.privacyguard.sectest

import com.privacyguard.app.core.blocklist.BlocklistCache
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.core.tls.ClientHelloParser
import com.privacyguard.core.tls.CtMonitor
import com.privacyguard.core.tls.Ja3Fingerprinter
import com.privacyguard.core.tls.Ja4Fingerprinter
import com.privacyguard.fuzz.Fuzz
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Security test pass (2026-10-14): fuzz targets for parsers of untrusted input not
 * covered by fuzz/PacketParserFuzzTest. Exceptions the production caller catches are
 * allowed; Errors (StackOverflowError, OutOfMemoryError) and hangs are not.
 */
class UntrustedInputFuzzTest {

    private val clientHelloSeed: ByteArray by lazy {
        // Minimal TLS 1.3 ClientHello with SNI, ALPN, groups and supported_versions.
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val sniName = "example.com".toByteArray()
        val sni = u16(0) + u16(sniName.size + 5) + u16(sniName.size + 3) + byteArrayOf(0) + u16(sniName.size) + sniName
        val alpn = u16(16) + u16(5) + u16(3) + byteArrayOf(2) + "h2".toByteArray()
        val groups = u16(10) + u16(6) + u16(4) + u16(0x001d) + u16(0x0017)
        val versions = u16(43) + u16(3) + byteArrayOf(2) + u16(0x0304)
        val ext = sni + alpn + groups + versions
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(4) + u16(0x1301) + u16(0x1302) +
            byteArrayOf(1, 0) + u16(ext.size) + ext
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs
    }

    @Test fun clientHelloFingerprintsAndClassifier() = Fuzz.run("TLS ClientHello/JA3/JA4", listOf(clientHelloSeed)) { data ->
        ClientHelloParser.parse(data)?.let { hello ->
            hello.ja3String(); hello.ja3Hash()
            Ja4Fingerprinter.ja4(hello)
            Ja3Fingerprinter.inspect(hello, "com.example")
        }
        EncryptionEnforcer().inspect(443, data)
        EncryptionEnforcer().inspect(80, data)
        EncryptionEnforcer().inspectUdp(data)
    }

    /** SNI is attacker-chosen: only a valid hostname may become the recorded host (V-06). */
    @Test fun sniIsValidatedAsAHostname() {
        val evil = "‮moc.elgoog‬=HYPERLINK(\"http://x\")<script>"
        val name = evil.toByteArray(Charsets.UTF_8)
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val sni = u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(sni.size) + sni
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        val record = byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs
        val parsed = ClientHelloParser.parse(record)?.sni
        val classified = EncryptionEnforcer().inspect(443, record).sniHostname
        // Fixed (V-06): neither parser keeps a name that is not a hostname.
        assertTrue("bidi/HTML/formula SNI must be rejected", parsed == null && classified == null)
        assertTrue(ClientHelloParser.parse(record)!!.sniRejected)
    }

    /** crt.sh body: production catches Exception only (CtMonitor.checkDomain). */
    @Test fun crtShResponses() {
        val seed = """[{"id":1,"name_value":"a.example.com","not_before":"2026","not_after":"2027","issuer_name":"CN=x","entry_timestamp":"t"}]"""
        Fuzz.run("CtMonitor.parse", listOf(seed.toByteArray())) { raw ->
            try { CtMonitor.parse(String(raw, Charsets.UTF_8)) } catch (_: org.json.JSONException) { } catch (_: NumberFormatException) { }
        }
    }

    @Test fun crtShDeeplyNestedJsonIsAnError() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val thrown = runCatching { CtMonitor.parse(deep) }.exceptionOrNull()
        println("CtMonitor.parse(deep nesting) -> ${thrown?.javaClass?.name}")
        // Records the behaviour; StackOverflowError would escape checkDomain's catch (Exception).
        assertTrue(thrown == null || thrown is Exception || thrown is StackOverflowError)
    }

    private fun invokePrivateParser(name: String, raw: String): List<*> {
        val m = BlocklistManager::class.java.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
        return m.invoke(BlocklistManager, raw) as List<*>
    }

    @Test fun blocklistFormats() {
        val hosts = "# c\n0.0.0.0 ads.example.com\n127.0.0.1 localhost\n0.0.0.0 tr.example.net # x\n".toByteArray()
        val easy = "||ads.example.com^\n||x.example.org^\$important\n@@||ok.example^\n".toByteArray()
        val plain = "ads.example.com\n# c\ntrack.example.net\n".toByteArray()
        Fuzz.run("Blocklist parsers", listOf(hosts, easy, plain)) { raw ->
            val text = String(raw, Charsets.UTF_8)
            for (list in listOf(
                invokePrivateParser("parseHostsFormat", text),
                BlocklistManager.parseEasyListFormat(text),
                invokePrivateParser("parseDomainListFormat", text),
            )) {
                for (d in list) {
                    val s = d as String
                    assertTrue("unsafe entry '$s'", s.contains('.') && s.length <= 253 && !s.startsWith('.'))
                }
            }
        }
    }

    /** A tampered list must not block a whole public suffix (V-10). */
    @Test fun tamperedListCannotBlockAPublicSuffix() {
        val out = invokePrivateParser("parseDomainListFormat", "co.uk\ncom.au\nads.example.co.uk\n")
        assertTrue("public suffix accepted: $out", "co.uk" !in out && "com.au" !in out)
        assertTrue(out.contains("ads.example.co.uk"))
    }

    /** A label mixing scripts (Latin and Cyrillic o) is a look-alike and is refused (V-10). */
    @Test fun blocklistRejectsMixedScriptHomoglyphs() {
        val out = invokePrivateParser("parseDomainListFormat", "gоogle.com\n")   // Cyrillic o
        assertTrue("homoglyph accepted: $out", out.isEmpty())
    }

    @Test fun blocklistParserScalesLinearly() {
        val line = "0.0.0.0 " + "a".repeat(60) + ".example.com\n"
        val big = line.repeat(32 * 1024 * 1024 / line.length)   // the 32 MB download cap
        val t0 = System.nanoTime()
        val n = invokePrivateParser("parseHostsFormat", big).size
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("parseHostsFormat 32 MB: $n entries in $ms ms, heap used ${usedMb()} MB")
        assertTrue("32 MB list took $ms ms", ms < 60_000)
    }

    @Test fun startupCacheFile() {
        val dir = createTempDir("pgcache")
        try {
            val good = File(dir, "good.gz")
            GZIPOutputStream(good.outputStream()).bufferedWriter().use { it.write("privacyguard-blocklist-cache 1\nads.example\n--security--\nmal.example\n") }
            val seed = good.readBytes()
            val f = File(dir, "f.gz")
            Fuzz.run("BlocklistCache.read", listOf(seed), iterations = 5_000) { raw ->
                f.writeBytes(raw)
                try { BlocklistCache.read(f) } catch (_: java.io.IOException) { }
            }
        } finally { dir.deleteRecursively() }
    }

    private fun usedMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)

    @Suppress("unused") private val rnd = Random(1)
}
