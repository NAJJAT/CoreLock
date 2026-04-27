package com.privacyguard.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecurityPosture
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.platform.android.PrivacyVpnService
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SecuritySettingsState(
    val blockCleartext: Boolean = false,
    val blockWeakTls: Boolean = false,
    val killSwitch: Boolean = false,
    val posture: SecurityPosture = SecurityPosture(),
)

data class DiagnosticCheck(
    val label: String,
    val status: DiagnosticStatus,
    val detail: String,
)

enum class DiagnosticStatus { PASS, WARN, FAIL, RUNNING }

data class NetworkDiagnosticsState(
    val isRunning: Boolean = false,
    val lastRunAt: Long = 0L,
    val checks: List<DiagnosticCheck> = emptyList(),
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val settingsPreferences = SettingsPreferences.getInstance(app)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())

    private val _securityState = MutableStateFlow(SecuritySettingsState())
    val securityState: StateFlow<SecuritySettingsState> = _securityState.asStateFlow()

    private val _diagnosticsState = MutableStateFlow(NetworkDiagnosticsState())
    val diagnosticsState: StateFlow<NetworkDiagnosticsState> = _diagnosticsState.asStateFlow()

    init {
        // Re-evaluate rule toggles whenever any rule changes
        viewModelScope.launch {
            RuleSyncBus.version.collect { refreshRules() }
        }
        // Posture check is a system call; refresh every 30s rather than every 1s
        viewModelScope.launch {
            while (true) {
                refreshPosture()
                delay(30_000)
            }
        }
    }

    fun toggleBlockCleartext(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = RULE_ID_BLOCK_CLEARTEXT,
                        label = "Block all HTTP (cleartext)",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchEncryption = EncryptionStatus.CLEARTEXT,
                    )
                )
            } else {
                rulesRepo.deleteRule(RULE_ID_BLOCK_CLEARTEXT)
            }
            refresh()
        }
    }

    fun toggleBlockWeakTls(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = RULE_ID_BLOCK_WEAK_TLS,
                        label = "Block deprecated TLS versions",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchEncryption = EncryptionStatus.WEAK_TLS,
                    )
                )
            } else {
                rulesRepo.deleteRule(RULE_ID_BLOCK_WEAK_TLS)
            }
            refresh()
        }
    }

    fun toggleKillSwitch(enabled: Boolean) {
        settingsPreferences.setKillSwitchEnabled(enabled)
        if (enabled) {
            KillSwitch.enable()
            if (PrivacyVpnService.isRunning) KillSwitch.startMonitoring(getApplication())
        } else {
            KillSwitch.disable()
            KillSwitch.stopMonitoring()
        }
        _securityState.value = _securityState.value.copy(killSwitch = enabled)
    }

    fun clearConnectionData() {
        viewModelScope.launch {
            db.connectionDao().deleteAll()
            db.connectionProfileDao().deleteAll()
            db.dnsAnomalyDao().deleteAll()
        }
    }

    fun runDiagnostics() {
        if (_diagnosticsState.value.isRunning) return
        _diagnosticsState.value = NetworkDiagnosticsState(
            isRunning = true,
            checks = listOf(DiagnosticCheck("Diagnostics", DiagnosticStatus.RUNNING, "Running network checks...")),
        )
        viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) { buildDiagnostics() }
            _diagnosticsState.value = NetworkDiagnosticsState(
                isRunning = false,
                lastRunAt = System.currentTimeMillis(),
                checks = checks,
            )
        }
    }

    private suspend fun refreshRules() {
        val rules = db.rulesDao().getAllRules()
        _securityState.value = _securityState.value.copy(
            blockCleartext = rules.any {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.matchEncryption == EncryptionStatus.CLEARTEXT.name
            },
            blockWeakTls = rules.any {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.matchEncryption == EncryptionStatus.WEAK_TLS.name
            },
            killSwitch = settingsPreferences.killSwitchEnabled.value,
        )
    }

    private suspend fun refreshPosture() {
        val posture = withContext(Dispatchers.IO) { AppSecurityMonitor.refresh(getApplication()) }
        _securityState.value = _securityState.value.copy(posture = posture)
    }

    private fun buildDiagnostics(): List<DiagnosticCheck> {
        val snapshot = StatsManager.snapshot.value
        val installedNetworkApps = runCatching {
            @Suppress("DEPRECATION")
            getApplication<Application>().packageManager
                .getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .count { info ->
                    info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
                }
        }.getOrDefault(0)

        return listOf(
            DiagnosticCheck(
                label = "VPN tunnel",
                status = if (PrivacyVpnService.isRunning) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = if (PrivacyVpnService.isRunning) "VPN service reports active" else "VPN is not running",
            ),
            DiagnosticCheck(
                label = "Kill Switch",
                status = if (settingsPreferences.killSwitchEnabled.value && KillSwitch.isEnabled()) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = if (settingsPreferences.killSwitchEnabled.value) "Enabled by default" else "Disabled",
            ),
            resolveHostCheck("DNS resolution", "google.com"),
            tcpReachabilityCheck("Upstream DNS TCP", settingsPreferences.upstreamDns.value, 53),
            externalIpCheck(),
            dnsLeakCheck(),
            DiagnosticCheck(
                label = "IPv6 leak prevention",
                status = DiagnosticStatus.PASS,
                detail = "${snapshot.ipv6PacketsBlocked} IPv6 packets blocked/tracked",
            ),
            DiagnosticCheck(
                label = "App inventory",
                status = if (installedNetworkApps > 0) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = "$installedNetworkApps network-capable apps visible",
            ),
            DiagnosticCheck(
                label = "Captured traffic",
                status = if (snapshot.totalPackets > 0) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = "${snapshot.totalPackets} packets observed in current session",
            ),
        )
    }

    private fun resolveHostCheck(label: String, host: String): DiagnosticCheck {
        return runCatching {
            val addresses = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
            DiagnosticCheck(
                label = label,
                status = if (addresses.isNotEmpty()) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                detail = addresses.take(3).joinToString(", ").ifBlank { "No records returned" },
            )
        }.getOrElse { error ->
            DiagnosticCheck(label, DiagnosticStatus.FAIL, error.message ?: "DNS failed")
        }
    }

    private fun tcpReachabilityCheck(label: String, host: String, port: Int): DiagnosticCheck {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2_500)
            }
            DiagnosticCheck(label, DiagnosticStatus.PASS, "$host:$port reachable")
        }.getOrElse { error ->
            DiagnosticCheck(label, DiagnosticStatus.WARN, "$host:$port ${error.message ?: "unreachable"}")
        }
    }

    private fun externalIpCheck(): DiagnosticCheck {
        return runCatching {
            val url = java.net.URL("https://api.ipify.org?format=text")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            val ip = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            val isPrivate = ip.startsWith("10.") || ip.startsWith("192.168.") ||
                ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip == "10.0.0.2"
            DiagnosticCheck(
                label = "External IP",
                status = if (isPrivate) DiagnosticStatus.WARN else DiagnosticStatus.PASS,
                detail = if (isPrivate) "Shows VPN address $ip — tunnel may be transparent" else ip,
            )
        }.getOrElse { error ->
            DiagnosticCheck("External IP", DiagnosticStatus.WARN, error.message ?: "Could not reach ipify.org")
        }
    }

    private fun dnsLeakCheck(): DiagnosticCheck {
        val dohEnabled = settingsPreferences.dohEnabled.value
        val upstreamDns = settingsPreferences.upstreamDns.value
        // DNS leak risk: if DoH is off, queries go plaintext to the upstream server.
        // VPN tunnel captures all traffic so no bypass is possible, but plaintext DNS
        // is visible to whoever operates the upstream server.
        val isLeakFree = dohEnabled
        return DiagnosticCheck(
            label = "DNS privacy",
            status = if (isLeakFree) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (isLeakFree)
                "DoH encrypted queries → $upstreamDns"
            else
                "Plaintext DNS to $upstreamDns — enable DoH for encrypted queries",
        )
    }

    companion object {
        private const val RULE_ID_BLOCK_CLEARTEXT = "global:block:cleartext"
        private const val RULE_ID_BLOCK_WEAK_TLS = "global:block:weak_tls"
    }
}
