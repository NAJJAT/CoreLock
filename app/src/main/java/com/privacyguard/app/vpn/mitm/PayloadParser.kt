package com.privacyguard.vpn.mitm

import android.util.Log
import com.privacyguard.core.session.Session
import java.nio.charset.StandardCharsets
import java.util.*

// FIXED: Protocol enum to match TcpForwarder expectations
enum class Protocol {
    HTTP1, HTTP2, GRPC, WEBSOCKET, UNKNOWN
}

// FIXED: ParsedPayload data class with fields matching TcpForwarder expectations
data class ParsedPayload(
    val raw: ByteArray,                           // ADDED: required by TcpForwarder
    val protocol: Protocol,                       // CHANGED: Protocol type instead of ProtocolType
    val direction: String,
    val method: String?,
    val urlPath: String?,
    val headers: Map<String, String>,
    val body: String?,
    val bodyEncoding: String,
    val sizeBytes: Int,
    val piiRedacted: Boolean,
    val timestamp: Long
) {
    // ADDED: equals and hashCode for ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedPayload

        if (!raw.contentEquals(other.raw)) return false
        if (protocol != other.protocol) return false
        if (direction != other.direction) return false
        if (method != other.method) return false
        if (urlPath != other.urlPath) return false
        if (headers != other.headers) return false
        if (body != other.body) return false
        if (bodyEncoding != other.bodyEncoding) return false
        if (piiRedacted != other.piiRedacted) return false
        if (sizeBytes != other.sizeBytes) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + protocol.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + (method?.hashCode() ?: 0)
        result = 31 * result + (urlPath?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        result = 31 * result + bodyEncoding.hashCode()
        result = 31 * result + piiRedacted.hashCode()
        result = 31 * result + sizeBytes
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

// FIXED: Parses raw TLS-decrypted bytes into structured ParsedPayload
class PayloadParser(
    private val piiRedactor: PiiRedactor
) {
    companion object {
        private const val TAG = "PayloadParser"
        private const val MAX_BODY_SIZE = 65_536

        private val HTTP_METHODS = setOf(
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE"
        )

        private val HTTP2_PREFACE = byteArrayOf(
            0x50, 0x52, 0x49, 0x20, 0x2A, 0x20, 0x48, 0x54, 0x54, 0x50,
            0x2F, 0x32, 0x2E, 0x30, 0x0D, 0x0A, 0x0D, 0x0A, 0x53, 0x4D, 0x0D, 0x0A, 0x0D, 0x0A
        )
    }

    fun parse(bytes: ByteArray, direction: String, session: Session): ParsedPayload {
        return try {
            if (isHttp1Traffic(bytes)) {
                parseHttp1(bytes, direction, session)
            } else if (isHttp2Traffic(bytes)) {
                parseHttp2(bytes, direction, session)
            } else {
                ParsedPayload(
                    raw = bytes,
                    timestamp = System.currentTimeMillis(),
                    direction = direction,
                    protocol = Protocol.UNKNOWN,
                    method = null,
                    urlPath = null,
                    headers = emptyMap(),
                    body = null,
                    bodyEncoding = "binary",
                    sizeBytes = bytes.size,
                    piiRedacted = false,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Payload parse failed: ${e.message}")
            ParsedPayload(
                raw = bytes,
                timestamp = System.currentTimeMillis(),
                direction = direction,
                protocol = Protocol.UNKNOWN,
                method = null,
                urlPath = null,
                headers = emptyMap(),
                body = null,
                bodyEncoding = "binary",
                sizeBytes = bytes.size,
                piiRedacted = false,
            )
        }
    }

    private fun isHttp1Traffic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val prefix = String(bytes.copyOfRange(0, minOf(8, bytes.size)), StandardCharsets.ISO_8859_1)
        return HTTP_METHODS.any { prefix.startsWith(it) } || prefix.startsWith("HTTP/")
    }

    private fun isHttp2Traffic(bytes: ByteArray): Boolean {
        if (bytes.size < HTTP2_PREFACE.size) return false
        return bytes.sliceArray(0 until HTTP2_PREFACE.size).contentEquals(HTTP2_PREFACE)
    }

    /**
     * Parses one complete HTTP/1.x message. Bodies are de-chunked and
     * decompressed so they are readable. Nothing is redacted here: the local
     * inspector shows exactly what left the phone. Use [redactForExport] before
     * sending a payload anywhere else.
     *
     * `piiRedacted` is set when [LeakDetector] finds personal data; the leak
     * types are stored under the [LeakDetector.LEAKS_HEADER] pseudo-header.
     */
    private fun parseHttp1(bytes: ByteArray, direction: String, session: Session): ParsedPayload {
        val head = HttpCodec.parseHead(bytes)
            ?: return ParsedPayload(
                raw = bytes, timestamp = System.currentTimeMillis(), direction = direction,
                protocol = Protocol.HTTP1, method = null, urlPath = null, headers = emptyMap(),
                body = String(bytes, StandardCharsets.ISO_8859_1).take(MAX_BODY_SIZE),
                bodyEncoding = "UTF-8", sizeBytes = bytes.size, piiRedacted = false,
            )

        val parts = head.startLine.split(" ")
        val method = parts.getOrNull(0)?.takeIf { it in HTTP_METHODS }
        val urlPath = if (method != null) parts.getOrNull(1) else null

        // Body: strip chunked framing, then undo Content-Encoding.
        var bodyBytes = bytes.copyOfRange(minOf(head.bodyOffset, bytes.size), bytes.size)
        if (HttpCodec.isChunked(head)) bodyBytes = HttpCodec.dechunk(bodyBytes)
        val contentEncoding = head.header("Content-Encoding")
        val decoded = if (bodyBytes.isEmpty()) bodyBytes
            else HttpCodec.decompress(bodyBytes, contentEncoding)

        val (body, bodyEncoding) = when {
            bodyBytes.isEmpty() -> null to "none"
            decoded == null ->
                "[${contentEncoding ?: "unknown"}-compressed body, ${bodyBytes.size} bytes — not decodable]" to
                    "compressed:${contentEncoding ?: "unknown"}"
            HttpCodec.looksLikeText(decoded, head.header("Content-Type")) ->
                String(decoded, StandardCharsets.UTF_8).take(MAX_BODY_SIZE) to "UTF-8"
            else ->
                kotlin.io.encoding.Base64.encode(decoded.copyOf(minOf(decoded.size, MAX_BODY_SIZE))) to "base64"
        }

        val textBody = body?.takeIf { bodyEncoding == "UTF-8" }
        val leaks = if (direction == "OUTBOUND")
            LeakDetector.detect(urlPath?.let { decodeUrl(it) }, head.headers, textBody?.let { decodeUrl(it) })
        else emptySet()

        val headers = if (leaks.isEmpty()) head.headers
            else head.headers + (LeakDetector.LEAKS_HEADER to LeakDetector.encode(leaks))

        return ParsedPayload(
            raw = bytes,
            timestamp = System.currentTimeMillis(),
            direction = direction,
            protocol = Protocol.HTTP1,
            method = method,
            urlPath = urlPath,
            headers = headers,
            body = body,
            bodyEncoding = bodyEncoding,
            sizeBytes = bytes.size,
            piiRedacted = leaks.isNotEmpty(),
        )
    }

    /** Copy with credentials and personal data masked, for shipping off-device. */
    fun redactForExport(payload: ParsedPayload): ParsedPayload = payload.copy(
        headers = piiRedactor.redactHeaders(payload.headers),
        body = payload.body?.let { if (payload.bodyEncoding == "UTF-8") piiRedactor.redact(it) else it },
    )

    private fun decodeUrl(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    private fun parseHttp2(bytes: ByteArray, direction: String, session: Session): ParsedPayload {
        val hexPreview = bytes.take(100).joinToString(" ") { "%02x".format(it) }

        return ParsedPayload(
            raw = bytes,
            timestamp = System.currentTimeMillis(),
            direction = direction,
            protocol = Protocol.HTTP2,
            method = null,
            urlPath = null,
            headers = mapOf("raw_hex" to hexPreview),
            body = null,
            bodyEncoding = "BINARY",
            sizeBytes = bytes.size,
            piiRedacted = false,
        )
    }
}