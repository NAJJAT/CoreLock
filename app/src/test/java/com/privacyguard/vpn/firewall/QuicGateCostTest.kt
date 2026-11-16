package com.privacyguard.vpn.firewall

import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.core.tls.QuicTestPackets.clientHello
import com.privacyguard.core.tls.QuicTestPackets.crypto
import com.privacyguard.core.tls.QuicTestPackets.initial
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the QUIC gate costs per new flow: decrypting the Initial, reassembling its
 * CRYPTO frames and reading the ClientHello (CLAUDE.md §6.2, reference ≈ 9.3 µs on the
 * emulator's release build). Runs once per new QUIC flow, never per packet of an open
 * one. The bound is loose on purpose, to catch a regression without failing on a busy
 * CI machine; the measured numbers are printed.
 */
class QuicGateCostTest {

    @Test
    fun quic_gate_costs_microseconds_per_new_flow() {
        val hellos = List(64) { initial(crypto(0, clientHello("host$it.example.org")), pn = 0) }
        val gate = QuicGate<Int>(
            userChoice = { false }, level = { BlockLevel.STANDARD }, interceptionBlocksQuic = { false },
            onUnreadableFlow = {}, onHoldLimitFlow = {}, onQuicOffFlow = {}, now = { 1_000L },
        )
        var key = 0
        repeat(20_000) { gate.onNewFlow(key++, hellos[it % hellos.size]) }   // warm up the JIT

        val flows = 5_000
        val rounds = 7
        val perFlowNs = LongArray(rounds) {
            val start = System.nanoTime()
            var forwarded = 0
            repeat(flows) { i ->
                if (gate.onNewFlow(key++, hellos[i % hellos.size]) is QuicGate.Verdict.Forward) forwarded++
            }
            assertTrue("complete hellos are forwarded", forwarded == flows)
            (System.nanoTime() - start) / flows
        }.sorted()
        val median = perFlowNs[rounds / 2]
        println("QUIC gate: median $median ns per new flow (min ${perFlowNs.first()}, max ${perFlowNs.last()})")
        assertTrue("QUIC gate took $median ns per new flow", median < 200_000)
    }
}
