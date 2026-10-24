package com.privacyguard.vpn.firewall

import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.core.tls.QuicInitial
import com.privacyguard.core.tls.QuicSniTracker
import com.privacyguard.core.tls.QuicTestPackets.clientHello
import com.privacyguard.core.tls.QuicTestPackets.crypto
import com.privacyguard.core.tls.QuicTestPackets.initial
import com.privacyguard.core.tls.QuicTestPackets.splitHello
import com.privacyguard.vpn.firewall.QuicGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicGateTest {

    private var clock = 1_000_000L
    private var unreadableCallbacks = 0
    private var holdLimitCallbacks = 0
    private var quicOffCallbacks = 0

    private fun gate(level: BlockLevel, choice: Boolean? = null, interception: Boolean = false) = QuicGate<String>(
        userChoice = { choice }, level = { level }, interceptionBlocksQuic = { interception },
        onUnreadableFlow = { unreadableCallbacks++ }, onHoldLimitFlow = { holdLimitCallbacks++ },
        onQuicOffFlow = { quicOffCallbacks++ }, now = { clock },
    )

    private val hello = initial(crypto(0, clientHello("news.example.org")), pn = 0)

    /** An Initial of a QUIC version this build cannot decrypt (draft-29). */
    private val unknownVersion = initial(crypto(0, clientHello("x.example.com")), pn = 0).also {
        it[1] = 0xFF.toByte(); it[2] = 0; it[3] = 0; it[4] = 0x1D
    }

    /** A readable Initial whose first frame is a type the parser does not know. */
    private val unknownFrame = initial(byteArrayOf(0x30) + crypto(0, clientHello("x.example.com")), pn = 0)

    private val stun = byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xA4.toByte(), 0x42) + ByteArray(12)

    private fun Verdict.forwardedHelloSni() = (this as Verdict.Forward).hello?.sni

    // ── The setting: UDP/443 dropped when on, passed when off ────────────────

    @Test
    fun quic_is_dropped_when_the_setting_is_on() {
        for (level in BlockLevel.entries) {
            val g = gate(level, choice = true)
            assertTrue(g.dropsAllQuic())
            assertSame("$level", Verdict.Drop, g.onNewFlow("f", hello))
            assertSame("$level open flow", Verdict.Drop, g.onOpenFlow("f", hello, inspected = true))
            assertTrue(g.dropsWithoutLooking("f", hello))
        }
    }

    @Test
    fun quic_passes_when_the_setting_is_off() {
        for (level in BlockLevel.entries) {
            val g = gate(level, choice = false)
            assertFalse(g.dropsAllQuic())
            assertEquals("$level", "news.example.org", g.onNewFlow("f", hello).forwardedHelloSni())
        }
    }

    @Test
    fun default_is_on_only_at_strict() {
        assertTrue(gate(BlockLevel.STRICT).dropsAllQuic())
        assertFalse(gate(BlockLevel.STANDARD).dropsAllQuic())
        assertFalse(gate(BlockLevel.MINIMAL).dropsAllQuic())
    }

    @Test
    fun inspection_drops_quic_whatever_the_setting() {
        assertTrue(gate(BlockLevel.MINIMAL, choice = false, interception = true).dropsAllQuic())
    }

    // ── Holding until the ClientHello is complete ───────────────────────────

    @Test
    fun standard_holds_a_split_hello_and_releases_it_whole() {
        val (first, second) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.STANDARD)
        assertSame("nothing reaches the server yet", Verdict.Hold, g.onNewFlow("f", first))
        val v = g.onNewFlow("f", second) as Verdict.Forward
        assertEquals("tracker.example.net", v.hello?.sni)
        assertEquals(1, v.held.size)
        assertSame(first, v.held[0])
        assertTrue(v.inspected)
    }

    @Test
    fun minimal_forwards_while_reassembling_as_before() {
        val (first, second) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.MINIMAL)
        val v1 = g.onNewFlow("f", first) as Verdict.Forward
        assertNull(v1.hello); assertFalse(v1.inspected); assertTrue(v1.held.isEmpty())
        val v2 = g.onNewFlow("f", second) as Verdict.Forward
        assertEquals("tracker.example.net", v2.hello?.sni)
        assertTrue(v2.held.isEmpty())
    }

    // ── Hold limits ─────────────────────────────────────────────────────────

    /** Holds the first half of a ClientHello for every slot there is. */
    private fun QuicGate<String>.fillHoldSlots(first: ByteArray) =
        repeat(QuicSniTracker.MAX_HOLDING_FLOWS) { assertSame("slot $it", Verdict.Hold, onNewFlow("h$it", first)) }

    @Test
    fun flow_past_the_hold_limit_is_dropped_and_counted_once() {
        val (first, _) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.STANDARD)
        g.fillHoldSlots(first)
        assertSame(Verdict.Drop, g.onNewFlow("x", first))
        assertSame("its retry is dropped too", Verdict.Drop, g.onNewFlow("x", first))
        assertEquals("one flow, however many datagrams", 1L, g.holdLimitFlows.get())
        assertEquals(1, holdLimitCallbacks)
        assertSame(Verdict.Drop, g.onNewFlow("y", first))
        assertEquals(2L, g.holdLimitFlows.get())
        assertEquals("not counted as unreadable", 0L, g.unreadableFlows.get())
    }

    @Test
    fun abandoned_holds_free_their_slots_after_the_timeout() {
        val (first, _) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.STRICT, choice = false)
        g.fillHoldSlots(first)
        clock += QuicSniTracker.HOLD_TIMEOUT_MS + 1
        assertSame(Verdict.Hold, g.onNewFlow("x", first))
        assertEquals(0L, g.holdLimitFlows.get())
    }

    @Test
    fun minimal_never_reaches_the_hold_limit() {
        val (first, _) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.MINIMAL)
        repeat(QuicSniTracker.MAX_HOLDING_FLOWS + 1) { assertTrue(g.onNewFlow("h$it", first) is Verdict.Forward) }
        assertEquals(0L, g.holdLimitFlows.get())
    }

    // ── Unreadable QUIC: dropped at Standard and Strict, counted per flow ─────

    @Test
    fun unreadable_initial_is_dropped_at_standard_and_strict() {
        for (level in listOf(BlockLevel.STANDARD, BlockLevel.STRICT)) {
            unreadableCallbacks = 0
            val g = gate(level, choice = false)
            assertSame("$level", Verdict.Drop, g.onNewFlow("f", unknownVersion))
            assertEquals(1L, g.unreadableFlows.get())
            assertEquals(1, unreadableCallbacks)
        }
    }

    @Test
    fun unreadable_initial_passes_at_minimal() {
        val g = gate(BlockLevel.MINIMAL)
        val v = g.onNewFlow("f", unknownVersion) as Verdict.Forward
        assertTrue("no further look", v.inspected)
        assertEquals(0L, g.unreadableFlows.get())
    }

    @Test
    fun unknown_frame_type_is_held_then_dropped() {
        val g = gate(BlockLevel.STANDARD)
        repeat(QuicSniTracker.MAX_DATAGRAMS - 1) { assertSame(Verdict.Hold, g.onNewFlow("f", unknownFrame)) }
        assertSame(Verdict.Drop, g.onNewFlow("f", unknownFrame))
        assertEquals(1L, g.unreadableFlows.get())
    }

    @Test
    fun a_dropped_flow_stays_dropped_and_is_counted_once() {
        val g = gate(BlockLevel.STANDARD)
        g.onNewFlow("f", unknownVersion)
        assertTrue(g.dropsWithoutLooking("f", hello))
        repeat(5) { assertSame(Verdict.Drop, g.onNewFlow("f", hello)) }
        assertEquals(1L, g.unreadableFlows.get())
        assertEquals(1, unreadableCallbacks)
        assertFalse("other flows unaffected", g.dropsWithoutLooking("g", hello))

        clock += 120_000L
        assertFalse("forgotten after two minutes", g.dropsWithoutLooking("f", hello))
        assertEquals("news.example.org", g.onNewFlow("f", hello).forwardedHelloSni())
    }

    @Test
    fun udp_on_443_that_is_not_quic_is_not_affected() {
        val g = gate(BlockLevel.STRICT, choice = false)
        val v = g.onNewFlow("f", stun) as Verdict.Forward
        assertTrue(v.inspected)
        assertFalse(QuicInitial.isLongHeader(stun))
        assertEquals(0L, g.unreadableFlows.get())
    }

    // ── QUIC off: counted once per flow, never per datagram ─────────────────

    @Test
    fun quic_off_drops_are_counted_once_per_flow() {
        val g = gate(BlockLevel.STANDARD, choice = true)
        repeat(5) { assertTrue(g.dropsWithoutLooking("f", hello)) }
        assertSame(Verdict.Drop, g.onNewFlow("f", hello))
        assertSame(Verdict.Drop, g.onOpenFlow("f", hello, inspected = true))
        assertEquals("retries of one flow count once", 1L, g.quicOffFlows.get())
        g.dropsWithoutLooking("g", hello)
        assertEquals("a new flow (new source port) counts again", 2L, g.quicOffFlows.get())
        assertEquals(2, quicOffCallbacks)

        clock += 120_000L
        g.dropsWithoutLooking("f", hello)
        assertEquals("counted again after two minutes", 3L, g.quicOffFlows.get())
    }

    @Test
    fun quic_off_counts_only_quic_long_headers() {
        val g = gate(BlockLevel.STRICT)
        assertTrue(g.dropsAllQuic())
        val shortHeader = byteArrayOf(0x40) + ByteArray(30)
        assertTrue(g.dropsWithoutLooking("stun", stun))
        assertTrue(g.dropsWithoutLooking("old", shortHeader))
        assertEquals(0L, g.quicOffFlows.get())
        assertEquals("not unreadable either", 0L, g.unreadableFlows.get())
    }

    @Test
    fun quic_off_counter_stays_zero_while_quic_is_allowed() {
        val g = gate(BlockLevel.STANDARD, choice = false)
        g.onNewFlow("f", hello)
        g.dropsWithoutLooking("f", hello)
        assertEquals(0L, g.quicOffFlows.get())
    }

    // ── Open flows (started at Minimal or before a level change) ─────────────

    @Test
    fun open_flows_already_inspected_are_not_looked_at_again() {
        val v = gate(BlockLevel.STANDARD).onOpenFlow("f", unknownVersion, inspected = true) as Verdict.Forward
        assertTrue(v.inspected)
        assertNull(v.hello)
    }

    @Test
    fun open_uninspected_flow_with_unreadable_quic_is_dropped_at_standard_only() {
        assertSame(Verdict.Drop, gate(BlockLevel.STANDARD).onOpenFlow("f", unknownVersion, inspected = false))
        assertTrue((gate(BlockLevel.MINIMAL).onOpenFlow("f", unknownVersion, inspected = false) as Verdict.Forward).inspected)
    }

    @Test
    fun open_flows_are_never_held() {
        val (first, second) = splitHello("tracker.example.net")
        val g = gate(BlockLevel.STANDARD)
        val v1 = g.onOpenFlow("f", first, inspected = false) as Verdict.Forward
        assertFalse(v1.inspected)
        assertEquals("tracker.example.net", g.onOpenFlow("f", second, inspected = false).forwardedHelloSni())
    }
}
