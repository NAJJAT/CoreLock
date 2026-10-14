package com.privacyguard.core.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Regression for V-07 (QUIC part): the SNI of a QUIC connection is read from the
 * client's Initial packets, whose keys RFC 9001 derives from public values.
 */
class QuicInitialTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── Key derivation: published test vectors ───────────────────────────────

    @Test fun v1_client_keys_match_rfc9001_appendix_a1() {
        val k = QuicInitial.clientKeys(hex("8394c8f03e515708"), QuicInitial.VERSION_1)!!
        assertArrayEquals(hex("1f369613dd76d5467730efcbe3b1a22d"), k.key)
        assertArrayEquals(hex("fa044b2f42a3fd3b46fb255c"), k.iv)
        assertArrayEquals(hex("9f50449e04a0e810283a1e9933adedd2"), k.hp)
    }

    @Test fun v2_client_keys_match_rfc9369_appendix_a1() {
        val k = QuicInitial.clientKeys(hex("8394c8f03e515708"), QuicInitial.VERSION_2)!!
        assertArrayEquals(hex("8b1a0bc121284290a29e0971b5cd045d"), k.key)
        assertArrayEquals(hex("91f73e2351d8fa91660e909f"), k.iv)
        assertArrayEquals(hex("45b95e15235d6f45a6b19cbcb0294ba9"), k.hp)
    }

    // ── Packets: built the way a client protects them (RFC 9001 §5) ─────────

    private fun varint(v: Long): ByteArray = when {
        v < 64 -> byteArrayOf(v.toByte())
        v < 16384 -> byteArrayOf((0x40 or (v ushr 8).toInt()).toByte(), v.toByte())
        else -> byteArrayOf((0x80 or (v ushr 24).toInt()).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    private fun crypto(offset: Int, data: ByteArray) = byteArrayOf(0x06) + varint(offset.toLong()) + varint(data.size.toLong()) + data

    /** One protected client Initial carrying [frames], padded to 1200 bytes like real clients. */
    private fun initial(frames: ByteArray, pn: Int, version: Int = QuicInitial.VERSION_1): ByteArray {
        val dcid = hex("8394c8f03e515708")
        val keys = QuicInitial.clientKeys(dcid, version)!!
        val pnLen = 2
        val typeBits = if (version == QuicInitial.VERSION_2) 1 else 0
        val first = 0xC0 or (typeBits shl 4) or (pnLen - 1)
        val plain = frames + ByteArray(maxOf(0, 1100 - frames.size))      // PADDING
        val length = pnLen + plain.size + 16
        val header = ByteArrayOutputStream().apply {
            write(first)
            write(byteArrayOf((version ushr 24).toByte(), (version ushr 16).toByte(), (version ushr 8).toByte(), version.toByte()))
            write(dcid.size); write(dcid); write(0)                       // DCID, empty SCID
            write(varint(0))                                               // no token
            write(varint(length.toLong()))
            write(byteArrayOf((pn ushr 8).toByte(), pn.toByte()))
        }.toByteArray()
        val nonce = keys.iv.copyOf().also { it[11] = (it[11].toInt() xor (pn and 0xFF)).toByte(); it[10] = (it[10].toInt() xor (pn ushr 8 and 0xFF)).toByte() }
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(header)
        }.doFinal(plain)
        val packet = header + sealed
        val pnOffset = header.size - pnLen
        val mask = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES")) }
            .doFinal(packet.copyOfRange(pnOffset + 4, pnOffset + 20))
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        return packet
    }

    /** A ClientHello handshake message (no record header) with [sni] and [filler] bytes of extensions. */
    private fun clientHello(sni: String, filler: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val name = sni.toByteArray()
        val sniExt = u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val pad = if (filler > 0) u16(0x0015) + u16(filler) + ByteArray(filler) else ByteArray(0)
        val ext = sniExt + u16(0x002b) + u16(3) + byteArrayOf(2, 3, 4) + pad
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(ext.size) + ext
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    @Test fun reads_sni_from_a_single_initial() {
        val t = QuicSniTracker<String>()
        val r = t.offer("flow", initial(crypto(0, clientHello("ads.example.com")), pn = 0))
        assertEquals("ads.example.com", (r as QuicSniTracker.Result.Hello).hello.sni)
    }

    @Test fun reads_sni_from_quic_v2() {
        val r = QuicSniTracker<String>().offer("f", initial(crypto(0, clientHello("v2.example.com")), 0, QuicInitial.VERSION_2))
        assertEquals("v2.example.com", (r as QuicSniTracker.Result.Hello).hello.sni)
    }

    /** Large (post-quantum) ClientHello over two packets, CRYPTO frames out of order. */
    @Test fun reassembles_a_split_and_reordered_client_hello() {
        val ch = clientHello("tracker.example.net", filler = 1400)
        val a = ch.copyOfRange(0, 700); val b = ch.copyOfRange(700, 1000); val c = ch.copyOfRange(1000, ch.size)
        val t = QuicSniTracker<String>()
        assertEquals(QuicSniTracker.Result.Pending, t.offer("f", initial(crypto(700, b) + crypto(0, a), pn = 0)))
        val r = t.offer("f", initial(crypto(1000, c), pn = 1))
        assertEquals("tracker.example.net", (r as QuicSniTracker.Result.Hello).hello.sni)
    }

    @Test fun invalid_sni_is_rejected_not_returned() {
        val r = QuicSniTracker<String>().offer("f", initial(crypto(0, clientHello("evil‮example.com")), 0))
        val hello = (r as QuicSniTracker.Result.Hello).hello
        assertNull(hello.sni)
        assertTrue(hello.sniRejected)
    }

    @Test fun non_quic_and_damaged_packets_are_unreadable() {
        val t = QuicSniTracker<String>()
        assertEquals(QuicSniTracker.Result.Unreadable, t.offer("a", ByteArray(1200) { 7 }))
        val damaged = initial(crypto(0, clientHello("x.example.com")), 0).also { it[200] = (it[200].toInt() xor 1).toByte() }
        assertEquals(QuicSniTracker.Result.Unreadable, t.offer("b", damaged))
    }

    @Test fun random_datagrams_never_throw() {
        val rnd = kotlin.random.Random(9001)
        val seed = initial(crypto(0, clientHello("a.example.com")), 0)
        val t = QuicSniTracker<Int>()
        repeat(5_000) { i ->
            val d = if (i % 3 == 0) rnd.nextBytes(rnd.nextInt(0, 1500))
            else seed.copyOf().also { b -> repeat(rnd.nextInt(1, 4)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
            t.offer(i % 50, d)
        }
    }
}
