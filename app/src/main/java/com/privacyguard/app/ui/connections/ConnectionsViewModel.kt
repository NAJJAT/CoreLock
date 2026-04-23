package com.privacyguard.app.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.stats.StatsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Connection(
    val id: String,
    val appName: String,
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
    val appName: String? = null,
    val protocol: String? = null,
    val showBlockedOnly: Boolean = false
)

class ConnectionsViewModel : ViewModel() {

    val connections: StateFlow<List<Connection>> = StatsManager.snapshot
        .map { snapshot ->
            snapshot.activeConnections.map {
                Connection(
                    id = it.id,
                    appName = it.appName,
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

    val dataRate: String
        get() = formatRate(connections.value.sumOf { it.bytesSent })

    fun setFilter(filter: ConnectionFilter) {
        _filter.value = filter
    }

    fun clearFilter() {
        _filter.value = ConnectionFilter()
    }

    fun getFilteredConnections(): List<Connection> {
        var result = connections.value

        if (_filter.value.showBlockedOnly) {
            result = result.filter { it.isBlocked }
        }

        _filter.value.appName?.let { appName ->
            result = result.filter { it.appName.equals(appName, ignoreCase = true) }
        }

        _filter.value.protocol?.let { protocol ->
            result = result.filter { it.protocol.equals(protocol, ignoreCase = true) }
        }

        return result
    }

    private fun formatRate(bytes: Long): String {
        if (bytes <= 0) return "0 KB/s"
        return String.format("%.1f KB/s", bytes / 1024.0)
    }
}