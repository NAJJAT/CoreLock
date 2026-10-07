package com.privacyguard.vpn.mitm

import com.privacyguard.fuzz.Fuzz
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * HttpCodec frames and decodes decrypted traffic from apps and remote servers.
 * Malformed input must never throw, and a message length must never point
 * outside the buffer the capture code slices with it.
 */
class HttpCodecFuzzTest {

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun deflate(b: ByteArray) = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(b) } }.toByteArray()

    private val json = """{"user":"alice","token":"abc123"}""".toByteArray()

    private val seeds = listOf(
        "GET /path?q=1 HTTP/1.1\r\nHost: example.com\r\nAccept-Encoding: gzip, br\r\n\r\n".toByteArray(),
        "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Length: ${json.size}\r\nContent-Type: application/json\r\n\r\n".toByteArray() + json,
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray(),
        "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 10\r\n\r\n".toByteArray() + gzip(json),
    )

    @Test fun framing() = Fuzz.run("HttpCodec framing", seeds) { data ->
        HttpCodec.parseHead(data)
        HttpCodec.messageLength(data)?.let { len ->
            assertTrue("messageLength $len outside 0..${data.size}", len in 0..data.size)
        }
        HttpCodec.rewriteAcceptEncoding(data)
    }

    /** Regression: GZIPInputStream threw from its constructor on a non-gzip body. */
    @Test fun mislabeledGzipReturnsNull() {
        assertNull(HttpCodec.decompress("not gzip".toByteArray(), "gzip"))
    }

    @Test fun bodies() = Fuzz.run("HttpCodec bodies", seeds + listOf(gzip(json), deflate(json))) { body ->
        HttpCodec.dechunk(body)
        for (encoding in listOf("gzip", "deflate", "gzip, deflate", "br", "identity", null)) {
            HttpCodec.decompress(body, encoding)?.let {
                assertTrue("decoded ${it.size}B over cap", it.size <= HttpCodec.MAX_DECODED_BYTES)
            }
        }
        HttpCodec.looksLikeText(body, null)
    }
}
