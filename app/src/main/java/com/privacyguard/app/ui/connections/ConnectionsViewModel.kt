/**
 * ConnectionsViewModel.kt
 * 
 * ViewModel لشاشة الاتصالات الحية
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val bytesReceived: Long
)

data class ConnectionFilter(
    val appName: String? = null,
    val protocol: String? = null,
    val showBlockedOnly: Boolean = false
)

class ConnectionsViewModel : ViewModel() {

    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    private val _filter = MutableStateFlow(ConnectionFilter())
    val filter: StateFlow<ConnectionFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val dataRate: String
        get() = "${(0..100).random()} KB/s"

    init {
        startLiveUpdate()
    }

    private fun startLiveUpdate() {
        viewModelScope.launch {
            _isLoading.value = true
            delay(500)
            _isLoading.value = false

            while (true) {
                updateConnections()
                delay(1000) // تحديث كل ثانية
            }
        }
    }

    private suspend fun updateConnections() {
        _connections.value = generateMockConnections()
    }

    private fun generateMockConnections(): List<Connection> {
        val domains = listOf("google.com", "facebook.com", "youtube.com", "cloudflare.com", "twitter.com", "whatsapp.net", "instagram.com")
        val apps = listOf("Chrome", "Firefox", "WhatsApp", "Instagram", "System", "YouTube", "Facebook")
        val ips = listOf("142.250.185.46", "157.240.22.35", "142.250.185.46", "104.16.123.96", "104.244.42.1", "31.13.93.35", "13.107.42.14")

        return List(20) { i ->
            Connection(
                id = "conn_$i",
                appName = apps.random(),
                destination = domains.random(),
                destinationIp = ips.random(),
                destinationPort = listOf(80, 443, 8080, 53, 5222).random(),
                protocol = listOf("TCP", "UDP").random(),
                isBlocked = (0..10).random() > 7,
                dataRate = "${(0..500).random()} KB/s",
                bytesSent = (0..1000000).random().toLong(),
                bytesReceived = (0..1000000).random().toLong()
            )
        }
    }

    fun setFilter(filter: ConnectionFilter) {
        _filter.value = filter
    }

    fun clearFilter() {
        _filter.value = ConnectionFilter()
    }

    fun getFilteredConnections(): List<Connection> {
        var result = _connections.value

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
}