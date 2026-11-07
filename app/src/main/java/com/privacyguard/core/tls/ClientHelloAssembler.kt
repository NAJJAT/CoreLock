package com.privacyguard.core.tls

/**
 * Puts a TLS ClientHello back together from the first bytes a client sends.
 *
 * TLS lets a handshake message span several records (RFC 8446 §5.1), and TCP splits the
 * records into segments. Reading only the first record let a client hide its server name
 * in a later one. This walks the handshake records in [assemble]'s buffer and says whether
 * the whole ClientHello is there, more bytes are needed, or it can't be read.
 *
 * Untrusted input: every length is checked before use, and nothing beyond [MAX_BYTES] is
 * ever buffered or copied. No recursion; the only allocation is the joined record when a
 * hello really spans records (rare), never for the usual single record.
 */
object ClientHelloAssembler {

    /** Largest TLS record payload (RFC 8446 §5.1: 2^14). */
    private const val MAX_RECORD = 16_384

    /**
     * Most bytes buffered for one ClientHello: one full record. Chrome's post-quantum hello
     * is about 1.8 KB; a client needing more than 16 KB is not a browser, and buffering more
     * per connection would let a local app tie up memory with half-sent hellos.
     */
    const val MAX_BYTES = 5 + MAX_RECORD

    sealed interface Result
    /** The hello is not complete yet; buffer and wait for more (the caller caps the buffer). */
    data object NeedMore : Result
    /** Not a ClientHello, malformed, or larger than [MAX_BYTES]: its name can't be read. */
    data object Unreadable : Result
    /**
     * The stream starts with 0x16 but not with a TLS record header (version 3.0 to 3.4), so
     * it is not TLS at all: another protocol on the port, handled like any non-TLS stream.
     */
    data object NotTls : Result
    /**
     * The whole ClientHello, as one TLS record [ClientHelloParser.parse] reads: the caller's
     * buffer itself when it is exactly one record holding the hello, else a joined copy.
     */
    class Complete(val record: ByteArray) : Result

    fun assemble(buf: ByteArray): Result {
        var pos = 0          // start of the next record header
        var have = 0         // handshake bytes in complete records so far
        var need = -1        // 4 + ClientHello length, once its header is in
        var records = 0
        // Not TLS at all unless the stream opens with a record header: 0x16, version 3.0 to
        // 3.4. A random-looking protocol on 443 starts with 0x16 once in 256 connections and
        // must not be reset as an unreadable hello; a real server wouldn't take it as TLS.
        if (buf.size >= 2 && buf[1].toInt() != 3) return NotTls
        if (buf.size >= 3 && (buf[2].toInt() and 0xFF) > 4) return NotTls
        while (need < 0 || have < need) {
            if (buf.size - pos < 5) return needMoreOrUnreadable(buf)
            if ((buf[pos].toInt() and 0xFF) != 0x16 || buf[pos + 1].toInt() != 3) return Unreadable
            val len = u16(buf, pos + 3)
            if (len == 0 || len > MAX_RECORD) return Unreadable
            if (buf.size - pos - 5 < len) return needMoreOrUnreadable(buf)
            have += len
            pos += 5 + len
            records++
            if (need < 0 && have >= 4) {
                if (handshakeByte(buf, 0) != 0x01) return Unreadable
                val bodyLen = (handshakeByte(buf, 1) shl 16) or (handshakeByte(buf, 2) shl 8) or handshakeByte(buf, 3)
                need = 4 + bodyLen
                if (5 + need > MAX_BYTES) return Unreadable
            }
        }
        // The usual case: one record that is exactly the hello. Nothing to copy.
        if (records == 1 && have == need && buf.size == 5 + need) return Complete(buf)
        return Complete(joined(buf, need))
    }

    private fun needMoreOrUnreadable(buf: ByteArray): Result =
        if (buf.size >= MAX_BYTES) Unreadable else NeedMore

    /** Byte [index] of the handshake data, counted across the record payloads in [buf]. */
    private fun handshakeByte(buf: ByteArray, index: Int): Int {
        var pos = 0
        var skip = index
        while (pos + 5 <= buf.size) {
            val len = u16(buf, pos + 3)
            if (skip < len) return buf[pos + 5 + skip].toInt() and 0xFF
            skip -= len
            pos += 5 + len
        }
        return -1   // unreachable: callers only ask for bytes in complete records
    }

    /** The first [need] handshake bytes of [buf]'s records as one record (need ≤ MAX_RECORD). */
    private fun joined(buf: ByteArray, need: Int): ByteArray {
        val out = ByteArray(5 + need)
        // The client's own record version, as in a single record: the TLS classifier reads it.
        out[0] = 0x16; out[1] = buf[1]; out[2] = buf[2]
        out[3] = (need ushr 8).toByte(); out[4] = need.toByte()
        var pos = 0
        var at = 5
        while (at < out.size) {
            val len = u16(buf, pos + 3)
            val take = minOf(len, out.size - at)
            System.arraycopy(buf, pos + 5, out, at, take)
            at += take
            pos += 5 + len
        }
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
