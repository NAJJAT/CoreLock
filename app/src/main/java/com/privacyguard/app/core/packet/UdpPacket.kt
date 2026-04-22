package com.privacyguard.app.core.packet

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class UdpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int,
    val checksum: Int,
    val payload: ByteArray
) {
    companion object {
        fun parse(data: ByteArray, length: Int): UdpPacket? {
            if (length < 8) return null

            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            val sourcePort = buffer.getShort().toInt() and 0xFFFF
            val destinationPort = buffer.getShort().toInt() and 0xFFFF
            val packetLength = buffer.getShort().toInt() and 0xFFFF
            val checksum = buffer.getShort().toInt() and 0xFFFF
            if (packetLength < 8 || packetLength > length) return null

            val payloadLength = packetLength - 8
            val payload = ByteArray(payloadLength)
            if (payloadLength > 0) {
                buffer.get(payload)
            }

            return UdpPacket(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                length = packetLength,
                checksum = checksum,
                payload = payload
            )
        }
    }
}
