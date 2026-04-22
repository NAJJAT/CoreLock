package com.privacyguard.app.core.pcap

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcapGlobalHeader {

    private const val MAGIC_NUMBER = 0xA1B2C3D4.toInt()
    private const val VERSION_MAJOR = 2
    private const val VERSION_MINOR = 4
    private const val THISZONE = 0
    private const val SIGFIGS = 0
    private const val SNAPLEN = 65_535
    private const val NETWORK = 101 // LINKTYPE_RAW for packets coming from Android's TUN interface

    fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC_NUMBER)
            .putShort(VERSION_MAJOR.toShort())
            .putShort(VERSION_MINOR.toShort())
            .putInt(THISZONE)
            .putInt(SIGFIGS)
            .putInt(SNAPLEN)
            .putInt(NETWORK)
            .array()
    }
}

data class PcapPacketHeader(
    val timestampSeconds: Long,
    val timestampMicroseconds: Long,
    val capturedLength: Int,
    val originalLength: Int
) {
    fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(timestampSeconds.toInt())
            .putInt(timestampMicroseconds.toInt())
            .putInt(capturedLength)
            .putInt(originalLength)
            .array()
    }

    companion object {
        fun fromPacket(packet: ByteArray, timestampMillis: Long = System.currentTimeMillis()): PcapPacketHeader {
            val seconds = timestampMillis / 1000L
            val microseconds = (timestampMillis % 1000L) * 1000L
            return PcapPacketHeader(
                timestampSeconds = seconds,
                timestampMicroseconds = microseconds,
                capturedLength = packet.size,
                originalLength = packet.size
            )
        }
    }
}
