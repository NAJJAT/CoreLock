package com.privacyguard.core.tls

import com.privacyguard.app.core.utils.ResponseTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Regression for V-12: the crt.sh client caps the body, refuses redirects and uses the PSL. */
class CtMonitorHardeningTest {

    private fun monitor() = CtMonitor(runLoop = false) { _, _ -> }.also { it.setEnabled(true) }

    private fun httpGet(m: CtMonitor, url: String): Result<String?> {
        val f = CtMonitor::class.java.getDeclaredMethod("httpGet", String::class.java).apply { isAccessible = true }
        return runCatching { f.invoke(m, url) as String? }.recoverCatching { throw (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it }
    }

    /** One-shot HTTP server answering [head] then [bodySize] bytes. */
    private fun server(head: String, bodySize: Int): ServerSocket {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                ss.accept().use { s ->
                    s.getInputStream().read(ByteArray(4096))
                    val out = s.getOutputStream()
                    out.write(head.toByteArray())
                    val chunk = ByteArray(64 * 1024) { '['.code.toByte() }
                    var left = bodySize
                    while (left > 0) { val n = minOf(left, chunk.size); out.write(chunk, 0, n); left -= n }
                    out.flush()
                }
            }
        }
        return ss
    }

    @Test fun oversized_body_is_refused() {
        val size = (CtMonitor.MAX_RESPONSE_BYTES + 1024).toInt()
        val ss = server("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n", size)
        val r = httpGet(monitor(), "http://127.0.0.1:${ss.localPort}/")
        ss.close()
        assertTrue("got ${r.exceptionOrNull()}", r.exceptionOrNull() is ResponseTooLargeException)
    }

    @Test fun redirect_is_not_followed() {
        val ss = server("HTTP/1.1 302 Found\r\nLocation: http://evil.example/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", 0)
        val r = httpGet(monitor(), "http://127.0.0.1:${ss.localPort}/")
        ss.close()
        assertNull(r.getOrThrow())
    }

    @Test fun watched_domain_is_the_registrable_domain() {
        val apex = CtMonitor::class.java.getDeclaredMethod("apexDomain", String::class.java).apply { isAccessible = true }
        val m = monitor()
        assertEquals("bbc.co.uk", apex.invoke(m, "news.bbc.co.uk"))
        assertEquals("example.com", apex.invoke(m, "a.b.example.com"))
    }

    @Test fun query_is_url_encoded() {
        assertTrue(CtMonitor.queryUrl("a&b=c.example").contains("a%26b%3Dc.example"))
    }
}
