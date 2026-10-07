package com.privacyguard.vpn.mitm

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Byte-level HTTP/1.x helpers for captured traffic: message framing, chunked
 * transfer decoding, body decompression and text rendering.
 *
 * Pure JVM (no Android APIs) so it is unit-testable.
 */
object HttpCodec {

    /** Upper bound for a decompressed body — protects against zip bombs. */
    const val MAX_DECODED_BYTES = 2 * 1024 * 1024

    private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)

    data class Head(val startLine: String, val headers: Map<String, String>, val bodyOffset: Int) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** Index of the blank line ending the header block, or -1 if not yet complete. */
    fun headerEnd(data: ByteArray): Int = indexOf(data, CRLFCRLF, 0)

    fun parseHead(data: ByteArray): Head? {
        val end = headerEnd(data)
        if (end < 0) return null
        val lines = String(data, 0, end, StandardCharsets.ISO_8859_1).split("\r\n")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            // Repeated headers (e.g. Set-Cookie) are joined rather than overwritten.
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        return Head(lines.first(), headers, end + 4)
    }

    /**
     * Total length in bytes of the first complete HTTP message in [data], or null
     * if more bytes are needed. Handles Content-Length, chunked encoding and
     * messages without a body.
     */
    fun messageLength(data: ByteArray): Int? {
        val head = parseHead(data) ?: return null
        val start = head.bodyOffset
        if (isChunked(head)) {
            val bodyLen = chunkedLength(data, start) ?: return null
            return start + bodyLen
        }
        val lengthText = head.header("Content-Length")?.trim()
            ?: return start   // no length and not chunked: treat as header-only
        // Negative, non-numeric or absurd lengths are malformed: take what we have
        // rather than wait forever or overflow start + length.
        val length = lengthText.toLongOrNull()?.takeIf { it >= 0 } ?: return data.size
        return if (data.size.toLong() >= start + length) (start + length).toInt() else null
    }

    fun isChunked(head: Head): Boolean =
        head.header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true

    /** Length of a complete chunked body starting at [from], or null if incomplete. */
    private fun chunkedLength(data: ByteArray, from: Int): Int? {
        var pos = from
        while (true) {
            val lineEnd = indexOf(data, byteArrayOf(13, 10), pos)
            if (lineEnd < 0) return null
            val sizeText = String(data, pos, lineEnd - pos, StandardCharsets.ISO_8859_1)
                .substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16)?.takeIf { it >= 0 }
                ?: return data.size - from   // malformed: take all
            pos = lineEnd + 2
            if (size == 0L) {
                // Skip optional trailers up to the terminating blank line.
                if (data.size >= pos + 2 && data[pos] == 13.toByte() && data[pos + 1] == 10.toByte()) {
                    return pos + 2 - from
                }
                val trailerEnd = indexOf(data, CRLFCRLF, pos - 2)
                return if (trailerEnd < 0) null else trailerEnd + 4 - from
            }
            // Long arithmetic: a chunk size near Int.MAX_VALUE must not wrap pos negative.
            if (pos.toLong() + size + 2 > data.size) return null
            pos += size.toInt() + 2
        }
    }

    /** Removes chunked framing. Tolerates a truncated final chunk. */
    fun dechunk(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size)
        var pos = 0
        while (pos < body.size) {
            val lineEnd = indexOf(body, byteArrayOf(13, 10), pos)
            if (lineEnd < 0) break
            val size = String(body, pos, lineEnd - pos, StandardCharsets.ISO_8859_1)
                .substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: break
            if (size == 0L) break
            val dataStart = lineEnd + 2
            val n = minOf(size, (body.size - dataStart).toLong()).toInt()
            if (n <= 0) break
            out.write(body, dataStart, n)
            if (n.toLong() < size) break   // truncated final chunk
            pos = dataStart + n + 2
        }
        return out.toByteArray()
    }

    /**
     * Decodes a Content-Encoding. Returns null for encodings we cannot decode
     * (br, zstd) or corrupt/truncated input that yields nothing.
     */
    fun decompress(body: ByteArray, contentEncoding: String?): ByteArray? {
        val encodings = contentEncoding?.split(',')?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() && it != "identity" } ?: return body
        var data = body
        // Encodings are listed in the order applied, so undo them in reverse.
        for (enc in encodings.reversed()) {
            data = when (enc) {
                "gzip", "x-gzip" -> readCapped(GZIPInputStream(data.inputStream()))
                "deflate" -> readCapped(InflaterInputStream(data.inputStream()))
                    ?: readCapped(InflaterInputStream(data.inputStream(), Inflater(true)))
                else -> null
            } ?: return null
        }
        return data
    }

    /** Reads until EOF or [MAX_DECODED_BYTES]; keeps whatever was decoded before an error. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            input.use {
                while (out.size() < MAX_DECODED_BYTES) {
                    val n = it.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, minOf(n, MAX_DECODED_BYTES - out.size()))
                }
            }
        } catch (_: Exception) {
            // Truncated capture — fall through with the partial output.
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** True if the bytes look like human-readable text (UTF-8, few control chars). */
    fun looksLikeText(bytes: ByteArray, contentType: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (listOf("text/", "json", "xml", "javascript", "x-www-form-urlencoded", "html", "csv")
                .any { ct.contains(it) }) return true
        if (bytes.isEmpty()) return true
        val sample = bytes.copyOf(minOf(bytes.size, 512))
        val control = sample.count { b ->
            val c = b.toInt() and 0xFF
            c < 0x09 || (c in 0x0E..0x1F) || c == 0x7F
        }
        return control * 20 < sample.size   // < 5 % control bytes
    }

    /**
     * Makes outgoing requests ask for encodings we can decode. Only touches a
     * chunk that starts with a request line and contains the whole header block.
     */
    fun rewriteAcceptEncoding(chunk: ByteArray): ByteArray {
        val end = headerEnd(chunk)
        if (end < 0) return chunk
        val headText = String(chunk, 0, end, StandardCharsets.ISO_8859_1)
        val method = headText.substringBefore(' ')
        if (method.isEmpty() || method.any { !it.isUpperCase() }) return chunk
        val rewritten = ACCEPT_ENCODING.replace(headText) { m ->
            "${m.groupValues[1]}gzip, deflate"
        }
        if (rewritten == headText) return chunk
        val newHead = rewritten.toByteArray(StandardCharsets.ISO_8859_1)
        return newHead + chunk.copyOfRange(end, chunk.size)
    }

    private val ACCEPT_ENCODING = Regex("(?im)^(accept-encoding:[ \\t]*)[^\\r\\n]*")

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        val last = data.size - pattern.size
        outer@ while (i <= last) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
