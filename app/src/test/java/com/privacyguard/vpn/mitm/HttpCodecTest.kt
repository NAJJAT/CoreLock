package com.privacyguard.vpn.mitm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class HttpCodecTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(data) } }.toByteArray()

    @Test
    fun `message length waits for complete content-length body`() {
        val msg = bytes("POST /a HTTP/1.1\r\nContent-Length: 5\r\n\r\nhel")
        assertNull(HttpCodec.messageLength(msg))
        val full = bytes("POST /a HTTP/1.1\r\nContent-Length: 5\r\n\r\nhelloEXTRA")
        assertEquals(full.size - 5, HttpCodec.messageLength(full))
    }

    @Test
    fun `message length handles header-only and incomplete headers`() {
        assertNull(HttpCodec.messageLength(bytes("GET / HTTP/1.1\r\nHost: x")))
        val get = bytes("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(get.size, HttpCodec.messageLength(get))
    }

    @Test
    fun `chunked message length and dechunk`() {
        val body = "4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\n\r\n"
        val head = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        val partial = bytes(head + body.dropLast(4))
        assertNull(HttpCodec.messageLength(partial))
        val full = bytes(head + body + "HTTP/1.1 next")
        assertEquals(head.length + body.length, HttpCodec.messageLength(full))
        assertEquals("Wikipedia", String(HttpCodec.dechunk(bytes(body))))
    }

    @Test
    fun `chunked message with trailers`() {
        val msg = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\nX-Trailer: 1\r\n\r\n"
        assertEquals(msg.length, HttpCodec.messageLength(bytes(msg)))
    }

    @Test
    fun `decompresses gzip and deflate`() {
        val text = "{\"lat\": 52.370216}".repeat(20).toByteArray()
        assertArrayEquals(text, HttpCodec.decompress(gzip(text), "gzip"))
        val zlib = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(text) } }.toByteArray()
        assertArrayEquals(text, HttpCodec.decompress(zlib, "deflate"))
    }

    @Test
    fun `truncated gzip returns partial output`() {
        val text = ByteArray(50_000) { ('a' + it % 26).code.toByte() }
        val gz = gzip(text)
        val partial = HttpCodec.decompress(gz.copyOf(gz.size - 10), "gzip")
        assertTrue(partial != null && partial.isNotEmpty())
    }

    @Test
    fun `unsupported encodings return null and identity passes through`() {
        assertNull(HttpCodec.decompress(byteArrayOf(1, 2, 3), "br"))
        val raw = byteArrayOf(1, 2, 3)
        assertSame(raw, HttpCodec.decompress(raw, null))
        assertSame(raw, HttpCodec.decompress(raw, "identity"))
    }

    @Test
    fun `text detection`() {
        assertTrue(HttpCodec.looksLikeText(bytes("hello world"), null))
        assertTrue(HttpCodec.looksLikeText(byteArrayOf(0, 1, 2), "application/json"))
        assertFalse(HttpCodec.looksLikeText(ByteArray(100) { (it % 8).toByte() }, "image/png"))
    }

    @Test
    fun `rewrites accept-encoding in requests only`() {
        val req = bytes("GET / HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip, deflate, br, zstd\r\n\r\nBODY")
        assertEquals(
            "GET / HTTP/1.1\r\nHost: x\r\nAccept-Encoding: gzip, deflate\r\n\r\nBODY",
            String(HttpCodec.rewriteAcceptEncoding(req), Charsets.ISO_8859_1)
        )
        val resp = bytes("HTTP/1.1 200 OK\r\nAccept-Encoding: br\r\n\r\n")
        assertSame(resp, HttpCodec.rewriteAcceptEncoding(resp))
        val partialHead = bytes("GET / HTTP/1.1\r\nAccept-Encoding: br")
        assertSame(partialHead, HttpCodec.rewriteAcceptEncoding(partialHead))
        val binary = byteArrayOf(0x16, 0x03, 0x01)
        assertSame(binary, HttpCodec.rewriteAcceptEncoding(binary))
    }
}
