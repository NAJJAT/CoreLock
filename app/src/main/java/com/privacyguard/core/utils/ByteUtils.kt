package com.privacyguard.core.utils

/**
 * Low-level byte manipulation utilities for reading and writing
 * network packet fields from raw byte arrays.
 *
 * All methods use big-endian byte order (network byte order).
 */
object ByteUtils {

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads an unsigned 8-bit integer from [buf] at [offset].
     */
    fun readUInt8(buf: ByteArray, offset: Int): Int {
        require(offset in buf.indices) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        return buf[offset].toInt() and 0xFF
    }

    /**
     * Reads an unsigned 16-bit integer (big-endian) from [buf] at [offset].
     */
    fun readUInt16(buf: ByteArray, offset: Int): Int {
        require(offset + 1 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        return ((buf[offset].toInt() and 0xFF) shl 8) or
                (buf[offset + 1].toInt() and 0xFF)
    }

    /**
     * Reads an unsigned 32-bit integer (big-endian) from [buf] at [offset].
     * Returns a Long to avoid signed overflow.
     */
    fun readUInt32(buf: ByteArray, offset: Int): Long {
        require(offset + 3 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        return ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
    }

    /**
     * Reads a signed 32-bit integer (big-endian) from [buf] at [offset].
     */
    fun readInt32(buf: ByteArray, offset: Int): Int {
        require(offset + 3 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        return (buf[offset].toInt() shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }

    /**
     * Copies [length] bytes from [buf] starting at [offset] into a new array.
     */
    fun readBytes(buf: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= buf.size) {
            "Invalid range: offset=$offset, length=$length, bufSize=${buf.size}"
        }
        return buf.copyOfRange(offset, offset + length)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes an unsigned 8-bit value to [buf] at [offset].
     */
    fun writeUInt8(buf: ByteArray, offset: Int, value: Int) {
        require(offset in buf.indices) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        buf[offset] = (value and 0xFF).toByte()
    }

    /**
     * Writes an unsigned 16-bit value (big-endian) to [buf] at [offset].
     */
    fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        require(offset + 1 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        buf[offset]     = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    /**
     * Writes an unsigned 32-bit value (big-endian) to [buf] at [offset].
     * Accepts a Long to allow full 32-bit unsigned range.
     */
    fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        require(offset + 3 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        buf[offset]     = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8)  and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * Writes a signed 32-bit integer (big-endian) to [buf] at [offset].
     */
    fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        require(offset + 3 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        buf[offset]     = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    /**
     * Copies [src] into [dst] starting at [dstOffset].
     */
    fun writeBytes(dst: ByteArray, dstOffset: Int, src: ByteArray) {
        require(dstOffset + src.size <= dst.size) {
            "Source does not fit: dstOffset=$dstOffset, srcSize=${src.size}, dstSize=${dst.size}"
        }
        src.copyInto(dst, dstOffset)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP Address Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a 4-byte big-endian IPv4 address at [offset] in [buf]
     * to its dotted-decimal string representation (e.g. "192.168.1.1").
     */
    fun readIpv4Address(buf: ByteArray, offset: Int): String {
        require(offset + 3 < buf.size) { "Offset $offset out of bounds for buffer size ${buf.size}" }
        return "${buf[offset].toInt() and 0xFF}." +
               "${buf[offset + 1].toInt() and 0xFF}." +
               "${buf[offset + 2].toInt() and 0xFF}." +
               "${buf[offset + 3].toInt() and 0xFF}"
    }

    /**
     * Converts an IPv4 dotted-decimal string to a 4-byte array (big-endian).
     * @throws IllegalArgumentException if the string is not a valid IPv4 address.
     */
    fun ipv4ToBytes(ip: String): ByteArray {
        val parts = ip.split(".")
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        return ByteArray(4) { i ->
            val v = parts[i].toIntOrNull()
            require(v != null && v in 0..255) { "Invalid octet '${parts[i]}' in address $ip" }
            v.toByte()
        }
    }

    /**
     * Writes a dotted-decimal IPv4 address string into [buf] at [offset].
     */
    fun writeIpv4Address(buf: ByteArray, offset: Int, ip: String) {
        writeBytes(buf, offset, ipv4ToBytes(ip))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bit / Flag Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the bit at [bitPos] (0 = MSB) of [byte] is set.
     */
    fun isBitSet(byte: Int, bitPos: Int): Boolean =
        (byte and (1 shl (7 - bitPos))) != 0

    /**
     * Extracts bits from [value] between [high] (inclusive, MSB side)
     * and [low] (inclusive, LSB side), e.g. extractBits(0b10110000, 7, 4) → 0b1011.
     */
    fun extractBits(value: Int, high: Int, low: Int): Int {
        val mask = (1 shl (high - low + 1)) - 1
        return (value ushr low) and mask
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a hex-encoded string representation of [buf], e.g. "DE AD BE EF".
     * Useful for debugging raw packet bytes.
     */
    fun toHexString(buf: ByteArray, maxBytes: Int = buf.size): String =
        buf.take(maxBytes).joinToString(" ") { "%02X".format(it) }

    /**
     * Returns a copy of [buf] with zero-padding to [targetSize].
     * If buf.size >= targetSize the original array is returned unchanged.
     */
    fun padTo(buf: ByteArray, targetSize: Int): ByteArray {
        if (buf.size >= targetSize) return buf
        return buf.copyOf(targetSize)
    }
}