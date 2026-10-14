package com.privacyguard.vpn.mitm

import android.util.Log
import com.privacyguard.app.data.db.NOT_DECRYPTED_PREFIX
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.core.session.Session
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.interception.TlsInterception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Enterprise [TlsInterception]: redirects TLS sessions to [MitmEngine], then
 * reassembles the decrypted HTTP stream, logs each message locally and queues
 * a redacted copy for SIEM shipping.
 */
class MitmTlsInterception(
    private val mitmEngine: MitmEngine,
    private val pinningDetector: PinningDetector,
    private val mitmConfig: MitmConfig,
    private val payloadParser: PayloadParser,
    private val payloadShipper: PayloadShipper,
    private val payloadLogRepository: PayloadLogRepository,
    /** Creates the CA on first use; it is only generated once inspection is on and consented to. */
    private val ensureCa: suspend () -> Boolean,
    /** This app's UID; its own connections are never inspected. */
    private val ownUid: Int = android.os.Process.myUid(),
) : TlsInterception {

    companion object {
        private const val TAG = "MitmTlsInterception"
        /** Headers without their blank line yet: give up waiting past this. */
        private const val MAX_HEADER_BYTES = 65_536
        /** Larger messages are logged truncated; the rest of their body is skipped. */
        private const val MAX_MESSAGE_BYTES = 2_097_152
        private const val CAPTURE_QUEUE = 4_096
        private const val MESSAGE_QUEUE = 256
        private const val MESSAGE_WORKERS = 2
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val caInitStarted = AtomicBoolean(false)

    /** Chunks dropped because the capture worker fell behind (capture is best effort). */
    val droppedCaptures = AtomicLong(0)

    private class CaptureItem(val direction: String, val bytes: ByteArray, val session: Session)

    // One worker consumes every chunk in arrival order. Capture used to be launched
    // as a coroutine per packet, so chunks of one stream raced on the session's
    // buffer and were merged, dropped or reordered.
    private val captureQueue = Channel<CaptureItem>(CAPTURE_QUEUE)

    private class Message(val direction: String, val bytes: ByteArray, val session: Session)

    // Parsing and PII redaction run on their own workers, not the capture worker:
    // a message that is slow to scan must not stall reassembly for every app (V-04).
    private val messageQueue = Channel<Message>(MESSAGE_QUEUE)

    init {
        coroutineScope.launch {
            for (item in captureQueue) {
                try {
                    accumulate(item)
                } catch (e: Exception) {
                    Log.w(TAG, "Capture failed: ${e.javaClass.simpleName}")
                }
            }
        }
        repeat(MESSAGE_WORKERS) {
            coroutineScope.launch {
                for (m in messageQueue) processPayload(m.direction, m.bytes, m.session)
            }
        }
    }

    override val isEnabled: Boolean
        get() = mitmConfig.isEnabled

    override val blockQuic: Boolean
        get() = mitmConfig.isEnabled && mitmConfig.blockQuicWhenMitm

    // PrivacyGuard's own requests (blocklist and tracker updates, SIEM shipping)
    // are never inspected: the app does not trust its own CA, so they would fail.
    // Recognised by our exact UID: a package name can be imitated (V-11).
    override fun shouldIntercept(ownerUid: Int, ownerPackage: String?, sni: String): Boolean {
        if (isPrivacyGuardTraffic(ownerUid) || !mitmConfig.isEnabled || !mitmConfig.isConsentValid()) {
            return false
        }
        if (pinningDetector.isPinned(ownerPackage, sni)) return false
        if (!mitmEngine.canIntercept(sni)) {
            // Inspection is on but the CA does not exist yet: create it in the
            // background and pass this connection through meanwhile.
            if (caInitStarted.compareAndSet(false, true)) {
                coroutineScope.launch { if (!ensureCa()) caInitStarted.set(false) }
            }
            return false
        }
        return true
    }

    override fun intercept(session: Session, clientPort: Int): Int =
        mitmEngine.intercept(session, clientPort) { dir, bytes, sess -> capture(dir, bytes, sess) }

    override fun markPinned(sni: String) = pinningDetector.markAsPinned(sni)

    override fun logNotDecrypted(session: Session, sizeBytes: Int) {
        val sni = session.tlsSni ?: return
        val reason = when {
            !mitmConfig.isConsentValid() -> "mitm-off"
            pinningDetector.isBypassDomain(sni) -> "bypass"
            pinningDetector.isPinned(session.ownerPackage, sni) -> "pinned"
            else -> "failed"
        }
        coroutineScope.launch {
            payloadLogRepository.saveLog(PayloadLogEntity(
                timestamp    = System.currentTimeMillis(),
                sessionId    = session.key.toString(),
                direction    = "OUTBOUND",
                ownerPackage = session.ownerPackage,
                sniHostname  = sni,
                destinationIp   = session.key.destinationIp,
                destinationPort = session.key.destinationPort,
                protocol     = session.encryptionStatus.name,
                method = null, urlPath = null,
                headers = "{}", body = null,
                bodyEncoding = NOT_DECRYPTED_PREFIX + reason,
                sizeBytes    = sizeBytes,
                piiRedacted  = false,
                isMitmSuccess = false
            ))
        }
    }

    /**
     * Queues plaintext bytes for reassembly. Called on the packet and relay threads,
     * so it only hands the chunk over; [bytes] must not be modified afterwards.
     */
    override fun capture(direction: String, bytes: ByteArray, session: Session) {
        if (!captureQueue.trySend(CaptureItem(direction, bytes, session)).isSuccess) {
            droppedCaptures.incrementAndGet()
        }
    }

    // ── HTTP message reassembly (capture worker only) ───────────────────────

    /** Growable byte buffer for one direction of one session. */
    private class StreamBuffer {
        var data = ByteArray(4_096)
        var size = 0
        /** Total length of the message at the front, once known from its headers. */
        var expected: Int? = null
        var chunked = false
        /** Body bytes still to discard after a message was cut at [MAX_MESSAGE_BYTES]. */
        var skip = 0L

        fun append(bytes: ByteArray) {
            var from = 0
            if (skip > 0) {
                val n = minOf(skip, bytes.size.toLong()).toInt()
                skip -= n
                from = n
            }
            val len = bytes.size - from
            if (len <= 0) return
            if (size + len > data.size) data = data.copyOf(maxOf(data.size * 2, size + len))
            bytes.copyInto(data, size, from, bytes.size)
            size += len
        }

        fun view(): ByteArray = data.copyOf(size)

        fun endsWithCrlf(): Boolean = size >= 2 && data[size - 2] == 13.toByte() && data[size - 1] == 10.toByte()

        fun consume(n: Int) {
            data.copyInto(data, 0, n, size)
            size -= n
            expected = null
            chunked = false
        }
    }

    private class CaptureState {
        val outbound = StreamBuffer()
        val inbound = StreamBuffer()
    }

    // HTTP messages (especially POSTs) routinely span multiple TCP segments, so
    // bytes are buffered until a complete message (headers plus the body that
    // Content-Length or chunked framing promise) is there.
    private fun accumulate(item: CaptureItem) {
        val session = item.session
        val state = session.captureState as? CaptureState ?: CaptureState().also { session.captureState = it }
        val buffer = if (item.direction == "OUTBOUND") state.outbound else state.inbound
        buffer.append(item.bytes)
        while (buffer.size > 0) {
            val total = completeLength(buffer) ?: return
            val message = buffer.data.copyOfRange(0, total)
            buffer.consume(total)
            Log.d(TAG, "📦 HTTP ${item.direction} captured ${message.size}B")
            if (!messageQueue.trySend(Message(item.direction, message, session)).isSuccess) {
                droppedCaptures.incrementAndGet()
            }
        }
    }

    /**
     * Length of the complete message at the front of [buffer], or null while more
     * bytes are needed. Framing is worked out once per message from its headers,
     * so a large body is not re-scanned for every segment that adds to it.
     */
    private fun completeLength(buffer: StreamBuffer): Int? {
        buffer.expected?.let { expected ->
            return when {
                buffer.size >= expected -> expected
                buffer.size >= MAX_MESSAGE_BYTES -> truncateAt(buffer, expected)
                else -> null
            }
        }
        if (buffer.chunked) {
            // A chunked body can only end on a CRLF; skip the scan otherwise.
            if (!buffer.endsWithCrlf() && buffer.size < MAX_MESSAGE_BYTES) return null
            return HttpCodec.messageLength(buffer.view())
                ?: if (buffer.size >= MAX_MESSAGE_BYTES) buffer.size else null
        }

        val view = buffer.view()
        val head = HttpCodec.parseHead(view)
            ?: return if (buffer.size > MAX_HEADER_BYTES) buffer.size else null
        if (HttpCodec.isChunked(head)) {
            buffer.chunked = true
            return HttpCodec.messageLength(view)
        }
        val length = head.header("Content-Length")?.trim()?.toLongOrNull()
            ?: return HttpCodec.messageLength(view)   // no body, or malformed: as before
        if (length < 0) return buffer.size
        val expected = (head.bodyOffset + length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        buffer.expected = expected
        return completeLength(buffer)
    }

    /** Logs what we have of an oversized message and discards the rest of its body. */
    private fun truncateAt(buffer: StreamBuffer, expected: Int): Int {
        buffer.skip = (expected - buffer.size).toLong()
        return buffer.size
    }

    // ── Payload parse + save ─────────────────────────────────────────────────
    private fun processPayload(direction: String, bytes: ByteArray, session: Session) {
        try {
            if (isPrivacyGuardTraffic(session.ownerUid)) return

            val parsed = payloadParser.parse(bytes, direction, session)
            val redacted = payloadParser.redactForExport(parsed)
            // "Redact PII" (on by default, MDM key mitm_redact_pii) applies to the
            // local log as well: credentials and personal data are masked before
            // they reach the database. Off, the inspector shows exactly what left
            // the phone. Anything leaving the device is always redacted.
            val stored = if (mitmConfig.redactPii) redacted else parsed

            val headersJson = try {
                Json.encodeToString(stored.headers)
            } catch (e: Exception) {
                "{}"
            }

            val entity = PayloadLogEntity(
                timestamp = stored.timestamp,
                sessionId = session.key.toString(),
                direction = stored.direction,
                ownerPackage = session.ownerPackage,
                sniHostname = session.tlsSni,
                destinationIp = session.key.destinationIp,
                destinationPort = session.key.destinationPort,
                protocol = stored.protocol.name,
                method = stored.method,
                urlPath = stored.urlPath,
                headers = headersJson,
                body = stored.body,
                bodyEncoding = stored.bodyEncoding,
                sizeBytes = stored.sizeBytes,
                piiRedacted = stored.piiRedacted,
                isMitmSuccess = true
            )

            coroutineScope.launch {
                payloadLogRepository.saveLog(entity)
            }

            payloadShipper.enqueue(
                redacted,
                session.key.toString(),
                session.key.destinationIp,
                session.key.destinationPort,
                session.ownerPackage,
                session.tlsSni
            )

            session.payloadCount.incrementAndGet()
            Log.d(TAG, "MITM payload processed: ${parsed.protocol} ${parsed.sizeBytes} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MITM payload: ${e.javaClass.simpleName}")
        }
    }

    /** Only our own UID: an app named "com.privacyguard.app.x" is inspected and logged like any other. */
    internal fun isPrivacyGuardTraffic(ownerUid: Int): Boolean = ownerUid == ownUid
}
