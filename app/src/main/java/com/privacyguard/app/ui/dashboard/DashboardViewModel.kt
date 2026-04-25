package com.privacyguard.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.vpn.PrivacyVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isVpnActive: Boolean = false,
    val trackersBlocked: Int = 0,
    val cleartextCount: Int = 0,
    val blocklistDomains: Int = 0,
    val encryptionHealth: Float = 0.87f,
    val anomalies: List<DnsAnomalyEntity> = emptyList()
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    private suspend fun refresh() {
        val snapshot = StatsManager.snapshot.value
        val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val recentConnections = db.connectionDao().getRecentConnections(since, 200)
        val cleartextCount = recentConnections.count {
            !it.wasBlocked && (it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN")
        }
        val secureCount = recentConnections.count { it.encryptionStatus == "TLS" }
        val encryptionHealth = if (recentConnections.isEmpty()) {
            0.87f
        } else {
            secureCount.toFloat() / recentConnections.size.toFloat()
        }

        _uiState.value = DashboardUiState(
            isVpnActive = PrivacyVpnService.isRunning,
            trackersBlocked = snapshot.totalTrackersBlocked,
            cleartextCount = cleartextCount,
            blocklistDomains = BlocklistManager.size.value,
            encryptionHealth = encryptionHealth.coerceIn(0f, 1f),
            anomalies = db.dnsAnomalyDao().recent(5)
        )
    }
}
