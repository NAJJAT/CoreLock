package com.privacyguard.core.tls

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the TLS ClientHello out of a client's QUIC Initial packets.
 *
 * Initial packets are encrypted, but with keys derived only from the Destination
 * Connection ID in the packet itself and a salt published in the standard
 * (RFC 9001 §5.2, RFC 9369 §3.3.1). Anyone on the path can derive them by design;
 * this is not interception and needs no certificate. Only the client's own Initial
 * packets are read; nothing is modified.
 */
object QuicInitial {

    const val VERSION_1 = 0x00000001
    const val VERSION_2 = 0x6b3343cf

    private val SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    private val SALT_V2 = hex("0dede3def700a6db819381be6e269dcbf9bd2ed9")

    /** Packet protection keys for one direction. */
    class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    /** One CRYPTO frame: [data] belongs at [offset] of the TLS handshake stream. */
    class CryptoChunk(val offset: Long, val data: ByteArray)

    /** Client Initial keys for [dcid] (RFC 9001 §5.2; RFC 9369 for v2). */
    fun clientKeys(dcid: ByteArray, version: Int): Keys? {
        val v2 = version == VERSION_2
        if (!v2 && version != VERSION_1) return null
        val initialSecret = hkdfExtract(if (v2) SALT_V2 else SALT_V1, dcid)
        val client = expandLabel(initialSecret, "client in", 32)
        val prefix = if (v2) "quicv2 " else "quic "
        return Keys(
            key = expandLabel(client, prefix + "key", 16),
            iv = expandLabel(client, prefix + "iv", 12),
            hp = expandLabel(client, prefix + "hp", 16),
        )
    }

    /**
     * The CRYPTO frames of every client Initial packet in one UDP datagram (they can
     * be coalesced with later packets), or null if the datagram has none we can read.
     */
    fun cryptoFrames(datagram: ByteArray): List<CryptoChunk>? {
        val out = ArrayList<CryptoChunk>()
        var pos = 0
        var sawInitial = false
        while (pos < datagram.size) {
            val packetEnd = readInitial(datagram, pos, out) ?: break
            sawInitial = true
            pos = packetEnd
        }
        return if (sawInitial) out else null
    }

    /** Decrypts the Initial packet at [start]; returns where the packet ends, or null. */
    private fun readInitial(d: ByteArray, start: Int, out: MutableList<CryptoChunk>): Int? {
        try {
            val first = d[start].toInt() and 0xFF
            if ((first and 0xC0) != 0xC0 || start + 7 > d.size) return null
            val version = readInt(d, start + 1)
            val type = (first ushr 4) and 0x03
            val isInitial = (version == VERSION_1 && type == 0) || (version == VERSION_2 && type == 1)
            if (!isInitial) return null
            run {
                var p = start + 5
                val dcidLen = d[p].toInt() and 0xFF; p++
                if (dcidLen > 20 || p + dcidLen > d.size) return null
                val dcid = d.copyOfRange(p, p + dcidLen); p += dcidLen
                val scidLen = d[p].toInt() and 0xFF; p++
                if (scidLen > 20) return null
                p += scidLen
                val (tokenLen, t) = readVarint(d, p) ?: return null; p = t
                if (tokenLen > d.size) return null
                p += tokenLen.toInt()
                val (length, l) = readVarint(d, p) ?: return null; p = l
                if (length > d.size) return null
                val pnOffset = p
                val packetEnd = pnOffset + length.toInt()
                if (length < 20 || packetEnd > d.size || pnOffset + 4 + 16 > d.size) return null
                val keys = clientKeys(dcid, version) ?: return null

                // Header protection (RFC 9001 §5.4): mask from a 16-byte sample.
                val sample = d.copyOfRange(pnOffset + 4, pnOffset + 20)
                val mask = Cipher.getInstance("AES/ECB/NoPadding")
                    .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES")) }.doFinal(sample)
                val header = d.copyOfRange(start, pnOffset + 4)
                header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
                val pnLen = (header[0].toInt() and 0x03) + 1
                var pn = 0L
                for (i in 0 until pnLen) {
                    val b = (header[pnOffset - start + i].toInt() xor mask[1 + i].toInt()) and 0xFF
                    header[pnOffset - start + i] = b.toByte()
                    pn = (pn shl 8) or b.toLong()
                }
                val aad = header.copyOfRange(0, pnOffset - start + pnLen)
                val nonce = keys.iv.copyOf()
                for (i in 0 until 8) nonce[11 - i] = (nonce[11 - i].toInt() xor ((pn ushr (8 * i)).toInt() and 0xFF)).toByte()
                val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(aad)
                }
                val payload = gcm.doFinal(d, pnOffset + pnLen, packetEnd - pnOffset - pnLen)
                parseFrames(payload, out)
                return packetEnd
            }
        } catch (_: Exception) {
            return null   // not decryptable: not an Initial we can read (or not QUIC at all)
        }
    }

    private fun parseFrames(p: ByteArray, out: MutableList<CryptoChunk>) {
        var i = 0
        while (i < p.size) {
            when (p[i].toInt() and 0xFF) {
                0x00, 0x01 -> i++                                     // PADDING, PING
                0x06 -> {                                             // CRYPTO
                    val (offset, a) = readVarint(p, i + 1) ?: return
                    val (len, b) = readVarint(p, a) ?: return
                    if (len < 0 || len > p.size || b + len > p.size) return
                    out += CryptoChunk(offset, p.copyOfRange(b, b + len.toInt()))
                    i = b + len.toInt()
                }
                0x02, 0x03 -> {                                       // ACK (ECN): skip
                    var q = i + 1
                    val (_, q1) = readVarint(p, q) ?: return
                    val (_, q2) = readVarint(p, q1) ?: return
                    val (ranges, q3) = readVarint(p, q2) ?: return
                    val (_, q4) = readVarint(p, q3) ?: return
                    q = q4
                    repeat(ranges.toInt().coerceAtMost(256)) {
                        q = readVarint(p, q)?.second ?: return
                        q = readVarint(p, q)?.second ?: return
                    }
                    if ((p[i].toInt() and 0xFF) == 0x03) repeat(3) { q = readVarint(p, q)?.second ?: return }
                    i = q
                }
                else -> return                                        // CONNECTION_CLOSE or unknown
            }
        }
    }

    /** QUIC variable-length integer (RFC 9000 §16): value and the position after it. */
    fun readVarint(d: ByteArray, pos: Int): Pair<Long, Int>? {
        if (pos >= d.size) return null
        val first = d[pos].toInt() and 0xFF
        val len = 1 shl (first ushr 6)
        if (pos + len > d.size) return null
        var v = (first and 0x3F).toLong()
        for (i in 1 until len) v = (v shl 8) or (d[pos + i].toLong() and 0xFF)
        return v to pos + len
    }

    // ── HKDF-SHA256 (RFC 5869) and TLS 1.3 HKDF-Expand-Label (RFC 8446 §7.1) ─

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val full = ("tls13 $label").toByteArray(Charsets.US_ASCII)
        val info = byteArrayOf((length ushr 8).toByte(), length.toByte(), full.size.toByte()) + full + byteArrayOf(0)
        // One block is enough: every length used here is at most 32 bytes.
        return hmac(secret, info + byteArrayOf(1)).copyOf(length)
    }

    private fun readInt(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xFF) shl 24) or ((d[off + 1].toInt() and 0xFF) shl 16) or
            ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Reassembles the ClientHello of each QUIC flow from its first Initial datagrams.
 * Browsers split a large (post-quantum) ClientHello over two or more Initial
 * packets and send its CRYPTO frames out of order, so one datagram is not enough.
 * Bounded: [MAX_FLOWS] flows, [MAX_DATAGRAMS] datagrams and [MAX_BYTES] per flow.
 */
class QuicSniTracker<K : Any> {

    companion object {
        const val MAX_FLOWS = 512
        const val MAX_DATAGRAMS = 6
        const val MAX_BYTES = 16_384
    }

    sealed interface Result {
        /** Not (yet) known: forward the datagram. */
        data object Pending : Result
        /** Not a readable client Initial, or gave up: stop looking at this flow. */
        data object Unreadable : Result
        /** The ClientHello is complete. [sni] is null when it had none or an invalid one. */
        data class Hello(val hello: ClientHello) : Result
    }

    private class Flow {
        val chunks = HashMap<Long, ByteArray>()
        var datagrams = 0
        var bytes = 0
    }

    private val flows = java.util.LinkedHashMap<K, Flow>(16, 0.75f, true)

    @Synchronized
    fun offer(key: K, datagram: ByteArray): Result {
        val frames = QuicInitial.cryptoFrames(datagram)
        if (frames == null) {
            // A flow that already started is allowed non-Initial packets (e.g. 0-RTT).
            return if (flows.containsKey(key)) Result.Pending else Result.Unreadable
        }
        val flow = flows.getOrPut(key) {
            if (flows.size >= MAX_FLOWS) flows.remove(flows.keys.first())
            Flow()
        }
        flow.datagrams++
        for (c in frames) {
            if (c.offset > MAX_BYTES || flow.bytes + c.data.size > MAX_BYTES) { flows.remove(key); return Result.Unreadable }
            if (flow.chunks.putIfAbsent(c.offset, c.data) == null) flow.bytes += c.data.size
        }
        val hello = assemble(flow)
        return when {
            hello != null -> { flows.remove(key); Result.Hello(hello) }
            flow.datagrams >= MAX_DATAGRAMS -> { flows.remove(key); Result.Unreadable }
            else -> Result.Pending
        }
    }

    @Synchronized
    fun forget(key: K) { flows.remove(key) }

    /** The ClientHello once the stream from offset 0 holds the whole message. */
    private fun assemble(flow: Flow): ClientHello? {
        val stream = java.io.ByteArrayOutputStream()
        var next = 0L
        while (true) {
            // Frames may overlap; take any chunk that starts at or before `next`.
            val entry = flow.chunks.entries.filter { it.key <= next && it.key + it.value.size > next }
                .maxByOrNull { it.key + it.value.size } ?: break
            val skip = (next - entry.key).toInt()
            stream.write(entry.value, skip, entry.value.size - skip)
            next = entry.key + entry.value.size
        }
        val data = stream.toByteArray()
        if (data.size < 4 || data[0].toInt() != 0x01) return null
        val msgLen = ((data[1].toInt() and 0xFF) shl 16) or ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (data.size < 4 + msgLen) return null
        return ClientHelloParser.parseHandshake(data.copyOf(4 + msgLen))
    }
}
