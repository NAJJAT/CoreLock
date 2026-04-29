package com.privacyguard.vpn.tunnel

import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Wraps the raw TUN interface file descriptor provided by Android's [VpnService].
 *
 * The TUN (tunnel) interface is a virtual network device exposed as a file:
 *  - Reading from it yields raw IPv4 packets sent by apps on the device.
 *  - Writing to it injects raw IPv4 packets back to the device as if they
 *    arrived from the network.
 *
 * Each read returns exactly one IP packet. Each write sends exactly one
 * IP packet. There is no framing — the kernel handles packet boundaries.
 *
 * ┌──────────────────────────────────────────────────┐
 * │ Device apps (Chrome, Instagram, …)               │
 * │         ↓ send / ↑ recv                          │
 * │   [ Android VPN Service / TUN interface ]        │
 * │         ↓ read / ↑ write (this class)            │
 * │   [ PrivacyGuard VPN Engine ]                    │
 * │         ↓ forward / inspect / block              │
 * │   [ Real Internet sockets ]                      │
 * └──────────────────────────────────────────────────┘
 *
 * Note: this class is deliberately free of Android framework dependencies
 * beyond [FileDescriptor], so it can be mocked in unit tests using piped
 * streams.
 */
class TunInterface(
    private val fd: FileDescriptor,
    /** Maximum Transmission Unit — must match the VpnService MTU setting. */
    val mtu: Int = DEFAULT_MTU,
) : Closeable {

    // ─────────────────────────────────────────────────────────────────────────
    // Streams
    // ─────────────────────────────────────────────────────────────────────────

    private val inputStream  = FileInputStream(fd)
    private val outputStream = FileOutputStream(fd)

    @Volatile private var closed = false

    // ─────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads one IP packet from the TUN device into a freshly-allocated [ByteArray].
     *
     * Blocks until a packet is available. Returns null if the interface has been
     * closed or an I/O error occurs (both are treated as shutdown signals).
     *
     * @return raw IP packet bytes, or null on shutdown / error.
     */
    fun readPacket(): ByteArray? {
        if (closed) return null
        val buf = ByteArray(mtu)
        return try {
            val n = inputStream.read(buf)
            if (n <= 0) null else buf.copyOf(n)
        } catch (e: IOException) {
            if (!closed) System.err.println("[TunInterface] read error: ${e.message}")
            null
        }
    }

    /**
     * Reads one IP packet into a caller-provided buffer.
     * No allocation — the caller must pre-allocate [buf] once per session.
     *
     * @return number of bytes read, 0 if the interface is idle/non-blocking,
     *         or -1 on shutdown / error.
     */
    fun readInto(buf: ByteArray): Int {
        if (closed) return -1
        return try {
            inputStream.read(buf)
        } catch (e: IOException) {
            if (!closed) System.err.println("[TunInterface] read error: ${e.message}")
            -1
        }
    }

    /**
     * Reads one IP packet from the TUN device into the provided [ByteBuffer].
     * The buffer is cleared before reading.
     *
     * @return the number of bytes read, or -1 on shutdown / error.
     */
    fun readPacket(buffer: ByteBuffer): Int {
        if (closed) return -1
        buffer.clear()
        return try {
            val n = inputStream.read(buffer.array(), buffer.arrayOffset(), buffer.capacity())
            if (n > 0) buffer.limit(n)
            n
        } catch (e: IOException) {
            if (!closed) System.err.println("[TunInterface] read error: ${e.message}")
            -1
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes one IP packet to the TUN device (injects it back to the device stack).
     *
     * @param packet raw IP packet bytes to inject.
     * @param offset start index within [packet].
     * @param length number of bytes to write.
     * @return true on success, false if closed or an I/O error occurred.
     */
    fun writePacket(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): Boolean {
        if (closed) return false
        return try {
            outputStream.write(packet, offset, length)
            true
        } catch (e: IOException) {
            if (!closed) System.err.println("[TunInterface] write error: ${e.message}")
            false
        }
    }

    /**
     * Writes one IP packet from a [ByteBuffer] to the TUN device.
     * Reads [buffer.remaining()] bytes starting at [buffer.position()].
     *
     * @return true on success.
     */
    fun writePacket(buffer: ByteBuffer): Boolean {
        if (closed) return false
        return try {
            val bytes  = buffer.array()
            val offset = buffer.arrayOffset() + buffer.position()
            val length = buffer.remaining()
            outputStream.write(bytes, offset, length)
            true
        } catch (e: IOException) {
            if (!closed) System.err.println("[TunInterface] write error: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if this interface is still open and usable. */
    val isOpen: Boolean get() = !closed

    /**
     * Closes the TUN interface streams.
     * Safe to call multiple times. After closing, all reads return null and
     * all writes return false.
     */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Default MTU for the TUN device (matches typical Android VPN MTU). */
        const val DEFAULT_MTU = 32_767

        /**
         * Minimum IPv4 packet size we are willing to process.
         * Smaller packets are silently dropped.
         */
        const val MIN_PACKET_SIZE = 20
    }
}