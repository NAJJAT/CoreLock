package com.privacyguard.vpn.tunnel

import android.util.Log
import com.privacyguard.app.core.pcap.PcapWriter
import com.privacyguard.core.packet.IpPacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TunReader(
    private val tunInterface: TunInterface,
) : Runnable {

    fun interface PacketHandler {
        fun onPacket(packet: IpPacket)
    }

    fun interface Ipv6PacketHandler {
        fun onIpv6Packet(raw: ByteArray)
    }

    private val handlers = mutableListOf<PacketHandler>()
    private val ipv6Handlers = mutableListOf<Ipv6PacketHandler>()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    fun addHandler(handler: PacketHandler) { handlers.add(handler) }
    fun removeHandler(handler: PacketHandler) { handlers.remove(handler) }
    fun addIpv6Handler(handler: Ipv6PacketHandler) { ipv6Handlers.add(handler) }
    fun removeIpv6Handler(handler: Ipv6PacketHandler) { ipv6Handlers.remove(handler) }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tun-reader").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        tunInterface.close()
        thread?.interrupt()
    }

    fun join(timeoutMs: Long = 2_000): Boolean {
        val t = thread ?: return true
        t.join(timeoutMs)
        return !t.isAlive
    }

    companion object {
        private const val TAG = "TunReader"
        private const val POLL_TIMEOUT_MS = 500
    }

    /** False when interrupted (stop() was called). */
    private fun sleepQuietly(): Boolean = try {
        Thread.sleep(5)
        true
    } catch (_: InterruptedException) {
        false
    }

    val totalBytesRead = AtomicLong(0)
    val totalPacketsRead = AtomicLong(0)
    val droppedShort = AtomicLong(0)
    val droppedUnsupported = AtomicLong(0)
    val droppedMalformed = AtomicLong(0)
    val droppedFragments = AtomicLong(0)
    val handlerExceptions = AtomicLong(0)
    val emptyReads = AtomicLong(0)

    data class Stats(
        val bytesRead: Long,
        val packetsRead: Long,
        val droppedShort: Long,
        val droppedUnsupported: Long,
        val droppedMalformed: Long,
        val handlerExceptions: Long,
        val isRunning: Boolean,
    )

    fun stats(): Stats = Stats(
        bytesRead = totalBytesRead.get(),
        packetsRead = totalPacketsRead.get(),
        droppedShort = droppedShort.get(),
        droppedUnsupported = droppedUnsupported.get(),
        droppedMalformed = droppedMalformed.get(),
        handlerExceptions = handlerExceptions.get(),
        isRunning = running.get(),
    )

    override fun run() {
        Log.i(TAG, "TunReader started")

        // Single allocation for the entire session lifetime — reused every packet.
        val buf = ByteArray(tunInterface.mtu)

        while (running.get() && tunInterface.isOpen) {
            // Sleeps in poll() until a packet arrives; the timeout only bounds how
            // long stop() takes to be noticed. (A 5 ms sleep loop here woke the CPU
            // ~200 times a second for as long as the VPN was on.)
            val poll = tunInterface.awaitReadable(POLL_TIMEOUT_MS)
            if (!running.get() || !tunInterface.isOpen) break
            if (poll == TunInterface.POLL_TIMEOUT) continue
            // poll() failed: fall back to sleep-and-read so it cannot spin.
            if (poll == TunInterface.POLL_ERROR && !sleepQuietly()) break

            val n = tunInterface.readInto(buf)

            if (n <= 0) {
                if (!running.get() || !tunInterface.isOpen) break
                emptyReads.incrementAndGet()
                // An error, or "readable" with nothing to read: back off briefly.
                if (!sleepQuietly()) break
                continue
            }

            totalBytesRead.addAndGet(n.toLong())

            if (n < TunInterface.MIN_PACKET_SIZE) {
                droppedShort.incrementAndGet()
                continue
            }

            // IpPacket.parse() copies the bytes it needs — safe to reuse buf after this call.
            val raw = buf.copyOf(n)

            if (PcapWriter.isCapturing()) PcapWriter.write(raw)

            val version = (buf[0].toInt() ushr 4) and 0xF
            when (version) {
                4 -> {
                    val packet = IpPacket.parse(raw)
                    if (packet == null) { droppedMalformed.incrementAndGet(); continue }
                    // Fragments are dropped, never parsed: a later fragment has no
                    // TCP/UDP header, and fragmentation is a classic way past port,
                    // SNI and DNS filters. The local stack honours PMTU, so apps
                    // recover by sending smaller packets.
                    if (!packet.isUnfragmented) { droppedFragments.incrementAndGet(); continue }
                    totalPacketsRead.incrementAndGet()
                    for (handler in handlers) {
                        try { handler.onPacket(packet) }
                        catch (e: Exception) {
                            Log.e(TAG, "handler error: ${e.javaClass.simpleName}")
                            handlerExceptions.incrementAndGet()
                        }
                    }
                }
                6 -> {
                    totalPacketsRead.incrementAndGet()
                    for (handler in ipv6Handlers) {
                        try { handler.onIpv6Packet(raw) }
                        catch (e: Exception) {
                            Log.e(TAG, "ipv6 handler error: ${e.javaClass.simpleName}")
                            handlerExceptions.incrementAndGet()
                        }
                    }
                }
                else -> droppedUnsupported.incrementAndGet()
            }
        }

        running.set(false)
        Log.i(TAG, "TunReader stopped — packets=${totalPacketsRead.get()} bytes=${totalBytesRead.get()}")
    }
}
