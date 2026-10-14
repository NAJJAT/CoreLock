package com.privacyguard.vpn.tunnel

import android.util.Log
import com.privacyguard.core.utils.Checksum
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TunWriter(
    private val sink: PacketSink,
    queueCapacity: Int = DEFAULT_CAPACITY,
) : Runnable {

    /** Where packets end up: the TUN device, or a capture in tests. */
    interface PacketSink {
        val isOpen: Boolean
        fun writePacket(packet: ByteArray): Boolean
    }

    companion object {
        private const val TAG = "TunWriter"
        private const val QUEUE_POLL_TIMEOUT_MS = 200L
        const val DEFAULT_CAPACITY = 1024
        private val SENTINEL = ByteArray(0)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private val lowWaterMark = queueCapacity / 4
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    /**
     * Called (on the writer thread) when the queue drains down to a quarter full,
     * so producers that paused for room can resume without waiting for a timer.
     */
    @Volatile var onDrained: (() -> Unit)? = null

    val totalPacketsWritten = AtomicLong(0)
    val totalBytesWritten = AtomicLong(0)
    val droppedOverflow = AtomicLong(0)
    val droppedClosed = AtomicLong(0)
    val droppedWriteError = AtomicLong(0)

    fun start() {
        if (running.getAndSet(true)) return
        thread = com.privacyguard.vpn.WorkerGuard.thread(this, "tun-writer").also { it.start() }
        Log.d(TAG, "TunWriter started")
    }

    fun stop(drainTimeoutMs: Long = 1000) {
        running.set(false)
        runCatching { queue.offer(SENTINEL, 50, TimeUnit.MILLISECONDS) }
        thread?.join(drainTimeoutMs)
        thread = null
        Log.d(TAG, "TunWriter stopped - written: ${totalPacketsWritten.get()} packets, ${totalBytesWritten.get()} bytes")
    }

    val isRunning: Boolean get() = running.get()

    /** Free slots in the queue; TCP uses this to read no more than it can inject. */
    val remainingCapacity: Int get() = queue.remainingCapacity()

    fun enqueue(packet: ByteArray): Boolean {
        if (!running.get() || !sink.isOpen) {
            droppedClosed.incrementAndGet()
            return false
        }
        return if (queue.offer(packet)) {
            true
        } else {
            droppedOverflow.incrementAndGet()
            Log.w(TAG, "Queue full, packet dropped")
            false
        }
    }

    /**
     * Like [enqueue], but waits up to [timeoutMs] for room instead of dropping.
     * For stream data that has no other way to be delivered in order.
     */
    fun enqueueBlocking(packet: ByteArray, timeoutMs: Long): Boolean {
        if (!running.get() || !sink.isOpen) {
            droppedClosed.incrementAndGet()
            return false
        }
        val accepted = try {
            queue.offer(packet, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!accepted) droppedOverflow.incrementAndGet()
        return accepted
    }

    /** Recomputes IPv4 header and TCP/UDP checksums, then enqueues. IPv6 packets carry their own. */
    fun enqueueWithChecksums(packet: ByteArray): Boolean {
        if (packet.size < 20) return false
        if ((packet[0].toInt() ushr 4) and 0xF != 4) return enqueue(packet)
        val fixed = packet.copyOf()
        Checksum.setIpv4HeaderChecksum(fixed, 0)
        val proto = fixed[9].toInt() and 0xFF
        when (proto) {
            6 -> Checksum.setTcpChecksum(fixed, 0)
            17 -> Checksum.setUdpChecksum(fixed, 0)
        }
        return enqueue(fixed)
    }

    val queueSize: Int get() = queue.size

    override fun run() {
        Log.d(TAG, "TunWriter loop started")

        while (running.get() || queue.isNotEmpty()) {
            val packet = try {
                queue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }

            if (packet === SENTINEL) break

            if (!sink.isOpen) {
                droppedClosed.addAndGet(queue.size.toLong() + 1)
                queue.clear()
                Log.w(TAG, "TUN interface closed, clearing queue")
                break
            }

            // A failure here would end this thread, and with it the tunnel: drop the packet.
            try {
                if (sink.writePacket(packet)) {
                    // Incoming direction for the capture: what apps actually receive.
                    if (com.privacyguard.app.core.pcap.PcapWriter.isCapturing()) com.privacyguard.app.core.pcap.PcapWriter.write(packet)
                    totalPacketsWritten.incrementAndGet()
                    totalBytesWritten.addAndGet(packet.size.toLong())
                } else {
                    droppedWriteError.incrementAndGet()
                    Log.w(TAG, "Failed to write packet to TUN")
                }
                if (queue.size == lowWaterMark) onDrained?.invoke()
            } catch (e: Exception) {
                droppedWriteError.incrementAndGet()
                Log.e(TAG, "write error: ${e.javaClass.simpleName}")
            }
        }

        running.set(false)
        Log.d(TAG, "TunWriter loop ended")
    }

    fun stats(): Stats = Stats(
        packetsWritten = totalPacketsWritten.get(),
        bytesWritten = totalBytesWritten.get(),
        droppedOverflow = droppedOverflow.get(),
        droppedClosed = droppedClosed.get(),
        droppedWriteError = droppedWriteError.get(),
        queueSize = queue.size,
        isRunning = running.get(),
    )

    data class Stats(
        val packetsWritten: Long,
        val bytesWritten: Long,
        val droppedOverflow: Long,
        val droppedClosed: Long,
        val droppedWriteError: Long,
        val queueSize: Int,
        val isRunning: Boolean,
    )
}
