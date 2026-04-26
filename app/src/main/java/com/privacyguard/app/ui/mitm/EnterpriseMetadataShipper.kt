package com.privacyguard.app.ui.mitm

import android.content.Context
import com.privacyguard.vpn.mitm.MitmConfig
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

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
        // FIXED: Remove .value access - use direct property access
        val endpoint = config.siemEndpoint.trim()
        val apiKey = config.siemApiKey.trim()

        // FIXED: Remove .value access - use direct boolean property
        if (config.shipToSiem && endpoint.startsWith("https://", ignoreCase = true) && apiKey.isNotBlank()) {
            val current = sendBatch(endpoint, apiKey, payload)
            if (current.success) {
                flushPending(endpoint, apiKey)
                return current.copy(message = "Shipped ${items.size} metadata events.")
            }
        }

        // FIXED: Remove .value access - use direct boolean property
        return if (config.writeLocalLog) {
            persistPending(items.size, payload)
            ShipResult(
                success = false,
                shippedCount = 0,
                message = if (config.shipToSiem) {
                    "SIEM shipping unavailable. Metadata saved locally."
                } else {
                    "SIEM shipping is off. Metadata saved locally."
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

    private fun flushPending(endpoint: String, apiKey: String) {
        queueDir.listFiles()
            .orEmpty()
            .sortedBy { it.lastModified() }
            .forEach { file ->
                val batch = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@forEach
                val result = sendBatch(endpoint, apiKey, batch)
                if (result.success) {
                    file.delete()
                }
            }
    }

    private fun sendBatch(endpoint: String, apiKey: String, payload: String): ShipResult {
        val connection = runCatching {
            URL(endpoint.trimEnd('/') + "/api/v1/events").openConnection() as HttpsURLConnection
        }.getOrElse {
            return ShipResult(false, 0, "Invalid SIEM endpoint.")
        }

        return runCatching {
            connection.requestMethod = "POST"
            connection.connectTimeout = 7_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("X-PrivacyGuard-Sig", hmacSha256Hex(payload, apiKey))
            connection.outputStream.use { stream ->
                stream.write(payload.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            if (code in 200..299) {
                ShipResult(true, parseCountFromPayload(payload), "Shipped successfully.")
            } else {
                ShipResult(false, 0, "SIEM returned HTTP $code.")
            }
        }.getOrElse { error ->
            ShipResult(false, 0, error.message ?: "SIEM request failed.")
        }.also {
            connection.disconnect()
        }
    }

    private fun persistPending(count: Int, payload: String) {
        val safeCount = count.coerceAtLeast(1)
        val file = File(queueDir, "batch_${System.currentTimeMillis()}_${safeCount}.json")
        file.writeText(payload, StandardCharsets.UTF_8)
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

    private fun hmacSha256Hex(body: String, apiKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            String.format(Locale.US, "%02x", it)
        }
    }

    private fun escapeJson(input: String): String =
        input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
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