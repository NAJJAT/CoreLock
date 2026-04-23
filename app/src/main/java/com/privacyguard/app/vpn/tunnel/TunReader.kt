package com.privacyguard.vpn.tunnel

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

            // Only handle IPv4 for now (version field = high nibble of byte 0)
            val version = (raw[0].toInt() ushr 4) and 0xF
            if (version != 4) {
                droppedUnsupported.incrementAndGet()
                continue
            }

            val packet = IpPacket.parse(raw)
            if (packet == null) {
                droppedMalformed.incrementAndGet()
                continue
            }

            totalPacketsRead.incrementAndGet()
            dispatch(packet)
        }

        running.set(false)
    }

    private fun dispatch(packet: IpPacket) {
        for (handler in handlers) {
            try {
                handler.onPacket(packet)
            } catch (e: Exception) {
                // A misbehaving handler must not crash the reader loop
                System.err.println("[TunReader] handler exception: ${e.message}")
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