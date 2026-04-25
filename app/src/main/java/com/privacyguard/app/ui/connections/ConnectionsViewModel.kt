package com.privacyguard.app.ui.connections

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Connection(
    val id: String,
    val appName: String,
    val packageName: String,
    val destination: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val isBlocked: Boolean,
    val dataRate: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val hostName: String? = null,
    val securityInfo: String = "Unknown",
    val encryptionInfo: String = "Unknown",
    val payloadPreview: String? = null
)

data class ConnectionFilter(
    val query: String = "",
    val showBlockedOnly: Boolean = false,
    val showCleartextOnly: Boolean = false
)

class ConnectionsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())

    private val _recentConnections = MutableStateFlow<List<Connection>>(emptyList())
    val recentConnections: StateFlow<List<Connection>> = _recentConnections.asStateFlow()

    val connections: StateFlow<List<Connection>> = StatsManager.snapshot
        .map { snapshot ->
            val packagesByLabel = snapshot.appStats.associate { it.appName to it.packageName }
            snapshot.activeConnections.map {
                Connection(
                    id = it.id,
                    appName = it.appName,
                    packageName = packagesByLabel[it.appName] ?: it.appName,
                    destination = it.destination,
                    destinationIp = it.destinationIp,
                    destinationPort = it.destinationPort,
                    protocol = it.protocol,
                    isBlocked = it.isBlocked,
                    dataRate = formatRate(it.bytesTransferred),
                    bytesSent = it.bytesTransferred,
                    bytesReceived = 0,
                    hostName = it.hostName,
                    securityInfo = it.securityInfo,
                    encryptionInfo = it.encryptionInfo,
                    payloadPreview = it.payloadPreview
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(ConnectionFilter())
    val filter: StateFlow<ConnectionFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                _recentConnections.value = db.connectionDao().getRecentConnections(since, 100).map {
                    Connection(
                        id = it.id.toString(),
                        appName = it.appName.ifBlank {
                            it.packageName.ifBlank { "Unknown" }
                        },
                        packageName = it.packageName.ifBlank {
                            it.appName.ifBlank { "Unknown" }
                        },
                        destination = "${it.destinationIp}:${it.destinationPort}",
                        destinationIp = it.destinationIp,
                        destinationPort = it.destinationPort,
                        protocol = it.protocol,
                        isBlocked = it.wasBlocked,
                        dataRate = formatRate(it.bytesSent + it.bytesReceived),
                        bytesSent = it.bytesSent,
                        bytesReceived = it.bytesReceived,
                        hostName = it.sniHostname ?: it.domain,
                        securityInfo = it.encryptionStatus,
                        encryptionInfo = it.tlsVersion ?: "",
                        payloadPreview = null
                    )
                }
                delay(1_000)
            }
        }
    }

    val dataRate: String
        get() = formatRate(connections.value.sumOf { it.bytesSent })

    fun setFilter(filter: ConnectionFilter) {
        _filter.value = filter
    }

    fun clearFilter() {
        _filter.value = ConnectionFilter()
    }

    fun blockApp(packageName: String) {
        viewModelScope.launch {
            val ruleId = "pkg:block:$packageName"
            rulesRepo.upsertRule(
                FilterRule(
                    id = ruleId,
                    label = "Block $packageName",
                    action = FilterRule.Action.DENY,
                    source = FilterRule.Source.USER,
                    priority = FilterRule.HIGH_PRIORITY,
                    matchPackage = packageName
                )
            )
        }
    }

    fun getFilteredConnections(): List<Connection> {
        var result = if (connections.value.isNotEmpty()) connections.value else recentConnections.value

        if (_filter.value.showBlockedOnly) {
            result = result.filter { it.isBlocked }
        }

        if (_filter.value.showCleartextOnly) {
            result = result.filter {
                it.securityInfo.contains("CLEAR", ignoreCase = true) ||
                    it.securityInfo.contains("UNKNOWN", ignoreCase = true)
            }
        }

        if (_filter.value.query.isNotBlank()) {
            val q = _filter.value.query
            result = result.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true) ||
                    it.destinationIp.contains(q, ignoreCase = true) ||
                    (it.hostName?.contains(q, ignoreCase = true) == true) ||
                    it.destination.contains(q, ignoreCase = true)
            }
        }

        return result
    }

    private fun formatRate(bytes: Long): String {
        if (bytes <= 0) return "0 KB/s"
        return String.format("%.1f KB/s", bytes / 1024.0)
    }
}
