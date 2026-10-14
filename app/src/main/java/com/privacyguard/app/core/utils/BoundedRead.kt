package com.privacyguard.app.core.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Thrown when a remote response is larger than the caller allows. */
class ResponseTooLargeException(limit: Long) : IOException("response exceeds $limit bytes")

/**
 * Reads at most [maxBytes] from a network stream. A hostile or compromised
 * server must not be able to exhaust memory by sending an endless body, so
 * anything larger fails instead of being truncated silently.
 */
fun InputStream.readBytesCapped(maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun InputStream.readUtf8Capped(maxBytes: Long): String =
    String(readBytesCapped(maxBytes), Charsets.UTF_8)

/**
 * Passes reads through until [maxBytes] have been read, then fails with
 * [ResponseTooLargeException]: for streaming a body without holding it all.
 */
class CappedInputStream(private val source: InputStream, private val maxBytes: Long) : java.io.FilterInputStream(source) {
    private var total = 0L

    private fun count(n: Int): Int {
        if (n > 0) { total += n; if (total > maxBytes) throw ResponseTooLargeException(maxBytes) }
        return n
    }

    override fun read(): Int = source.read().also { if (it >= 0) count(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = count(source.read(b, off, len))
}
