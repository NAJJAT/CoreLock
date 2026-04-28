package com.privacyguard.app.ui.alerts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.db.TlsAlertEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AlertType { DNS_ANOMALY, JA3_THREAT, WEAK_CIPHER, CT_EVENT, CLEARTEXT }

enum class AlertSeverityFilter { ALL, CRITICAL, HIGH, MEDIUM }

data class AlertItem(
    val id: String,
    val type: AlertType,
    val title: String,
    val detail: String,
    val severity: Int,
    val timestamp: Long,
    val packageName: String? = null,
)

data class AlertInboxState(
    val items: List<AlertItem> = emptyList(),
    val filter: AlertSeverityFilter = AlertSeverityFilter.ALL,
    val isLoading: Boolean = true,
)

class AlertInboxViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val _state = MutableStateFlow(AlertInboxState())
    val state: StateFlow<AlertInboxState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5_000)
            }
        }
    }

    fun setFilter(f: AlertSeverityFilter) {
        _state.value = _state.value.copy(filter = f)
    }

    private suspend fun refresh() {
        val since = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L

        val dnsItems = db.dnsAnomalyDao().recent(60).map { it.toAlertItem() }
        val tlsItems = db.tlsAlertDao().recent(60).map { it.toAlertItem() }
        val cleartextItems = db.connectionDao()
            .getRecentConnections(since, 300)
            .filter { it.encryptionStatus == "CLEARTEXT" && !it.wasBlocked }
            .distinctBy { it.packageName }
            .take(25)
            .map { conn ->
                AlertItem(
                    id = "cleartext_${conn.id}",
                    type = AlertType.CLEARTEXT,
                    title = "Cleartext traffic",
                    detail = "${conn.packageName.ifBlank { "Unknown" }} → ${conn.sniHostname ?: conn.domain ?: conn.destinationIp}",
                    severity = 4,
                    timestamp = conn.timestamp,
                    packageName = conn.packageName.ifBlank { null },
                )
            }

        val all = (dnsItems + tlsItems + cleartextItems).sortedByDescending { it.timestamp }
        _state.value = _state.value.copy(items = all, isLoading = false)
    }

    private fun DnsAnomalyEntity.toAlertItem() = AlertItem(
        id = "dns_$id",
        type = AlertType.DNS_ANOMALY,
        title = anomalyType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
        detail = "$domain · $description",
        severity = severity,
        timestamp = timestamp,
        packageName = packageName.ifBlank { null },
    )

    private fun TlsAlertEntity.toAlertItem() = AlertItem(
        id = "tls_$id",
        type = when (alertType) {
            "JA3_THREAT"  -> AlertType.JA3_THREAT
            "WEAK_CIPHER" -> AlertType.WEAK_CIPHER
            else          -> AlertType.CT_EVENT
        },
        title = when (alertType) {
            "JA3_THREAT"  -> "JA3 fingerprint · ${malwareName ?: category ?: "Unknown malware"}"
            "WEAK_CIPHER" -> "Weak cipher suite detected"
            else          -> "Certificate Transparency event"
        },
        detail = listOfNotNull(sni, hash?.take(16)?.let { "hash:$it" }, detail).firstOrNull() ?: "",
        severity = severity,
        timestamp = timestamp,
        packageName = packageName,
    )
}
