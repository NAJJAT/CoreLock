package com.privacyguard.core.tls

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** QUIC client packets for tests, built the way a client protects them (RFC 9001 §5). */
object QuicTestPackets {

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun varint(v: Long): ByteArray = when {
        v < 64 -> byteArrayOf(v.toByte())
        v < 16384 -> byteArrayOf((0x40 or (v ushr 8).toInt()).toByte(), v.toByte())
        else -> byteArrayOf((0x80 or (v ushr 24).toInt()).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    fun crypto(offset: Int, data: ByteArray) = byteArrayOf(0x06) + varint(offset.toLong()) + varint(data.size.toLong()) + data

    /** One protected client Initial carrying [frames], padded to 1200 bytes like real clients. */
    fun initial(frames: ByteArray, pn: Int, version: Int = QuicInitial.VERSION_1): ByteArray {
        val dcid = hex("8394c8f03e515708")
        val keys = QuicInitial.clientKeys(dcid, version)!!
        val pnLen = 2
        val typeBits = if (version == QuicInitial.VERSION_2) 1 else 0
        val first = 0xC0 or (typeBits shl 4) or (pnLen - 1)
        val plain = frames + ByteArray(maxOf(0, 1100 - frames.size))      // PADDING
        val length = pnLen + plain.size + 16
        val header = ByteArrayOutputStream().apply {
            write(first)
            write(byteArrayOf((version ushr 24).toByte(), (version ushr 16).toByte(), (version ushr 8).toByte(), version.toByte()))
            write(dcid.size); write(dcid); write(0)                       // DCID, empty SCID
            write(varint(0))                                               // no token
            write(varint(length.toLong()))
            write(byteArrayOf((pn ushr 8).toByte(), pn.toByte()))
        }.toByteArray()
        val nonce = keys.iv.copyOf().also { it[11] = (it[11].toInt() xor (pn and 0xFF)).toByte(); it[10] = (it[10].toInt() xor (pn ushr 8 and 0xFF)).toByte() }
        val sealed = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(header)
        }.doFinal(plain)
        val packet = header + sealed
        val pnOffset = header.size - pnLen
        val mask = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES")) }
            .doFinal(packet.copyOfRange(pnOffset + 4, pnOffset + 20))
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until pnLen) packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        return packet
    }

    /** A ClientHello handshake message (no record header) with [sni] and [filler] bytes of extensions. */
    fun clientHello(sni: String, filler: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val name = sni.toByteArray()
        val sniExt = u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val pad = if (filler > 0) u16(0x0015) + u16(filler) + ByteArray(filler) else ByteArray(0)
        val ext = sniExt + u16(0x002b) + u16(3) + byteArrayOf(2, 3, 4) + pad
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(ext.size) + ext
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    /** A large (post-quantum sized) ClientHello for [sni], split over two Initials. */
    fun splitHello(sni: String): Pair<ByteArray, ByteArray> {
        val ch = clientHello(sni, filler = 1400)
        return initial(crypto(0, ch.copyOfRange(0, 900)), pn = 0) to initial(crypto(900, ch.copyOfRange(900, ch.size)), pn = 1)
    }

    /** A 1-RTT (short header) packet: fixed bit set, form bit clear. Not decryptable here. */
    fun shortHeader(size: Int = 60): ByteArray = ByteArray(size) { 0x11 }.also { it[0] = 0x41 }
}
