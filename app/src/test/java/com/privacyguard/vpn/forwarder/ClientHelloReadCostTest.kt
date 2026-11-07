package com.privacyguard.vpn.forwarder

import com.privacyguard.core.tls.ClientHelloAssembler
import com.privacyguard.core.tls.ClientHelloParser
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What reading the ClientHello costs per new TLS connection, before the SNI decision
 * ([SniCheckCostTest] measures that). Runs once per connection, never per packet.
 *
 * The hellos are sized like Chrome's (~1.8 KB with the post-quantum key share), in one
 * TLS record as nearly every client sends them. The bound is loose on purpose; the
 * measured numbers are printed for the report.
 */
class ClientHelloReadCostTest {

    private val enforcer = EncryptionEnforcer()

    /** The forwarder's read of a ClientHello as of 1.1 (classification, then the parser twice). */
    private fun readAsIn11(record: ByteArray): String? {
        val result = enforcer.inspect(443, record)
        val sni = result.sniHostname ?: ClientHelloParser.parse(record)?.sni
        val hello = ClientHelloParser.parse(record)
        return if (hello?.sniRejected == true) null else sni
    }

    /** The read as of 1.2: reassembled first (fail closed), then classified and parsed once. */
    private fun readAsIn12(record: ByteArray): String? {
        val assembled = ClientHelloAssembler.assemble(record) as? ClientHelloAssembler.Complete ?: return null
        val hello = ClientHelloParser.parse(assembled.record) ?: return null
        if (hello.sniRejected) return null
        enforcer.inspect(443, assembled.record)
        return hello.sni
    }

    @Test
    fun reading_a_client_hello_costs_microseconds_per_connection() {
        val rnd = Random(7)
        fun word() = buildString { repeat(rnd.nextInt(4, 11)) { append('a' + rnd.nextInt(26)) } }
        val hellos = List(20_000) { chromeLikeHello("${word()}.${word()}.com", rnd) }

        repeat(5) { for (h in hellos) { readAsIn11(h); readAsIn12(h) } }   // warm up the JIT
        val before = medianNs(hellos) { readAsIn11(it) }
        val after = medianNs(hellos) { readAsIn12(it) }
        println("ClientHello read: 1.1 path median $before ns, 1.2 path median $after ns per connection, " +
            "${hellos[0].size} bytes each")
        assertTrue("ClientHello read took $after ns", after < 50_000)
    }

    private fun medianNs(hellos: List<ByteArray>, read: (ByteArray) -> String?): Long {
        val rounds = 7
        val perCall = LongArray(rounds) {
            val start = System.nanoTime()
            var named = 0
            for (h in hellos) if (read(h) != null) named++
            assertTrue("names were read", named == hellos.size)
            (System.nanoTime() - start) / hellos.size
        }.sorted()
        return perCall[rounds / 2]
    }

    /** One TLS record: a ClientHello with the usual extensions and a 1.2 KB key share. */
    private fun chromeLikeHello(name: String, rnd: Random): ByteArray {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        fun ext(type: Int, body: ByteArray) = u16(type) + u16(body.size) + body
        val n = name.toByteArray()
        val exts = ext(0x0a0a, ByteArray(0)) +                                   // GREASE
            ext(0x0000, u16(n.size + 3) + byteArrayOf(0) + u16(n.size) + n) +
            ext(0x0017, ByteArray(0)) +
            ext(0x000a, u16(8) + u16(0x11ec) + u16(0x001d) + u16(0x0017) + u16(0x0018)) +
            ext(0x000b, byteArrayOf(1, 0)) +
            ext(0x000d, u16(8) + u16(0x0403) + u16(0x0804) + u16(0x0401) + u16(0x0503)) +
            ext(0x0010, u16(12) + byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()) +
            ext(0x002b, byteArrayOf(4) + u16(0x0304) + u16(0x0303)) +
            ext(0x0033, u16(1_220) + u16(0x11ec) + u16(1_216) + rnd.nextBytes(1_216)) +
            ext(0x0015, ByteArray(200))
        val ciphers = listOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8)
        val body = u16(0x0303) + rnd.nextBytes(32) + byteArrayOf(32) + rnd.nextBytes(32) +
            u16(ciphers.size * 2) + ciphers.fold(ByteArray(0)) { a, c -> a + u16(c) } +
            byteArrayOf(1, 0) + u16(exts.size) + exts
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        return byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs
    }
}
