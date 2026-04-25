package com.privacyguard.vpn.tunnel

import android.util.Log
import com.privacyguard.core.utils.Checksum
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TunWriter(
    private val tunInterface: TunInterface,
    queueCapacity: Int = 512,
) : Runnable {

    companion object {
        private const val TAG = "TunWriter"
        private const val QUEUE_POLL_TIMEOUT_MS = 200L
        private val SENTINEL = ByteArray(0)
    }

    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    val totalPacketsWritten = AtomicLong(0)
    val totalBytesWritten = AtomicLong(0)
    val droppedOverflow = AtomicLong(0)
    val droppedClosed = AtomicLong(0)
    val droppedWriteError = AtomicLong(0)

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tun-writer").also {
            it.isDaemon = true
            it.start()
        }
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

    fun enqueue(packet: ByteArray): Boolean {
        if (!running.get() || !tunInterface.isOpen) {
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

    fun enqueueWithChecksums(packet: ByteArray): Boolean {
        if (packet.size < 20) return false
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

            if (!tunInterface.isOpen) {
                droppedClosed.addAndGet(queue.size.toLong() + 1)
                queue.clear()
                Log.w(TAG, "TUN interface closed, clearing queue")
                break
            }

            if (tunInterface.writePacket(packet)) {
                totalPacketsWritten.incrementAndGet()
                totalBytesWritten.addAndGet(packet.size.toLong())
                if (totalPacketsWritten.get() % 100 == 0L) {
                    Log.d(TAG, "Written ${totalPacketsWritten.get()} packets")
                }
            } else {
                droppedWriteError.incrementAndGet()
                Log.w(TAG, "Failed to write packet to TUN")
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