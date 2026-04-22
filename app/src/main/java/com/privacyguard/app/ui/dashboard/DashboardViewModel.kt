/**
 * DashboardViewModel.kt
 * 
 * ViewModel for Dashboard screen
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.platform.android.PrivacyVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VpnStatus(
    val isActive: Boolean = false,
    val isConnecting: Boolean = false
)

data class DashboardStats(
    val blockedToday: Int = 0,
    val dataSavedMB: String = "0 MB",
    val activeApps: Int = 0,
    val totalTrackers: Int = 0,
    val privacyScore: Int = 0
)

data class RecentActivity(
    val appName: String,
    val description: String,
    val timeAgo: String,
    val isBlocked: Boolean
)

class DashboardViewModel : ViewModel() {

    private val _vpnStatus = MutableStateFlow(VpnStatus())
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    private val _recentActivity = MutableStateFlow<List<RecentActivity>>(emptyList())
    val recentActivity: StateFlow<List<RecentActivity>> = _recentActivity.asStateFlow()

    init {
        startStatsUpdate()
        loadRecentActivity()
    }

    fun startVpn(context: Context) {
        viewModelScope.launch {
            _vpnStatus.value = _vpnStatus.value.copy(isConnecting = true)
            val intent = Intent(context, PrivacyVpnService::class.java)
            context.startService(intent)
            delay(1000)
            _vpnStatus.value = _vpnStatus.value.copy(isActive = true, isConnecting = false)
        }
    }

    fun stopVpn(context: Context) {
        viewModelScope.launch {
            val intent = Intent(context, PrivacyVpnService::class.java)
            context.stopService(intent)
            _vpnStatus.value = _vpnStatus.value.copy(isActive = false)
        }
    }

    private fun startStatsUpdate() {
        viewModelScope.launch {
            while (true) {
                updateStats()
                delay(1000)
            }
        }
    }

    private suspend fun updateStats() {
        _stats.value = DashboardStats(
            blockedToday = (0..100).random(),
            dataSavedMB = "${(0..50).random()} MB",
            activeApps = (1..20).random(),
            totalTrackers = (100..1000).random(),
            privacyScore = (0..100).random()
        )
    }

    private fun loadRecentActivity() {
        _recentActivity.value = listOf(
            RecentActivity("Chrome", "Blocked tracker from google.com", "now", true),
            RecentActivity("Facebook", "Blocked tracker from facebook.com", "1 min ago", true),
            RecentActivity("WhatsApp", "Connected to whatsapp.net", "5 min ago", false),
            RecentActivity("Instagram", "Blocked tracker from instagram.com", "10 min ago", true),
            RecentActivity("YouTube", "Connected to youtube.com", "15 min ago", false)
        )
    }
}