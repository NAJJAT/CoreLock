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
        private const val MAX_BODY_SIZE = 8192

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

    private fun parseHttp1(bytes: ByteArray, direction: String, session: Session): ParsedPayload {
        val raw = String(bytes, StandardCharsets.UTF_8)
        val lines = raw.split("\r\n").ifEmpty { raw.split("\n") }

        // Parse request/response line
        val firstLine = lines.firstOrNull() ?: ""
        val parts = firstLine.split(" ")
        val method = parts.getOrNull(0)?.takeIf { it in HTTP_METHODS }
        val urlPath = parts.getOrNull(1)

        // Parse headers
        val headers = mutableMapOf<String, String>()
        var bodyStartIndex = lines.size
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                bodyStartIndex = i + 1
                break
            }
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).trim()] = line.substring(colonIdx + 1).trim()
            }
        }

        // Parse body
        var rawBody: String? = null
        if (bodyStartIndex < lines.size) {
            rawBody = lines.drop(bodyStartIndex).joinToString("\n").take(MAX_BODY_SIZE)
        }

        // Redact PII
        val redactedHeaders = piiRedactor.redactHeaders(headers)
        val redactedBody = rawBody?.let { piiRedactor.redact(it) }
        val piiRedacted = redactedHeaders != headers || redactedBody != rawBody

        return ParsedPayload(
            raw = bytes,
            timestamp = System.currentTimeMillis(),
            direction = direction,
            protocol = Protocol.HTTP1,
            method = method,
            urlPath = urlPath,
            headers = redactedHeaders,
            body = redactedBody,
            bodyEncoding = "UTF-8",
            sizeBytes = bytes.size,
            piiRedacted = piiRedacted,
        )
    }

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