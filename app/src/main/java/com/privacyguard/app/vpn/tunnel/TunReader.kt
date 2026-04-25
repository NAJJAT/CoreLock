package com.privacyguard.vpn.tunnel

import com.privacyguard.app.core.pcap.PcapWriter
import com.privacyguard.core.packet.IpPacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Continuously reads raw IP packets from the [TunInterface] and dispatches
 * them to registered [PacketHandler] callbacks.
 *
 * TunReader runs on a dedicated thread (not the main thread) and loops until
 * [stop] is called or the TUN interface is closed.
 *
 * Design:
 *  - Single producer loop: avoids synchronization overhead on the hot path.
 *  - Parses each raw buffer into an [IpPacket] before dispatching.
 *  - Malformed / unsupported packets are counted and silently dropped.
 *  - Statistics are lock-free via [AtomicLong].
 *
 * Usage:
 * ```kotlin
 * val reader = TunReader(tunInterface)
 * reader.addHandler { ip -> forwarder.handle(ip) }
 * reader.start()
 * // … later …
 * reader.stop()
 * ```
 */
class TunReader(
    private val tunInterface: TunInterface,
) : Runnable {

    // ─────────────────────────────────────────────────────────────────────────
    // Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Callback invoked for every successfully parsed [IpPacket].
     * Implementations must be non-blocking; heavyweight work must be offloaded
     * to another thread/coroutine.
     */
    fun interface PacketHandler {
        fun onPacket(packet: IpPacket)
    }

    private val handlers = mutableListOf<PacketHandler>()

    fun addHandler(handler: PacketHandler)    { handlers.add(handler) }
    fun removeHandler(handler: PacketHandler) { handlers.remove(handler) }

    /** Callback invoked for raw IPv6 packet bytes (Phase 2: full proxying). */
    fun interface Ipv6PacketHandler {
        fun onIpv6Packet(raw: ByteArray)
    }

    private val ipv6Handlers = mutableListOf<Ipv6PacketHandler>()

    fun addIpv6Handler(handler: Ipv6PacketHandler)    { ipv6Handlers.add(handler) }
    fun removeIpv6Handler(handler: Ipv6PacketHandler) { ipv6Handlers.remove(handler) }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private val running        = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    /**
     * Starts the reader on a new background thread named "tun-reader".
     * No-op if already running.
     */
    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tun-reader").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Signals the reader to stop. The background thread will exit after
     * finishing the current read (which may block until the next packet arrives).
     * Call [join] if you need to wait for full shutdown.
     */
    fun stop() {
        running.set(false)
        tunInterface.close()
        thread?.interrupt()
    }

    /**
     * Blocks until the reader thread has fully exited.
     * @param timeoutMs maximum wait time in milliseconds.
     * @return true if the thread exited cleanly within [timeoutMs].
     */
    fun join(timeoutMs: Long = 2_000): Boolean {
        val t = thread ?: return true
        t.join(timeoutMs)
        return !t.isAlive
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Loop
    // ─────────────────────────────────────────────────────────────────────────

    override fun run() {
        while (running.get() && tunInterface.isOpen) {
            val raw = tunInterface.readPacket()

            if (raw == null) {
                // Interface closed or error — exit the loop
                break
            }

            totalBytesRead.addAndGet(raw.size.toLong())

            if (raw.size < TunInterface.MIN_PACKET_SIZE) {
                droppedShort.incrementAndGet()
                continue
            }

            if (PcapWriter.isCapturing()) PcapWriter.write(raw)

            // Route IPv4 packets through the full pipeline.
            // IPv6 packets (version 6) are counted and dispatched to IPv6 handlers.
            val version = (raw[0].toInt() ushr 4) and 0xF
            when (version) {
                4 -> {
                    val packet = IpPacket.parse(raw)
                    if (packet == null) {
                        droppedMalformed.incrementAndGet()
                        continue
                    }
                    totalPacketsRead.incrementAndGet()
                    dispatch(packet)
                }
                6 -> {
                    // IPv6 — route is configured (addRoute("::/0")), so packets arrive here.
                    // Phase 1: DNS interception and hostname mapping (AAAA records) is handled
                    // in DnsHandler via the IPv4 DNS proxy path for now.
                    // Phase 2: Full IPv6 TCP/UDP session proxying (FR-VPN-16).
                    totalPacketsRead.incrementAndGet()
                    dispatchIpv6(raw)
                }
                else -> {
                    droppedUnsupported.incrementAndGet()
                }
            }
        }

        running.set(false)
    }

    private fun dispatch(packet: IpPacket) {
        for (handler in handlers) {
            try {
                handler.onPacket(packet)
            } catch (e: Exception) {
                System.err.println("[TunReader] handler exception: ${e.message}")
                handlerExceptions.incrementAndGet()
            }
        }
    }

    private fun dispatchIpv6(raw: ByteArray) {
        if (ipv6Handlers.isEmpty()) return
        for (handler in ipv6Handlers) {
            try {
                handler.onIpv6Packet(raw)
            } catch (e: Exception) {
                System.err.println("[TunReader] ipv6 handler exception: ${e.message}")
                handlerExceptions.incrementAndGet()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val totalBytesRead      = AtomicLong(0)
    val totalPacketsRead    = AtomicLong(0)
    val droppedShort        = AtomicLong(0)
    val droppedUnsupported  = AtomicLong(0)
    val droppedMalformed    = AtomicLong(0)
    val handlerExceptions   = AtomicLong(0)

    data class Stats(
        val bytesRead:        Long,
        val packetsRead:      Long,
        val droppedShort:     Long,
        val droppedUnsupported: Long,
        val droppedMalformed: Long,
        val handlerExceptions: Long,
        val isRunning:        Boolean,
    )

    fun stats(): Stats = Stats(
        bytesRead           = totalBytesRead.get(),
        packetsRead         = totalPacketsRead.get(),
        droppedShort        = droppedShort.get(),
        droppedUnsupported  = droppedUnsupported.get(),
        droppedMalformed    = droppedMalformed.get(),
        handlerExceptions   = handlerExceptions.get(),
        isRunning           = running.get(),
    )
}