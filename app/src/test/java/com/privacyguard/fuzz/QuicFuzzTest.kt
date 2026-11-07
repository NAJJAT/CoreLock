package com.privacyguard.fuzz

import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.core.tls.QuicInitial
import com.privacyguard.core.tls.QuicSniTracker
import com.privacyguard.core.tls.QuicTestPackets.clientHello
import com.privacyguard.core.tls.QuicTestPackets.crypto
import com.privacyguard.core.tls.QuicTestPackets.initial
import com.privacyguard.core.tls.QuicTestPackets.shortHeader
import com.privacyguard.core.tls.QuicTestPackets.splitHello
import com.privacyguard.vpn.firewall.QuicGate
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzz targets for the QUIC path (plan: every parser of untrusted data gets one).
 *
 * An Initial is encrypted (AEAD), so mutating the datagram mostly tests the
 * authentication check. These targets mutate the decrypted frames and encrypt them
 * again with the test helper, so the frame parser and the reassembly behind it get
 * hostile input too. Fixed seeds: a failure prints its input and can be replayed.
 */
class QuicFuzzTest {

    private val chSmall = clientHello("news.example.org")
    private val chLarge = clientHello("big.example.org", filler = 1400)

    @Test
    fun initial_header_layer() {
        val (a, b) = splitHello("split.example.org")
        val seeds = listOf(initial(crypto(0, chSmall), pn = 0), a, b, shortHeader(),
            initial(crypto(0, chSmall), pn = 0, version = QuicInitial.VERSION_2))
        Fuzz.run("QuicInitial datagram", seeds, iterations = 20_000) { d ->
            QuicInitial.isLongHeader(d)
            QuicInitial.cryptoFrames(d)
        }
    }

    @Test
    fun initial_frames_after_decryption() {
        val seeds = listOf(
            crypto(0, chSmall),
            crypto(0, chLarge.copyOfRange(0, 900)) + crypto(900, chLarge.copyOfRange(900, chLarge.size)),
            byteArrayOf(0x01, 0x00) + crypto(0, chSmall) + byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00),   // PING, ACK
        )
        Fuzz.run("QuicInitial frames", seeds, iterations = 8_000) { frames ->
            if (frames.size > 1_400) return@run                     // must fit one datagram
            val chunks = QuicInitial.cryptoFrames(initial(frames, pn = 0)) ?: return@run
            for (c in chunks) assertTrue("offset ${c.offset}", c.offset >= 0)
        }
    }

    /**
     * The CRYPTO reassembler on its own (QuicSniTracker): mutated frame sequences, cut
     * into up to four Initials of one flow and offered in order. No exception, and a
     * released hello never carries more datagrams than the tracker may hold.
     */
    @Test
    fun crypto_reassembly() {
        val seeds = listOf(
            crypto(0, chLarge.copyOfRange(0, 700)) + crypto(700, chLarge.copyOfRange(700, chLarge.size)),
            crypto(900, chLarge.copyOfRange(900, chLarge.size)) + crypto(0, chLarge.copyOfRange(0, 900)),
            crypto(0, chSmall) + crypto(0, chSmall),                                 // duplicate
            crypto(0, chLarge.copyOfRange(0, 500)) + crypto(400, chLarge.copyOfRange(400, chLarge.size)),  // overlap
        )
        Fuzz.run("QuicSniTracker reassembly", seeds, iterations = 3_000) { frames ->
            val tracker = QuicSniTracker<Int>(now = { 0L })
            val parts = 1 + (frames.firstOrNull()?.toInt()?.and(3) ?: 0)
            val size = (frames.size + parts - 1) / parts
            for (i in 0 until parts) {
                val piece = frames.copyOfRange(minOf(frames.size, i * size), minOf(frames.size, (i + 1) * size))
                if (piece.isEmpty() || piece.size > 1_400) continue
                val r = tracker.offer(7, initial(piece, pn = i), hold = true)
                if (r is QuicSniTracker.Result.Hello) assertTrue(r.held.size <= QuicSniTracker.MAX_DATAGRAMS)
            }
        }
    }

    @Test
    fun varints() {
        Fuzz.run("QuicInitial.readVarint", listOf(byteArrayOf(0x25), byteArrayOf(0x7b, 0xbd.toByte()), ByteArray(8) { 0xFF.toByte() })) { d ->
            for (p in 0..d.size) QuicInitial.readVarint(d, p)   // callers never pass a negative position
        }
    }

    /**
     * Sequences of hostile Initials over a few flows, through the gate at Standard:
     * crafted CRYPTO offsets and lengths, duplicates, gaps, overlaps, timeouts, flows
     * evicted. Invariant: a flow's released datagrams are its own, in the order they
     * were held, and nothing over the global limit is ever held.
     */
    @Test
    fun reassembly_and_holding_sequences() {
        val rng = Random(20261105 + Fuzz.seedOffset)
        var clock = 1_000_000L
        val gate = QuicGate<Int>(userChoice = { false }, level = { BlockLevel.STANDARD }, now = { clock })
        val tracker = QuicSniTracker<Int>(now = { clock })
        val held = HashMap<Int, MutableList<ByteArray>>()
        val open = HashMap<Int, Boolean>()
        var holds = 0; var drops = 0; var released = 0; var hellos = 0
        val pieces = (0 until chLarge.size step 300).map { it to chLarge.copyOfRange(it, minOf(chLarge.size, it + 300)) }

        fun hostileFrames(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            repeat(rng.nextInt(1, 4)) {
                val (off, data) = pieces.random(rng)
                val offset = when (rng.nextInt(6)) {
                    0 -> rng.nextInt(0, 20_000)                       // past the cap, or a gap
                    1 -> off + rng.nextInt(-50, 50).coerceAtLeast(-off)   // overlap
                    else -> off
                }
                val body = if (rng.nextInt(5) == 0) rng.nextBytes(data.size) else data
                out.write(crypto(offset, body))
            }
            return out.toByteArray()
        }

        repeat(30_000 * Fuzz.scale) { step ->
            val key = rng.nextInt(10)
            clock += if (rng.nextInt(50) == 0) QuicSniTracker.HOLD_TIMEOUT_MS + 1 else rng.nextLong(0, 200)
            val d = when (rng.nextInt(10)) {
                0 -> shortHeader(rng.nextInt(1, 1300))
                1 -> rng.nextBytes(rng.nextInt(0, 1300))
                else -> initial(hostileFrames(), pn = rng.nextInt(0, 1000))
            }
            try {
                tracker.offer(key, d, hold = rng.nextBoolean())
                // As the service calls it: onNewFlow until the gate forwards (the session
                // opens), then onOpenFlow with the session's flag; sessions end at random.
                if (rng.nextInt(40) == 0) open.remove(key)
                val openInspected = open[key]
                val v = if (openInspected != null) gate.onOpenFlow(key, d, openInspected) else gate.onNewFlow(key, d)
                when (v) {
                    QuicGate.Verdict.Hold -> { holds++; held.getOrPut(key) { mutableListOf() } += d }
                    QuicGate.Verdict.Drop -> { drops++; held.remove(key) }
                    is QuicGate.Verdict.Forward -> {
                        if (openInspected == null) open[key] = v.inspected
                        if (v.hello != null) hellos++
                        if (v.held.isNotEmpty()) released++
                        val mine = held.remove(key).orEmpty()
                        // Released datagrams: only this flow's, in order (the gate may have
                        // discarded older ones after a timeout or eviction, never added any).
                        var i = 0
                        for (r in v.held) {
                            while (i < mine.size && mine[i] !== r) i++
                            assertTrue("step $step (seed offset ${Fuzz.seedOffset}): flow $key released a datagram it never held", i < mine.size)
                            i++
                        }
                        assertTrue(v.held.sumOf { it.size } <= QuicSniTracker.MAX_HELD_BYTES)
                    }
                }
            } catch (e: AssertionError) {
                throw e
            } catch (t: Throwable) {
                throw AssertionError("step $step (seed offset ${Fuzz.seedOffset}) threw ${t::class.java.simpleName} for flow $key", t)
            }
        }
        println("QUIC sequences: $holds held, $drops dropped, $hellos hellos read, $released released held datagrams")
        // The target must reach the deep states, or it tests nothing.
        assertTrue(holds > 1_000 && drops > 100 && hellos > 50 && released > 20)
    }
}
