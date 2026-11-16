package com.privacyguard.vpn.mitm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    val piiRedacted: Boolean,
    /** [SiemEndpoint.tag] of the endpoint set when it was queued (null: none); it is sent only there. */
    val endpointTag: String? = null,
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
class PayloadShipper @androidx.annotation.VisibleForTesting internal constructor(
    private val config: MitmConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Uploads one batch; true on a 2xx answer. A seam for tests (no network in unit tests). */
    private val sender: ((endpoint: URI, pin: SiemEndpoint.Pin, json: String) -> Boolean)? = null,
) {
    companion object {
        private const val TAG = "PayloadShipper"
        private const val MAX_QUEUE_SIZE = 1_000
        /**
         * The queue's bound in bytes (F-5). One event can carry a body of up to the payload
         * limit, so 1,000 events alone could hold about a gigabyte.
         */
        internal const val MAX_QUEUE_BYTES = 8L * 1024 * 1024
        private const val HTTP_TIMEOUT_SECONDS = 15L
        /** Ship as soon as this many events are waiting... */
        private const val SHIP_THRESHOLD = 100
        /** ...or this long after the first one was queued. */
        private const val SHIP_DELAY_MS = 60_000L
        /** After a failed upload, automatic retries wait this long, doubling up to [MAX_BACKOFF_MS]. */
        internal const val MIN_BACKOFF_MS = 30_000L
        internal const val MAX_BACKOFF_MS = 30L * 60 * 1000

        @Volatile private var INSTANCE: PayloadShipper? = null

        /**
         * One shipper per process: the VPN fills it and the payload screen ships it.
         * (Each used to build its own, so "Ship now" drained an always-empty queue
         * and nothing captured by the VPN was ever sent.)
         */
        fun getInstance(context: Context): PayloadShipper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PayloadShipper(MitmConfig(context.applicationContext)).also { shipper ->
                    INSTANCE = shipper
                    // Lives as long as the process, like the shipper: never unregistered.
                    com.privacyguard.app.data.repository.LocalDataEraser.register { shipper.clearAll() }
                }
            }

        /** A rough upper bound of an event's size in memory (UTF-16 strings plus overhead). */
        internal fun estimateBytes(e: ShipEvent): Long {
            var chars = e.sessionId.length + e.destinationIp.length + (e.ownerPackage?.length ?: 0) +
                (e.sniHostname?.length ?: 0) + (e.method?.length ?: 0) + (e.urlPath?.length ?: 0) +
                (e.body?.length ?: 0) + e.bodyEncoding.length
            for ((k, v) in e.headers) chars += k.length + v.length + 16
            return 256L + 2L * chars
        }
    }

    // Guarded by [lock]: the queue and its byte count change together.
    private val lock = Any()
    private val queue = ArrayDeque<ShipEvent>()
    private var queuedBytes = 0L
    // Guarded by [lock]: bumped by [clearAll], so a failed batch taken before it is not requeued.
    private var generation = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.privacyguard.vpn.WorkerGuard.coroutineHandler)
    private val shipScheduled = AtomicBoolean(false)
    private val shipping = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private val nextAttempt = AtomicLong(0L)

    /**
     * Sending is allowed only while all three hold: inspection on, consent valid, SIEM
     * shipping on. Checked when an event is queued AND again when it would be sent, so
     * events queued under consent are never sent after it was withdrawn or expired.
     */
    private fun shippingAllowed(): Boolean =
        config.shipToSiem && config.isEnabled && config.isConsentValid()

    private fun currentTag(): String? = SiemEndpoint.validate(config.siemEndpoint)?.let { SiemEndpoint.tag(it) }

    /**
     * Enqueue a parsed payload for deferred SIEM shipping.
     * If the queue is full (in events or bytes) the event is dropped and a warning is logged.
     */
    fun enqueue(
        parsed: ParsedPayload,
        sessionId: String,
        destinationIp: String,
        destinationPort: Int,
        ownerPackage: String?,
        sniHostname: String?,
    ) {
        // The local log is the database; this queue exists only for the SIEM.
        if (!shippingAllowed()) return

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
            piiRedacted = parsed.piiRedacted,
            endpointTag = currentTag(),
        )

        val size = estimateBytes(event)
        val queuedNow = synchronized(lock) {
            if (queue.size >= MAX_QUEUE_SIZE || queuedBytes + size > MAX_QUEUE_BYTES) -1
            else { queue.addLast(event); queuedBytes += size; queue.size }
        }
        if (queuedNow < 0) {
            Log.w(TAG, "Queue full — dropping SIEM event")
            return
        }
        if (queuedNow >= SHIP_THRESHOLD) {
            shipIfDue()
        } else if (shipScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(SHIP_DELAY_MS)
                shipScheduled.set(false)
                shipIfDue()
            }
        }
    }

    /** "Ship now" from the screen: tries at once, even while a retry is waiting. */
    fun shipNow() = ship(manual = true)

    /** Automatic trigger: does nothing until the backoff after a failed upload has passed. */
    internal fun shipIfDue() = ship(manual = false)

    private fun ship(manual: Boolean) {
        if (!shippingAllowed()) {
            // Inspection off, consent gone, or shipping off: what is queued is never sent.
            val dropped = synchronized(lock) { queue.size.also { queue.clear(); queuedBytes = 0 } }
            if (dropped > 0) Log.i(TAG, "SIEM shipping not allowed now; dropped $dropped queued events")
            return
        }
        if (!manual && clock() < nextAttempt.get()) return
        // HTTPS only: the batch holds decrypted payloads and the request carries the bearer token.
        val endpoint = SiemEndpoint.validate(config.siemEndpoint) ?: run {
            if (config.siemEndpoint.isNotBlank()) Log.w(TAG, "SIEM endpoint rejected: must be an https:// URL")
            return
        }
        // Fail closed: a malformed pin must not quietly fall back to unpinned TLS.
        val pin = SiemEndpoint.parsePin(config.siemPinSha256)
        if (pin is SiemEndpoint.Pin.Invalid) {
            Log.e(TAG, "SIEM pin rejected: expected sha256/<base64 SHA-256>; events kept queued")
            return
        }

        // One upload at a time; a failed batch goes back to the queue for the next try.
        if (!shipping.compareAndSet(false, true)) return
        val tag = SiemEndpoint.tag(endpoint)
        val events = mutableListOf<ShipEvent>()
        var foreign = 0
        var batchGeneration = 0L
        synchronized(lock) {
            for (e in queue) if (e.endpointTag == tag) events += e else foreign++
            queue.clear(); queuedBytes = 0
            batchGeneration = generation
        }
        // Queued for another endpoint (or before one was set): never sent anywhere else.
        if (foreign > 0) Log.w(TAG, "Dropped $foreign SIEM events queued for a different endpoint")
        if (events.isEmpty()) {
            shipping.set(false)
            return
        }

        scope.launch {
            try {
                val json = buildJsonBatch(events)
                val ok = try {
                    (sender ?: ::upload)(endpoint, pin, json)
                } catch (e: Exception) {
                    Log.e(TAG, "SIEM ship failed: ${e.javaClass.simpleName}")
                    false
                }
                if (ok) {
                    Log.i(TAG, "Shipped ${events.size} events")
                    failures.set(0)
                    nextAttempt.set(0L)
                } else {
                    requeue(events, batchGeneration)
                    scheduleRetry()
                }
            } finally {
                shipping.set(false)
            }
        }
    }

    /** Puts a failed batch back in front, oldest first, within the same bounds; not after a clear. */
    private fun requeue(events: List<ShipEvent>, batchGeneration: Long) = synchronized(lock) {
        if (generation != batchGeneration) return@synchronized
        for (e in events.asReversed()) {
            val size = estimateBytes(e)
            if (queue.size >= MAX_QUEUE_SIZE || queuedBytes + size > MAX_QUEUE_BYTES) continue
            queue.addFirst(e); queuedBytes += size
        }
    }

    /** Doubles the wait after each failure in a row, from [MIN_BACKOFF_MS] up to [MAX_BACKOFF_MS]. */
    private fun scheduleRetry() {
        val n = failures.incrementAndGet().coerceAtMost(20)
        val wait = (MIN_BACKOFF_MS shl (n - 1).coerceAtMost(16)).coerceAtMost(MAX_BACKOFF_MS)
        nextAttempt.set(clock() + wait)
        Log.w(TAG, "SIEM upload failed; next automatic try in ${wait / 1000} s")
        scope.launch {
            delay(wait)
            shipIfDue()
        }
    }

    private fun upload(endpoint: URI, pin: SiemEndpoint.Pin, json: String): Boolean {
        val body = json.toRequestBody("application/json".toMediaType())
        val apiKey = config.siemApiKey
        val requestBuilder = Request.Builder()
            .url(endpoint.toString())
            .post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
        SiemEndpoint.signature(json, config.siemSigningKey, apiKey)?.let {
            requestBuilder.header(SiemEndpoint.SIGNATURE_HEADER, it)
        }
        SiemEndpoint.client(endpoint.host, pin, HTTP_TIMEOUT_SECONDS, HTTP_TIMEOUT_SECONDS)
            .newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) Log.e(TAG, "SIEM upload refused — HTTP ${response.code}")
                return response.isSuccessful
            }
    }

    /** Number of events currently waiting to be shipped. */
    /**
     * "Clear all data" (N-2): the queue holds decrypted payloads, which are history too.
     * Drops it, and bumps [generation] so a batch being uploaded right now is not put back
     * if its upload fails.
     */
    fun clearAll() {
        val dropped = synchronized(lock) { queue.size.also { queue.clear(); queuedBytes = 0; generation++ } }
        failures.set(0)
        nextAttempt.set(0L)
        if (dropped > 0) Log.i(TAG, "Local data cleared; dropped $dropped queued SIEM events")
    }

    fun pendingCount(): Int = synchronized(lock) { queue.size }

    /** Estimated bytes the queued events hold. */
    @androidx.annotation.VisibleForTesting
    internal fun pendingBytes(): Long = synchronized(lock) { queuedBytes }

    @androidx.annotation.VisibleForTesting
    internal fun nextAttemptAt(): Long = nextAttempt.get()

    @androidx.annotation.VisibleForTesting
    internal fun isShipping(): Boolean = shipping.get()

    /** The queued events, oldest first, without taking them (tests check what would be sent). */
    @androidx.annotation.VisibleForTesting
    internal fun pendingEvents(): List<ShipEvent> = synchronized(lock) { queue.toList() }

    /** The JSON a batch of [events] is sent as. */
    @androidx.annotation.VisibleForTesting
    internal fun jsonFor(events: List<ShipEvent>): String = buildJsonBatch(events)

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
            // Escaped too: it can hold a Content-Encoding value the server chose.
            append("\"bodyEncoding\":\"${escapeJson(event.bodyEncoding)}\",")
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

    private fun escapeJson(s: String): String = SiemEndpoint.escapeJson(s)
}