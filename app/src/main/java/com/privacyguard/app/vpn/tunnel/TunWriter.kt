package com.privacyguard.vpn.tunnel

import com.privacyguard.core.utils.Checksum
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes raw IP packets back into the TUN interface (injecting them to the device).
 *
 * Packets arrive from multiple forwarder threads and are queued into a bounded
 * [ArrayBlockingQueue]. A single dedicated writer thread drains the queue and
 * calls [TunInterface.writePacket] sequentially (the TUN fd is not thread-safe).
 *
 * Backpressure: if the queue is full, [enqueue] drops the packet and increments
 * [droppedOverflow]. This is intentional — the VPN engine prefers dropping to
 * blocking the forwarder threads, which could cause deadlocks.
 *
 * Usage:
 * ```kotlin
 * val writer = TunWriter(tunInterface)
 * writer.start()
 *
 * // From any thread:
 * writer.enqueue(rawIpPacketBytes)
 *
 * // Shutdown:
 * writer.stop()
 * ```
 */
class TunWriter(
    private val tunInterface: TunInterface,
    /** Maximum number of pending packets in the write queue. */
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : Runnable {

    // ─────────────────────────────────────────────────────────────────────────
    // Queue
    // ─────────────────────────────────────────────────────────────────────────

    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private val running       = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    /** Starts the background writer thread. No-op if already running. */
    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tun-writer").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Drains the remaining queue and stops the writer thread.
     *
     * @param drainTimeoutMs maximum time (ms) to wait for the queue to empty.
     */
    fun stop(drainTimeoutMs: Long = 1_000) {
        running.set(false)
        // Offer a sentinel to unblock the blocking poll
        runCatching { queue.offer(SENTINEL, 50, TimeUnit.MILLISECONDS) }
        thread?.join(drainTimeoutMs)
        thread = null
    }

    /** Returns true if the writer thread is active. */
    val isRunning: Boolean get() = running.get()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enqueues [packet] for writing to the TUN interface.
     * Non-blocking: if the queue is full, the packet is dropped.
     *
     * @return true if the packet was enqueued, false if dropped.
     */
    fun enqueue(packet: ByteArray): Boolean {
        if (!running.get() || !tunInterface.isOpen) {
            droppedClosed.incrementAndGet()
            return false
        }
        return if (queue.offer(packet)) {
            true
        } else {
            droppedOverflow.incrementAndGet()
            false
        }
    }

    /**
     * Enqueues [packet] and verifies/recalculates its checksums before writing.
     * Slightly slower than [enqueue] — use when the packet was modified by
     * the filter/NAT layer and checksums need updating.
     */
    fun enqueueWithChecksums(packet: ByteArray): Boolean {
        if (packet.size < 20) return false
        val fixed = packet.copyOf()
        Checksum.setIpv4HeaderChecksum(fixed, 0)
        val proto = fixed[9].toInt() and 0xFF
        when (proto) {
            6  -> Checksum.setTcpChecksum(fixed, 0)
            17 -> Checksum.setUdpChecksum(fixed, 0)
        }
        return enqueue(fixed)
    }

    /** Returns the current number of packets waiting in the write queue. */
    val queueSize: Int get() = queue.size

    // ─────────────────────────────────────────────────────────────────────────
    // Writer Loop
    // ─────────────────────────────────────────────────────────────────────────

    override fun run() {
        while (running.get() || queue.isNotEmpty()) {
            val packet = try {
                // Blocking poll with timeout allows the loop to re-check [running]
                queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }

            if (packet === SENTINEL) break

            if (!tunInterface.isOpen) {
                droppedClosed.addAndGet(queue.size.toLong() + 1)
                queue.clear()
                break
            }

            if (tunInterface.writePacket(packet)) {
                totalPacketsWritten.incrementAndGet()
                totalBytesWritten.addAndGet(packet.size.toLong())
            } else {
                droppedWriteError.incrementAndGet()
            }
        }

        running.set(false)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val totalPacketsWritten = AtomicLong(0)
    val totalBytesWritten   = AtomicLong(0)
    val droppedOverflow     = AtomicLong(0)
    val droppedClosed       = AtomicLong(0)
    val droppedWriteError   = AtomicLong(0)

    data class Stats(
        val packetsWritten: Long,
        val bytesWritten:   Long,
        val droppedOverflow: Long,
        val droppedClosed:   Long,
        val droppedWriteError: Long,
        val queueSize:      Int,
        val isRunning:      Boolean,
    )

    fun stats(): Stats = Stats(
        packetsWritten  = totalPacketsWritten.get(),
        bytesWritten    = totalBytesWritten.get(),
        droppedOverflow = droppedOverflow.get(),
        droppedClosed   = droppedClosed.get(),
        droppedWriteError = droppedWriteError.get(),
        queueSize       = queue.size,
        isRunning       = running.get(),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 512

        /** Sentinel object used to unblock the poll and stop the writer loop. */
        private val SENTINEL = ByteArray(0)
    }
}