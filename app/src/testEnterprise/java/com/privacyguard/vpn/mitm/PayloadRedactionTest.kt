package com.privacyguard.vpn.mitm

import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [PayloadParser.redactForExport] leaves of an inspected message (plan task 0.3): the
 * SIEM always gets this, and the local log too while "Redact PII" is on (the default).
 */
class PayloadRedactionTest {

    private val parser = PayloadParser(PiiRedactor())
    private val session = Session(SessionKey.of("10.0.0.2", 40_000, "93.184.216.34", 443, 6), ownerUid = 10_123, ownerPackage = "com.example")

    private fun redacted(message: String): ParsedPayload =
        parser.redactForExport(parser.parse(message.toByteArray(Charsets.ISO_8859_1), "OUTBOUND", session))

    /** Everything a redacted payload could carry off the phone, as one text. */
    private fun everything(p: ParsedPayload) =
        listOf(p.urlPath, p.body, p.bodyEncoding, p.headers.toString(), String(p.raw, Charsets.ISO_8859_1)).joinToString("\n")

    @Test
    fun query_values_become_placeholders_and_keys_stay() {
        val p = redacted("GET /search?q=divorce+lawyer&lang=en&token=s3cr3t#section HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals("/search?q=[REDACTED]&lang=[REDACTED]&token=[REDACTED]", p.urlPath)
    }

    @Test
    fun personal_data_in_the_path_and_bare_query_items_are_masked() {
        val p = redacted("GET /users/alice@example.org/profile?alice@example.org&=x&k HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals("/users/[EMAIL]/profile?[REDACTED]&[REDACTED]&[REDACTED]", p.urlPath)
    }

    /**
     * Review finding (2026-11-07): a key was kept as it was, so data put in the key position
     * (an address, a JSON object, an encoded address) reached the SIEM. Only a key that looks
     * like a field name and that the PII patterns leave alone is kept.
     */
    @Test
    fun personal_data_in_query_and_form_keys_is_masked_too() {
        val p = redacted("GET /c?john.doe@example.com=1&{\"phone\":\"+15551234567\"}=x&bob%40example.net=2&user_id=7 HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals("/c?[REDACTED]&[REDACTED]&[REDACTED]&user_id=[REDACTED]", p.urlPath)
        val body = "carol@example.com=1&page=2"
        val f = redacted("POST /f HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.length}\r\n\r\n$body")
        assertEquals("[REDACTED]&page=[REDACTED]", f.body)
        for (leak in listOf("john.doe", "5551234567", "bob%40", "carol@")) assertFalse(leak, everything(p).contains(leak) || everything(f).contains(leak))
    }

    @Test
    fun a_form_body_keeps_its_keys_only() {
        val body = "username=alice&password=hunter2&note=Main+Street+12"
        val p = redacted("POST /login HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/x-www-form-urlencoded; charset=UTF-8\r\n" +
            "Content-Length: ${body.length}\r\n\r\n$body")
        assertEquals("username=[REDACTED]&password=[REDACTED]&note=[REDACTED]", p.body)
        assertFalse(everything(p).contains("hunter2"))
        assertFalse(everything(p).contains("Main+Street"))
    }

    @Test
    fun a_binary_body_is_reduced_to_type_and_size() {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val head = "POST /upload HTTP/1.1\r\nHost: example.com\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\n\r\n"
        val raw = head.toByteArray(Charsets.ISO_8859_1) + bytes
        val parsed = parser.parse(raw, "OUTBOUND", session)
        assertEquals("base64", parsed.bodyEncoding)   // control: the parser keeps it as base64
        val p = parser.redactForExport(parsed)
        assertEquals("[image/png body, 300 bytes, not stored]", p.body)
        assertEquals(PayloadParser.REDACTED_ENCODING, p.bodyEncoding)
        assertEquals(0, p.raw.size)
        assertFalse(everything(p).contains(parsed.body!!.take(20)))
    }

    @Test
    fun multipart_and_undecodable_bodies_are_reduced_too() {
        val multipart = "--b\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\nA1234567\r\n--b--\r\n"
        val p = redacted("POST /f HTTP/1.1\r\nHost: example.com\r\nContent-Type: multipart/form-data; boundary=b\r\n" +
            "Content-Length: ${multipart.length}\r\n\r\n$multipart")
        assertFalse(everything(p).contains("A1234567"))
        assertEquals(PayloadParser.REDACTED_ENCODING, p.bodyEncoding)

        val gz = redacted("POST /g HTTP/1.1\r\nHost: example.com\r\nContent-Encoding: br\"x\r\nContent-Length: 4\r\n\r\nabcd")
        assertEquals(PayloadParser.REDACTED_ENCODING, gz.bodyEncoding)
        assertFalse(gz.body!!.contains("abcd"))
    }

    @Test
    fun urls_in_headers_lose_their_query() {
        val p = redacted("GET / HTTP/1.1\r\nHost: example.com\r\nReferer: https://shop.example/cart?email=bob@example.net&id=99\r\n\r\n")
        assertEquals("https://shop.example/cart?email=[REDACTED]&id=[REDACTED]", PayloadRedaction.header(p.headers, "referer"))
    }

    @Test
    fun http2_frames_are_not_kept_as_hex() {
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray() + "secret-frame-bytes".toByteArray()
        val p = parser.redactForExport(parser.parse(preface, "OUTBOUND", session))
        assertFalse(everything(p).contains("73 65 63"))   // "sec" as hex
        assertTrue(p.headers["raw_hex"]!!.startsWith("[application/http2 body"))
    }

    @Test
    fun text_bodies_keep_the_existing_pattern_redaction() {
        val body = "{\"password\":\"hunter2\",\"mail\":\"carol@example.com\",\"items\":3}"
        val p = redacted("POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertFalse(everything(p).contains("hunter2"))
        assertFalse(everything(p).contains("carol@example.com"))
        assertTrue(p.body!!.contains("\"items\":3"))
    }

    /** Attacker-controlled input: linear time, also for inputs built to be worst cases. */
    @Test
    fun redaction_is_linear_in_the_input() {
        val n = 1_000_000
        val inputs = listOf("&".repeat(n), "=".repeat(n), "a=".repeat(n / 2), "a".repeat(n), "?" + "k=v&".repeat(n / 4))
        for (s in inputs) {
            val start = System.nanoTime()
            PayloadRedaction.form(s)
            PayloadRedaction.url("/p?$s") { it }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("${s.take(8)}… took $ms ms", ms < 2_000)
        }
    }
}
