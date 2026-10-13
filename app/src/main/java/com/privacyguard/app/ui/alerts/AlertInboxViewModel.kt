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

enum class AlertType { DNS_ANOMALY, JA3_THREAT, TLS_FP_CHANGE, WEAK_CIPHER, CT_EVENT, CLEARTEXT }

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
    private val feed = AlertFeed(app)
    private val _state = MutableStateFlow(AlertInboxState())
    val state: StateFlow<AlertInboxState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                try { refresh() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
                delay(5_000)
            }
        }
    }

    fun setFilter(f: AlertSeverityFilter) {
        _state.value = _state.value.copy(filter = f)
    }

    private suspend fun refresh() {
        _state.value = _state.value.copy(items = feed.loadItems(), isLoading = false)
    }
}

/** Builds the alert inbox; also used for the Home bell badge so both always agree. */
class AlertFeed(private val app: Application) {
    private fun str(id: Int, vararg a: Any): String = app.getString(id, *a)
    private fun getApplication(): Application = app
    private val db = AppDatabase.getInstance(app)

    /**
     * Everything in the inbox: the last 7 days of DNS anomalies and TLS alerts, plus
     * one cleartext entry per app. The Home bell badge shows the size of this list.
     */
    suspend fun loadItems(): List<AlertItem> {
        val since = System.currentTimeMillis() - WINDOW_MS

        val dnsItems = db.dnsAnomalyDao().since(since).map { it.toAlertItem() }
        val tlsItems = db.tlsAlertDao().since(since).map { it.toAlertItem() }
        val cleartextItems = db.connectionDao()
            .cleartextSince(since)
            .filter { com.privacyguard.app.ui.traffic.HomeEvidence.isInternetCleartext(it) }
            .distinctBy { it.packageName }
            .map { conn ->
                AlertItem(
                    id = "cleartext_${conn.id}",
                    type = AlertType.CLEARTEXT,
                    title = str(com.privacyguard.app.R.string.alerts_cleartext),
                    detail = str(com.privacyguard.app.R.string.notif_app_to_domain, conn.packageName.ifBlank { null }?.let { appLabel(it) } ?: str(com.privacyguard.app.R.string.conn_unknown_app), conn.sniHostname ?: conn.domain ?: conn.destinationIp),
                    severity = 4,
                    timestamp = conn.timestamp,
                    packageName = conn.packageName.ifBlank { null },
                )
            }

        return (dnsItems + tlsItems + cleartextItems).sortedByDescending { it.timestamp }
    }

    companion object {
        const val WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private fun DnsAnomalyEntity.toAlertItem() = AlertItem(
        id = "dns_$id",
        type = AlertType.DNS_ANOMALY,
        title = com.privacyguard.app.ui.text.FindingText.dnsAnomalyTitle(getApplication(), anomalyType),
        detail = "$domain · ${com.privacyguard.app.ui.text.FindingText.dnsAnomalyExplanation(getApplication(), anomalyType)}",
        severity = severity,
        timestamp = timestamp,
        packageName = packageName.ifBlank { null },
    )

    private fun appLabel(pkg: String): String = runCatching {
        val pm = getApplication().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { com.privacyguard.app.ui.traffic.AppNames.label(app, pkg) }

    private fun TlsAlertEntity.toAlertItem() = AlertItem(
        id = "tls_$id",
        type = when (alertType) {
            "JA3_THREAT"  -> AlertType.JA3_THREAT
            TlsAlertEntity.TYPE_TLS_FP_CHANGE -> AlertType.TLS_FP_CHANGE
            "WEAK_CIPHER" -> AlertType.WEAK_CIPHER
            else          -> AlertType.CT_EVENT
        },
        title = when (alertType) {
            "JA3_THREAT"  -> str(com.privacyguard.app.R.string.alerts_ja3, malwareName ?: category ?: str(com.privacyguard.app.R.string.alerts_unknown_malware))
            "WEAK_CIPHER" -> str(com.privacyguard.app.R.string.alerts_weak_cipher)
            TlsAlertEntity.TYPE_TLS_FP_CHANGE -> getApplication().getString(
                com.privacyguard.app.R.string.tls_fp_alert_title,
                packageName?.let { appLabel(it) } ?: "?",
            )
            else          -> str(com.privacyguard.app.R.string.alerts_ct)
        },
        detail = listOfNotNull(sni, hash?.take(16)?.let { "hash:$it" }, detail).firstOrNull() ?: "",
        severity = severity,
        timestamp = timestamp,
        packageName = packageName,
    )
}
