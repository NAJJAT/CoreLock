package com.privacyguard.app.ui.mitm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.components.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

enum class PayloadDirectionFilter { ALL, OUTBOUND, INBOUND }

data class PayloadMetadataItem(
    val id: Long,
    val packageName: String,
    val appName: String,
    val hostname: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val methodHint: String?,
    val direction: String,
    val preview: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val relativeTime: String,
    val sizeLabel: String,
    val wasBlocked: Boolean,
    val encryptionLabel: String,
)

data class MitmUiState(
    val enabled: Boolean = false,
    val consentFresh: Boolean = false,
    val interceptedSessions: Int = 0,
    val pinnedBypassed: Int = 0,
    val pendingEvents: Int = 0,
    val searchQuery: String = "",
    val directionFilter: PayloadDirectionFilter = PayloadDirectionFilter.ALL,
    val withBodyOnly: Boolean = false,
    val logs: List<PayloadMetadataItem> = emptyList(),
    val siemEndpoint: String = "",
    val siemApiKey: String = "",
    val shipToSiem: Boolean = false,
    val writeLocalLog: Boolean = true,
    val isShipping: Boolean = false,
    val shipMessage: String? = null,
    val queuedBatches: List<QueuedBatchInfo> = emptyList(),
)

/**
 * ViewModel for the enterprise Payloads tab.
 *
 * Business reason:
 * Enterprise operators need a controlled screen for inspecting metadata and
 * managing consent-backed inspection settings on managed devices.
 *
 * Thread safety:
 * State is exposed via [StateFlow]. Refresh runs in the ViewModel coroutine
 * scope and only mutates a single flow.
 */
class MitmViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val config = MitmConfig.getInstance(app)
    private val shipper = EnterpriseMetadataShipper(app, config)
    private val _state = MutableStateFlow(MitmUiState())
    val state: StateFlow<MitmUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        viewModelScope.launch { refresh() }
    }

    fun setDirectionFilter(filter: PayloadDirectionFilter) {
        _state.value = _state.value.copy(directionFilter = filter)
        viewModelScope.launch { refresh() }
    }

    fun setWithBodyOnly(enabled: Boolean) {
        _state.value = _state.value.copy(withBodyOnly = enabled)
        viewModelScope.launch { refresh() }
    }

    fun setEnabled(enabled: Boolean) {
        config.setEnabled(enabled)
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun recordConsentNow() {
        config.recordConsent(System.currentTimeMillis())
        viewModelScope.launch { refresh() }
    }

    fun setSiemEndpoint(endpoint: String) {
        config.setSiemEndpoint(endpoint)
        _state.value = _state.value.copy(siemEndpoint = endpoint)
    }

    fun setSiemApiKey(apiKey: String) {
        config.setSiemApiKey(apiKey)
        _state.value = _state.value.copy(siemApiKey = apiKey)
    }

    fun setShipToSiem(enabled: Boolean) {
        config.setShipToSiem(enabled)
        _state.value = _state.value.copy(shipToSiem = enabled)
    }

    fun setWriteLocalLog(enabled: Boolean) {
        config.setWriteLocalLog(enabled)
        _state.value = _state.value.copy(writeLocalLog = enabled)
    }

    fun exportLogs(): File {
        val exportDir = File(getApplication<Application>().filesDir, "enterprise_exports").apply { mkdirs() }
        val file = File(exportDir, "payload_metadata_export.json")
        val content = buildString {
            appendLine("[")
            state.value.logs.forEachIndexed { index, item ->
                append("  {")
                append("\"packageName\":\"${escapeJson(item.packageName)}\",")
                append("\"appName\":\"${escapeJson(item.appName)}\",")
                append("\"hostname\":\"${escapeJson(item.hostname)}\",")
                append("\"destination\":\"${escapeJson("${item.destinationIp}:${item.destinationPort}")}\",")
                append("\"protocol\":\"${escapeJson(item.protocol)}\",")
                append("\"direction\":\"${escapeJson(item.direction)}\",")
                append("\"sizeBytes\":${item.sizeBytes},")
                append("\"preview\":\"${escapeJson(item.preview)}\",")
                append("\"timestamp\":${item.timestamp},")
                append("\"blocked\":${item.wasBlocked}")
                append("}")
                if (index != state.value.logs.lastIndex) append(",")
                appendLine()
            }
            appendLine("]")
        }
        file.writeText(content, StandardCharsets.UTF_8)
        return file
    }

    fun shipNow() {
        val snapshot = state.value.logs
        _state.value = _state.value.copy(isShipping = true, shipMessage = null)
        viewModelScope.launch {
            val result = shipper.shipNow(snapshot)
            _state.value = _state.value.copy(
                isShipping = false,
                shipMessage = result.message,
            )
            refresh()
        }
    }

    fun clearPendingQueue() {
        viewModelScope.launch {
            val removed = shipper.clearPending()
            _state.value = _state.value.copy(
                shipMessage = if (removed > 0) "Cleared $removed queued batch${if (removed == 1) "" else "es"}." else "No queued batches to clear.",
            )
            refresh()
        }
    }

    private suspend fun refresh() {
        val recent = db.connectionDao().getRecentConnections(
            since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L,
            limit = 200,
        )
        val filtered = applyFilters(recent, _state.value.searchQuery, _state.value.directionFilter, _state.value.withBodyOnly)
        val items = filtered.map { it.toPayloadMetadataItem() }
        _state.value = _state.value.copy(
            enabled = config.isEnabled.value,
            consentFresh = config.isConsentFresh(),
            interceptedSessions = recent.count { it.encryptionStatus == "TLS" || it.encryptionStatus == "WEAK_TLS" },
            pinnedBypassed = recent.count { (it.packageName.contains("facebook") || it.packageName.contains("whatsapp")) && it.encryptionStatus == "TLS" },
            pendingEvents = shipper.pendingEventCount().takeIf { it > 0 } ?: items.size.coerceAtMost(50),
            logs = items,
            siemEndpoint = config.siemEndpoint.value,
            siemApiKey = config.siemApiKey.value,
            shipToSiem = config.shipToSiem.value,
            writeLocalLog = config.writeLocalLog.value,
            isShipping = _state.value.isShipping,
            shipMessage = _state.value.shipMessage,
            queuedBatches = shipper.queuedBatches(),
        )
    }

    private fun applyFilters(
        input: List<ConnectionEntity>,
        query: String,
        direction: PayloadDirectionFilter,
        withBodyOnly: Boolean,
    ): List<ConnectionEntity> {
        val lowered = query.trim().lowercase()
        return input.filter { entity ->
            val matchesDirection = when (direction) {
                PayloadDirectionFilter.ALL -> true
                PayloadDirectionFilter.OUTBOUND -> entity.bytesSent > 0
                PayloadDirectionFilter.INBOUND -> entity.bytesReceived > 0
            }
            val preview = buildPreview(entity)
            val matchesBody = !withBodyOnly || preview.isNotBlank()
            val matchesQuery = lowered.isBlank() || listOf(
                entity.packageName,
                entity.appName,
                entity.domain,
                entity.sniHostname,
                entity.destinationIp,
            ).filterNotNull().any { it.lowercase().contains(lowered) }
            matchesDirection && matchesBody && matchesQuery
        }
    }

    private fun ConnectionEntity.toPayloadMetadataItem(): PayloadMetadataItem {
        val preview = buildPreview(this)
        return PayloadMetadataItem(
            id = id,
            packageName = packageName,
            appName = appName.ifBlank { packageName },
            hostname = sniHostname ?: domain ?: destinationIp,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            protocol = protocol,
            methodHint = protocol.takeIf { it == "HTTP" || it == "HTTPS" }?.let { "CONNECT" },
            direction = if (bytesSent >= bytesReceived) "OUTBOUND" else "INBOUND",
            preview = preview,
            timestamp = timestamp,
            sizeBytes = bytesSent + bytesReceived,
            relativeTime = formatAgo(timestamp),
            sizeLabel = formatBytes(bytesSent + bytesReceived),
            wasBlocked = wasBlocked,
            encryptionLabel = encryptionStatus.ifBlank { "UNKNOWN" },
        )
    }

    private fun buildPreview(entity: ConnectionEntity): String {
        val host = entity.sniHostname ?: entity.domain ?: entity.destinationIp
        val encryption = entity.encryptionStatus.lowercase()
        return "${entity.protocol} $host · $encryption · ${entity.bytesSent + entity.bytesReceived} bytes"
            .take(80)
    }

    private fun escapeJson(input: String): String =
        input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
