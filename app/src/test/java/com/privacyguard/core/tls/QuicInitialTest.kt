package com.privacyguard.core.tls

import com.privacyguard.core.tls.QuicTestPackets.clientHello
import com.privacyguard.core.tls.QuicTestPackets.crypto
import com.privacyguard.core.tls.QuicTestPackets.hex
import com.privacyguard.core.tls.QuicTestPackets.initial
import com.privacyguard.core.tls.QuicTestPackets.shortHeader
import com.privacyguard.core.tls.QuicTestPackets.splitHello
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for V-07 (QUIC part): the SNI of a QUIC connection is read from the
 * client's Initial packets, whose keys RFC 9001 derives from public values.
 */
class QuicInitialTest {

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

    // ── Holding and giving up ────────────────────────────────────────────────

    @Test fun held_datagrams_come_back_with_the_hello_in_order() {
        val (first, second) = splitHello("tracker.example.net")
        val t = QuicSniTracker<String>()
        assertEquals(QuicSniTracker.Result.Pending, t.offer("f", first, hold = true))
        val r = t.offer("f", second, hold = true) as QuicSniTracker.Result.Hello
        assertEquals("tracker.example.net", r.hello.sni)
        assertEquals(1, r.held.size)
        assertSame("the datagram itself, not a copy", first, r.held[0])
    }

    @Test fun without_hold_nothing_is_kept() {
        val (first, second) = splitHello("tracker.example.net")
        val t = QuicSniTracker<String>()
        t.offer("f", first)
        assertTrue((t.offer("f", second) as QuicSniTracker.Result.Hello).held.isEmpty())
    }

    /** Before, non-Initial datagrams of a started flow were never counted: it stayed pending (unchecked) for good. */
    @Test fun a_started_flow_that_never_completes_is_given_up() {
        val (first, _) = splitHello("tracker.example.net")
        val t = QuicSniTracker<String>()
        assertEquals(QuicSniTracker.Result.Pending, t.offer("f", first))
        repeat(QuicSniTracker.MAX_DATAGRAMS - 2) { assertEquals(QuicSniTracker.Result.Pending, t.offer("f", shortHeader())) }
        assertEquals(QuicSniTracker.Result.Unreadable, t.offer("f", shortHeader()))
    }

    @Test fun long_header_check_tells_quic_from_other_udp_on_443() {
        assertTrue(QuicInitial.isLongHeader(initial(crypto(0, clientHello("a.example.com")), 0)))
        assertFalse("STUN", QuicInitial.isLongHeader(byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xA4.toByte(), 0x42)))
        assertFalse("DTLS", QuicInitial.isLongHeader(byteArrayOf(0x16, 0xFE.toByte(), 0xFD.toByte(), 0, 0, 0, 0, 0)))
        assertFalse("short header / TURN ChannelData", QuicInitial.isLongHeader(shortHeader()))
        assertFalse("too short", QuicInitial.isLongHeader(byteArrayOf(0xC0.toByte(), 0, 0)))
    }
}
