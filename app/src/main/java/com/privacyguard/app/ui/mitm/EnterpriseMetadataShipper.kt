package com.privacyguard.app.ui.mitm

import android.content.Context
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.SiemEndpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Represents a single metadata item for payload inspection
 */
data class PayloadMetadataItem(
    val packageName: String,
    val appName: String,
    val hostname: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val direction: String,
    val preview: String,
    val sizeBytes: Int,
    val timestamp: Long,
    val wasBlocked: Boolean,
    val encryptionLabel: String
)

/**
 * Ships enterprise metadata batches to a configured SIEM endpoint or persists
 * them locally for later export.
 *
 * Business reason:
 * Enterprise operators need a safe way to forward metadata-only connection
 * events to internal tooling without embedding transport logic directly inside
 * the screen layer.
 *
 * Thread safety:
 * This class is stateless aside from filesystem interaction. Callers may use
 * it from multiple coroutines; file names are timestamp-based and writes are
 * scoped per call.
 */
class EnterpriseMetadataShipper(
    context: Context,
    private val config: MitmConfig,
) {
    private val appContext = context.applicationContext
    private val queueDir = File(appContext.filesDir, "enterprise_payload_batches").apply { mkdirs() }

    /**
     * Counts locally queued metadata events waiting for shipment.
     */
    fun pendingEventCount(): Int {
        return queueDir
            .listFiles()
            .orEmpty()
            .sumOf { parseCountFromFileName(it.name) }
    }

    /**
     * Returns lightweight descriptions of queued local metadata batches.
     */
    fun queuedBatches(): List<QueuedBatchInfo> {
        return queueDir.listFiles()
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                QueuedBatchInfo(
                    fileName = file.name,
                    eventCount = parseCountFromFileName(file.name),
                    sizeBytes = file.length(),
                    createdAt = file.lastModified(),
                )
            }
    }

    /**
     * Clears all locally queued metadata batches.
     */
    fun clearPending(): Int {
        var removed = 0
        queueDir.listFiles().orEmpty().forEach { file ->
            if (file.delete()) {
                removed++
            }
        }
        return removed
    }

    /**
     * Sends the provided metadata items immediately.
     *
     * If SIEM shipping is unavailable or fails, the batch is written to local
     * storage when local logging is enabled.
     */
    fun shipNow(items: List<PayloadMetadataItem>): ShipResult {
        if (items.isEmpty()) {
            return ShipResult(success = true, shippedCount = 0, message = "No metadata to ship.")
        }

        val payload = buildJson(items)
        val endpoint = SiemEndpoint.validate(config.siemEndpoint)
        val apiKey = config.siemApiKey.trim()
        // Fail closed: a malformed pin must not quietly fall back to unpinned TLS.
        val pin = SiemEndpoint.parsePin(config.siemPinSha256)

        if (config.shipToSiem && endpoint != null && apiKey.isNotBlank() && pin !is SiemEndpoint.Pin.Invalid) {
            val current = sendBatch(endpoint, apiKey, pin, payload)
            if (current.success) {
                flushPending(endpoint, apiKey, pin)
                return current.copy(message = "Shipped ${items.size} metadata events.")
            }
        }

        return if (config.writeLocalLog) {
            persistPending(items.size, payload, endpoint?.let(SiemEndpoint::tag) ?: UNBOUND_TAG)
            ShipResult(
                success = false,
                shippedCount = 0,
                message = when {
                    !config.shipToSiem -> "SIEM shipping is off. Metadata saved locally."
                    pin is SiemEndpoint.Pin.Invalid -> "SIEM certificate pin is invalid. Metadata saved locally."
                    else -> "SIEM shipping unavailable. Metadata saved locally."
                },
            )
        } else {
            ShipResult(
                success = false,
                shippedCount = 0,
                message = "Metadata was not shipped. Configure SIEM shipping or enable local logging.",
            )
        }
    }

    /**
     * Sends queued batches, but only those queued for this same endpoint. A batch
     * saved for one SIEM must not leak to a different one after a config change;
     * unbound and legacy (untagged) batches stay local for export or clearing.
     */
    private fun flushPending(endpoint: URI, apiKey: String, pin: SiemEndpoint.Pin) {
        val tag = SiemEndpoint.tag(endpoint)
        queueDir.listFiles()
            .orEmpty()
            .filter { parseTagFromFileName(it.name) == tag }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                val batch = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
                val result = sendBatch(endpoint, apiKey, pin, batch)
                if (result.success) {
                    file.delete()
                }
            }
    }

    private fun sendBatch(endpoint: URI, apiKey: String, pin: SiemEndpoint.Pin, payload: String): ShipResult {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint.toString().trimEnd('/') + "/api/v1/events")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .apply {
                    // Signed only with the separate signing key; HMAC under the bearer token adds nothing.
                    SiemEndpoint.signature(payload, config.siemSigningKey, apiKey)?.let {
                        header(SiemEndpoint.SIGNATURE_HEADER, it)
                    }
                }
                .build()
            SiemEndpoint.client(endpoint.host, pin, CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS)
                .newCall(request)
                .execute()
                .use { response ->
                    if (response.isSuccessful) {
                        ShipResult(true, parseCountFromPayload(payload), "Shipped successfully.")
                    } else {
                        ShipResult(false, 0, "SIEM returned HTTP ${response.code}.")
                    }
                }
        }.getOrElse { error ->
            ShipResult(false, 0, error.message ?: "SIEM request failed.")
        }
    }

    private fun persistPending(count: Int, payload: String, endpointTag: String) {
        val safeCount = count.coerceAtLeast(1)
        val file = File(queueDir, "batch_${System.currentTimeMillis()}_${endpointTag}_${safeCount}.json")
        file.writeText(payload, StandardCharsets.UTF_8)
    }

    /** Endpoint tag from "batch_<ts>_<tag>_<count>.json"; null for legacy "batch_<ts>_<count>.json". */
    private fun parseTagFromFileName(fileName: String): String? {
        val parts = fileName.removeSuffix(".json").split('_')
        return if (parts.size == 4) parts[2] else null
    }

    private fun buildJson(items: List<PayloadMetadataItem>): String {
        return buildString {
            appendLine("[")
            items.forEachIndexed { index, item ->
                append("  {")
                append("\"packageName\":\"${escapeJson(item.packageName)}\",")
                append("\"appName\":\"${escapeJson(item.appName)}\",")
                append("\"hostname\":\"${escapeJson(item.hostname)}\",")
                append("\"destinationIp\":\"${escapeJson(item.destinationIp)}\",")
                append("\"destinationPort\":${item.destinationPort},")
                append("\"protocol\":\"${escapeJson(item.protocol)}\",")
                append("\"direction\":\"${escapeJson(item.direction)}\",")
                append("\"preview\":\"${escapeJson(item.preview)}\",")
                append("\"sizeBytes\":${item.sizeBytes},")
                append("\"timestamp\":${item.timestamp},")
                append("\"wasBlocked\":${item.wasBlocked},")
                append("\"encryption\":\"${escapeJson(item.encryptionLabel)}\"")
                append("}")
                if (index != items.lastIndex) append(",")
                appendLine()
            }
            appendLine("]")
        }
    }

    private fun parseCountFromPayload(payload: String): Int =
        payload.count { it == '{' }

    private fun parseCountFromFileName(fileName: String): Int {
        val suffix = fileName.substringAfterLast('_', "")
            .substringBefore('.')
        return suffix.toIntOrNull() ?: 0
    }


    private fun escapeJson(input: String): String = SiemEndpoint.escapeJson(input)

    private companion object {
        /** Tag for batches saved with no valid endpoint; these are never auto-flushed. */
        const val UNBOUND_TAG = "none"
        const val CONNECT_TIMEOUT_SECONDS = 7L
        const val READ_TIMEOUT_SECONDS = 10L
    }
}

data class ShipResult(
    val success: Boolean,
    val shippedCount: Int,
    val message: String,
)

data class QueuedBatchInfo(
    val fileName: String,
    val eventCount: Int,
    val sizeBytes: Long,
    val createdAt: Long,
)