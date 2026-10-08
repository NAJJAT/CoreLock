package com.privacyguard.vpn.mitm

import android.util.Log
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.data.db.NOT_DECRYPTED_PREFIX
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.core.session.Session
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.interception.TlsInterception
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
) : TlsInterception {

    companion object {
        private const val TAG = "MitmTlsInterception"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override val isEnabled: Boolean
        get() = mitmConfig.isEnabled

    override val blockQuic: Boolean
        get() = mitmConfig.isEnabled && mitmConfig.blockQuicWhenMitm

    // PrivacyGuard's own requests (blocklist and tracker updates, SIEM shipping)
    // are never inspected: the app does not trust its own CA, so they would fail.
    override fun shouldIntercept(ownerPackage: String?, sni: String): Boolean =
        ownerPackage != com.privacyguard.app.BuildConfig.APPLICATION_ID &&
            mitmConfig.isEnabled &&
            mitmConfig.isConsentValid() &&
            !pinningDetector.isPinned(ownerPackage, sni)

    override fun intercept(session: Session, clientPort: Int): Int =
        mitmEngine.intercept(session, clientPort) { dir, bytes, sess -> capture(dir, bytes, sess) }

    override fun markPinned(sni: String) = pinningDetector.markAsPinned(sni)

    override fun logNotDecrypted(session: Session, sizeBytes: Int) {
        val sni = session.tlsSni ?: return
        val reason = when {
            !mitmConfig.isConsentValid() -> "mitm-off"
            session.ownerPackage in PinningDetector.END_TO_END_PACKAGES -> "e2e"
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

    // ── TCP segment accumulator ──────────────────────────────────────────────
    // HTTP messages (especially POSTs) routinely span multiple TCP segments:
    //   segment 1 → request line + headers
    //   segment 2 → body
    // This appends to a per-session buffer and only fires processPayload once a
    // complete HTTP message (headers + Content-Length bytes of body) is ready.
    override fun capture(direction: String, bytes: ByteArray, session: Session) {
        val combined = if (direction == "OUTBOUND") {
            session.httpOutBytes + bytes
        } else {
            session.httpInBytes + bytes
        }

        if (HttpCodec.headerEnd(combined) < 0) {
            // Haven't received complete headers yet — keep buffering (cap at 64 KB).
            if (combined.size <= 65_536) {
                if (direction == "OUTBOUND") session.httpOutBytes = combined
                else session.httpInBytes = combined
            }
            return
        }

        // Content-Length or chunked framing decides where the message ends.
        val messageLength = HttpCodec.messageLength(combined)
        if (messageLength == null && combined.size <= 2_097_152) {
            // Body is still arriving in later segments — keep buffering.
            if (direction == "OUTBOUND") session.httpOutBytes = combined
            else session.httpInBytes = combined
            return
        }
        val totalExpected = messageLength ?: combined.size   // over the cap: parse what we have

        // Complete message — parse it.
        val toParse = if (combined.size >= totalExpected) combined.copyOfRange(0, totalExpected) else combined
        coroutineScope.launch { processPayload(direction, toParse, session) }
        Log.d(TAG, "📦 HTTP $direction captured ${toParse.size}B (${session.tlsSni ?: session.key.destinationIp})")

        // Clear buffer; carry over any bytes that belong to the next message.
        val leftover = if (combined.size > totalExpected)
            combined.copyOfRange(totalExpected, combined.size) else ByteArray(0)
        if (direction == "OUTBOUND") session.httpOutBytes = leftover
        else session.httpInBytes = leftover
    }

    // ── Payload parse + save ─────────────────────────────────────────────────
    private fun processPayload(direction: String, bytes: ByteArray, session: Session) {
        try {
            if (isPrivacyGuardTraffic(session.ownerPackage)) return

            val parsed = payloadParser.parse(bytes, direction, session)

            val headersJson = try {
                Json.encodeToString(parsed.headers)
            } catch (e: Exception) {
                "{}"
            }

            val entity = PayloadLogEntity(
                timestamp = parsed.timestamp,
                sessionId = session.key.toString(),
                direction = parsed.direction,
                ownerPackage = session.ownerPackage,
                sniHostname = session.tlsSni,
                destinationIp = session.key.destinationIp,
                destinationPort = session.key.destinationPort,
                protocol = parsed.protocol.name,
                method = parsed.method,
                urlPath = parsed.urlPath,
                headers = headersJson,
                body = parsed.body,
                bodyEncoding = parsed.bodyEncoding,
                sizeBytes = parsed.sizeBytes,
                piiRedacted = parsed.piiRedacted,
                isMitmSuccess = true
            )

            coroutineScope.launch {
                payloadLogRepository.saveLog(entity)
            }

            // The local log keeps the full payload; anything leaving the device is redacted.
            payloadShipper.enqueue(
                payloadParser.redactForExport(parsed),
                session.key.toString(),
                session.key.destinationIp,
                session.key.destinationPort,
                session.ownerPackage,
                session.tlsSni
            )

            session.payloadCount.incrementAndGet()
            Log.d(TAG, "MITM payload processed: ${parsed.protocol} ${parsed.sizeBytes} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MITM payload", e)
        }
    }

    private fun isPrivacyGuardTraffic(ownerPackage: String?): Boolean {
        return ownerPackage == BuildConfig.APPLICATION_ID ||
                ownerPackage?.startsWith("com.privacyguard.app") == true
    }
}
