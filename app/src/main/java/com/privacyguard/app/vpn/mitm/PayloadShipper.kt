package com.privacyguard.vpn.mitm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// FIXED: Queued event model for SIEM shipping with all required fields
data class ShipEvent(
    val sessionId: String,
    val destinationIp: String,
    val destinationPort: Int,
    val ownerPackage: String?,
    val sniHostname: String?,
    val timestamp: Long,
    val direction: String,
    val protocol: String,
    val sizeBytes: Int,
    val method: String?,
    val urlPath: String?,
    val headers: Map<String, String>,
    val body: String?,
    val bodyEncoding: String,
    val piiRedacted: Boolean
)

/**
 * Buffers intercepted payload metadata and ships it to a SIEM endpoint on demand.
 *
 * Business Reason: Enterprise security teams need to receive payload metadata in
 * their SIEM systems for analysis, alerting, and compliance reporting.
 *
 * Thread Safety: Uses a thread-safe BlockingQueue; shipping runs in a background
 * coroutine scope separate from the VPN packet-processing thread.
 */
class PayloadShipper(
    private val config: MitmConfig
) {
    companion object {
        private const val TAG = "PayloadShipper"
        private const val MAX_QUEUE_SIZE = 1_000
        private const val HTTP_TIMEOUT_SECONDS = 15L
    }

    private val queue = LinkedBlockingQueue<ShipEvent>(MAX_QUEUE_SIZE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Enqueue a parsed payload for deferred SIEM shipping.
     * If the queue is full the event is dropped and a warning is logged.
     */
    fun enqueue(
        parsed: ParsedPayload,
        sessionId: String,
        destinationIp: String,
        destinationPort: Int,
        ownerPackage: String?,
        sniHostname: String?,
    ) {
        if (!config.shipToSiem && !config.writeLocalLog) return

        val event = ShipEvent(
            sessionId = sessionId,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            ownerPackage = ownerPackage,
            sniHostname = sniHostname,
            timestamp = parsed.timestamp,
            direction = parsed.direction,
            protocol = parsed.protocol.name,
            sizeBytes = parsed.sizeBytes,
            method = parsed.method,
            urlPath = parsed.urlPath,
            headers = parsed.headers,
            body = parsed.body,
            bodyEncoding = parsed.bodyEncoding,
            piiRedacted = parsed.piiRedacted
        )

        if (!queue.offer(event)) {
            Log.w(TAG, "Queue full — dropping SIEM event for $sniHostname")
        } else {
            Log.d(TAG, "Enqueued event for $sniHostname, queue size: ${queue.size}")
        }
    }

    /**
     * Drain the queue and ship all pending events to the configured SIEM endpoint.
     * No-op if SIEM shipping is disabled or endpoint is blank.
     */
    fun shipNow() {
        if (!config.shipToSiem) return
        val endpoint = config.siemEndpoint
        if (endpoint.isBlank()) return

        val events = mutableListOf<ShipEvent>()
        queue.drainTo(events)
        if (events.isEmpty()) return

        scope.launch {
            try {
                val json = buildJsonBatch(events)
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Authorization", "Bearer ${config.siemApiKey}")
                    .header("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.i(TAG, "Shipped ${events.size} events — HTTP ${response.code}")
                } else {
                    Log.e(TAG, "Failed to ship ${events.size} events — HTTP ${response.code}")
                    events.forEach { queue.offer(it) }
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "SIEM ship failed, re-queuing ${events.size} events", e)
                events.forEach { queue.offer(it) }
            }
        }
    }

    /** Number of events currently waiting to be shipped. */
    fun pendingCount(): Int = queue.size

    private fun buildJsonBatch(events: List<ShipEvent>): String = buildString {
        append("[")
        events.forEachIndexed { i, event ->
            if (i > 0) append(",")
            append("{")
            append("\"sessionId\":\"${escapeJson(event.sessionId)}\",")
            append("\"timestamp\":${event.timestamp},")
            append("\"direction\":\"${event.direction}\",")
            append("\"protocol\":\"${event.protocol}\",")
            append("\"destinationIp\":\"${escapeJson(event.destinationIp)}\",")
            append("\"destinationPort\":${event.destinationPort},")
            if (event.sniHostname != null) append("\"sniHostname\":\"${escapeJson(event.sniHostname)}\",")
            if (event.ownerPackage != null) append("\"ownerPackage\":\"${escapeJson(event.ownerPackage)}\",")
            if (event.method != null) append("\"method\":\"${escapeJson(event.method)}\",")
            if (event.urlPath != null) append("\"urlPath\":\"${escapeJson(event.urlPath)}\",")
            if (event.headers.isNotEmpty()) append("\"headers\":${buildJsonHeaders(event.headers)},")
            if (event.body != null) append("\"body\":\"${escapeJson(event.body)}\",")
            append("\"bodyEncoding\":\"${event.bodyEncoding}\",")
            append("\"sizeBytes\":${event.sizeBytes},")
            append("\"piiRedacted\":${event.piiRedacted}")
            append("}")
        }
        append("]")
    }

    private fun buildJsonHeaders(headers: Map<String, String>): String {
        return buildString {
            append("{")
            headers.entries.forEachIndexed { i, entry ->
                if (i > 0) append(",")
                append("\"${escapeJson(entry.key)}\":\"${escapeJson(entry.value)}\"")
            }
            append("}")
        }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}