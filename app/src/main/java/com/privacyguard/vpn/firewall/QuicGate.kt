package com.privacyguard.vpn.firewall

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.tls.ClientHello
import com.privacyguard.core.tls.QuicInitial
import com.privacyguard.core.tls.QuicSniTracker
import java.util.concurrent.atomic.AtomicLong

/**
 * What happens to a UDP/443 (QUIC) datagram before it is forwarded. The SNI decision
 * itself stays with the caller (NameBlockPolicy): this only gets it a ClientHello.
 *
 *  - QUIC off ([dropsAllQuic]): every QUIC long-header packet (the Initial and the
 *    rest of the handshake) is dropped, so no QUIC connection can start, and
 *    QUIC-capable apps retry over TCP, where the TLS SNI is checked. Other UDP/443
 *    (STUN/TURN for calls, WireGuard, DTLS) passes: none of it has both top bits set.
 *    On when the user turned on "Block QUIC for better filtering", or by default at
 *    Strict, or when inspection needs it.
 *  - Standard and Strict ([failsClosed]): a new flow's datagrams are held until its
 *    ClientHello is complete, so nothing reaches the server unchecked. A QUIC Initial
 *    that cannot be read (unknown version, frame or damage) is dropped; the app then
 *    falls back to TCP. UDP/443 that is not QUIC (STUN/TURN, VPNs) is not affected.
 *  - Minimal: datagrams are forwarded while the ClientHello is reassembled, and
 *    unreadable QUIC is let through, as before.
 *
 * Holding is bounded per flow and in total (QuicSniTracker.MAX_HOLDING_FLOWS and
 * MAX_HELD_BYTES); past those, new flows are dropped at Standard/Strict and counted in
 * [holdLimitFlows]. Minimal never
 * holds, so it never hits them. Runs on the packet thread.
 */
class QuicGate<K : Any>(
    /** The user's explicit choice; null = the default for the protection level. */
    private val userChoice: () -> Boolean?,
    private val level: () -> FilterEngine.BlockLevel,
    /** TLS inspection drops QUIC so apps use TCP, where it can decrypt. */
    private val interceptionBlocksQuic: () -> Boolean = { false },
    /** Called once per flow dropped because its Initial could not be read. */
    private val onUnreadableFlow: () -> Unit = {},
    /** Called once per flow dropped because the global hold limits were reached. */
    private val onHoldLimitFlow: () -> Unit = {},
    /** Called once per QUIC flow dropped because QUIC is blocked ([dropsAllQuic]). */
    private val onQuicOffFlow: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Verdict {
        /** Drop the datagram (QUIC is off, or the flow's Initial could not be read). */
        data object Drop : Verdict
        /** Kept back until the ClientHello is complete; nothing of the flow was sent. */
        data object Hold : Verdict
        /**
         * Send [held] (earlier datagrams of the flow, in order), then this one. [hello]
         * is the completed ClientHello, for the SNI check. [inspected]: the flow needs
         * no further look (its ClientHello was seen, or it was given up on at Minimal).
         */
        data class Forward(val held: List<ByteArray>, val hello: ClientHello?, val inspected: Boolean) : Verdict
    }

    /** Flows dropped because their Initial could not be read. */
    val unreadableFlows = AtomicLong(0)

    /** Flows dropped because too many others were already being held. */
    val holdLimitFlows = AtomicLong(0)

    /** QUIC flows dropped because QUIC is blocked. Counted, never logged as rows: apps retry. */
    val quicOffFlows = AtomicLong(0)

    private val tracker = QuicSniTracker<K>(now)

    /** Unreadable flows already dropped, with the time: later datagrams are dropped without a new count. */
    private val dropped = LinkedHashMap<K, Long>()

    /**
     * Flows already counted in [holdLimitFlows], with the time. Count only: the tracker
     * forgets a flow it had no room for, so its retries are tried again (and may fit).
     */
    private val limitCounted = LinkedHashMap<K, Long>()

    /** Flows already counted in [quicOffFlows], with the time: their retries are not counted again. */
    private val offCounted = LinkedHashMap<K, Long>()

    fun dropsAllQuic(): Boolean =
        interceptionBlocksQuic() || (userChoice() ?: (level() == FilterEngine.BlockLevel.STRICT))

    fun failsClosed(): Boolean = level() >= FilterEngine.BlockLevel.STANDARD

    /**
     * True when the datagram is dropped whatever it holds (a QUIC handshake packet while
     * QUIC is off, or a flow already dropped as unreadable). Cheap: lets the caller skip
     * the owner lookup and rules.
     */
    fun dropsWithoutLooking(key: K, datagram: ByteArray): Boolean {
        if (dropsAsQuicOff(key, datagram)) return true
        return wasDropped(key)
    }

    /**
     * A datagram of a flow that has no session yet: nothing of it was forwarded.
     * [ownerUid] is kept with a held flow, so later datagrams can skip the lookup
     * ([pendingOwnerUid]).
     */
    fun onNewFlow(key: K, datagram: ByteArray, ownerUid: Int? = null): Verdict {
        if (dropsAsQuicOff(key, datagram)) { tracker.forget(key); return Verdict.Drop }
        if (wasDropped(key)) return Verdict.Drop
        val closed = failsClosed()
        val v = verdict(key, datagram, hold = closed, closed = closed)
        if (v === Verdict.Hold && ownerUid != null) tracker.setTag(key, ownerUid)
        return v
    }

    /** The owner UID stored for a flow that is being held, or null. */
    fun pendingOwnerUid(key: K): Int? = tracker.tagOf(key) as? Int

    /**
     * A datagram of an open session. [inspected] is the session's own flag; an open
     * flow that is not inspected yet started at Minimal or before a level change, so
     * its first datagrams were already sent and cannot be held.
     */
    fun onOpenFlow(key: K, datagram: ByteArray, inspected: Boolean): Verdict {
        if (dropsAsQuicOff(key, datagram)) return Verdict.Drop
        if (inspected) return FORWARD_INSPECTED
        if (wasDropped(key)) return Verdict.Drop
        return verdict(key, datagram, hold = false, closed = failsClosed())
    }

    private fun verdict(key: K, datagram: ByteArray, hold: Boolean, closed: Boolean): Verdict {
        // A flow already being reassembled is QUIC, whatever this datagram looks like.
        val started = tracker.isTracking(key)
        return when (val r = tracker.offer(key, datagram, hold)) {
            is QuicSniTracker.Result.Hello -> Verdict.Forward(r.held, r.hello, inspected = true)
            QuicSniTracker.Result.Pending -> if (hold) Verdict.Hold else FORWARD_PENDING
            // Global hold limits reached (only asked for when closed): drop rather than
            // send unchecked; the app retries, or falls back to TCP.
            QuicSniTracker.Result.Full -> { countHoldLimit(key); Verdict.Drop }
            // Given up (6 datagrams, no ClientHello) or not readable: at Standard/Strict
            // nothing of a QUIC flow is sent, held datagrams included (the tracker
            // discarded them).
            QuicSniTracker.Result.Unreadable ->
                if (closed && (started || QuicInitial.isLongHeader(datagram))) { rememberDropped(key); Verdict.Drop }
                else FORWARD_INSPECTED
        }
    }

    private fun wasDropped(key: K): Boolean = synchronized(dropped) {
        val at = dropped[key] ?: return false
        if (now() - at < DROPPED_MEMORY_MS) return true
        dropped.remove(key)
        false
    }

    private fun rememberDropped(key: K) {
        synchronized(dropped) {
            if (dropped.size >= MAX_DROPPED) dropped.remove(dropped.keys.first())
            dropped[key] = now()
        }
        unreadableFlows.incrementAndGet()
        runCatching { onUnreadableFlow() }
    }

    private fun countHoldLimit(key: K) {
        if (!firstInWindow(limitCounted, key)) return
        holdLimitFlows.incrementAndGet()
        runCatching { onHoldLimitFlow() }
    }

    /**
     * QUIC is off and [datagram] is a QUIC long-header packet: dropped, and the flow
     * counted once. Only long headers: without them no QUIC connection can start, and
     * UDP/443 that is not QUIC (STUN/TURN, WireGuard, DTLS) must keep working. Short
     * headers belong to connections opened before QUIC was turned off; they keep the
     * decision made when they opened (rules are still re-checked) and end on their own.
     */
    private fun dropsAsQuicOff(key: K, datagram: ByteArray): Boolean {
        if (!dropsAllQuic() || !QuicInitial.isLongHeader(datagram)) return false
        if (firstInWindow(offCounted, key)) {
            quicOffFlows.incrementAndGet()
            runCatching { onQuicOffFlow() }
        }
        return true
    }

    /** True unless [key] was already counted within [DROPPED_MEMORY_MS]; repeats do not extend that. */
    private fun firstInWindow(seen: LinkedHashMap<K, Long>, key: K): Boolean = synchronized(seen) {
        val at = seen[key]
        if (at != null && now() - at < DROPPED_MEMORY_MS) return false
        seen.remove(key)
        if (seen.size >= MAX_DROPPED) seen.remove(seen.keys.first())
        seen[key] = now()
        true
    }

    companion object {
        private const val DROPPED_MEMORY_MS = 120_000L
        private const val MAX_DROPPED = 4_096
        private val FORWARD_PENDING = Verdict.Forward(emptyList(), null, inspected = false)
        private val FORWARD_INSPECTED = Verdict.Forward(emptyList(), null, inspected = true)
    }
}
