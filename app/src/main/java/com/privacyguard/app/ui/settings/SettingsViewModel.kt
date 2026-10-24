package com.privacyguard.app.ui.settings

import com.privacyguard.app.core.utils.readUtf8Capped
import android.app.Application
import com.privacyguard.app.R
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecurityPosture
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import android.os.Build
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.platform.android.PrivacyVpnService
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.privacyguard.app.ui.launchWhileObserved

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

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
    private val db = AppDatabase.getInstance(app)
    private val configDb = ConfigDatabase.getInstance(app)
    private val settingsPreferences = SettingsPreferences.getInstance(app)
    private val rulesRepo = RulesRepo(configDb.rulesDao(), FilterEngine())

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
        viewModelScope.launchWhileObserved(_securityState) {
            while (true) {
                try { refreshPosture() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
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
                        label = str(R.string.diag_rule_block_http),
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
                        label = str(R.string.diag_rule_block_weak_tls),
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

    /**
     * Android Private DNS in strict mode sends DNS over TLS (port 853), which the
     * tunnel drops to keep the blocklist in force; DNS then fails for every app.
     */
    private fun batteryCheck(): DiagnosticCheck {
        val label = str(R.string.diag_battery)
        return when (BatteryCheck.state(getApplication())) {
            BatteryCheck.State.RESTRICTED -> DiagnosticCheck(label, DiagnosticStatus.FAIL, str(R.string.diag_battery_restricted))
            BatteryCheck.State.OPTIMIZED -> DiagnosticCheck(label, DiagnosticStatus.WARN, str(R.string.diag_battery_optimized))
            BatteryCheck.State.UNRESTRICTED -> DiagnosticCheck(label, DiagnosticStatus.PASS, str(R.string.diag_battery_unrestricted))
        }
    }

    private fun privateDnsCheck(): DiagnosticCheck {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return DiagnosticCheck(str(R.string.diag_private_dns), DiagnosticStatus.PASS, str(R.string.diag_private_dns_pre9))
        }
        val cm = getApplication<android.app.Application>()
            .getSystemService(android.net.ConnectivityManager::class.java)
        // The underlying network, not the VPN, carries the Private DNS setting.
        val props = cm?.allNetworks
            ?.filter { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false }
            ?.mapNotNull { cm.getLinkProperties(it) }
            .orEmpty()
        val strict = props.firstOrNull { !it.privateDnsServerName.isNullOrBlank() }
        return when {
            strict != null -> DiagnosticCheck(
                str(R.string.diag_private_dns), DiagnosticStatus.WARN,
                str(R.string.diag_private_dns_strict, strict.privateDnsServerName.orEmpty()),
            )
            props.any { it.isPrivateDnsActive } -> DiagnosticCheck(
                str(R.string.diag_private_dns), DiagnosticStatus.PASS, str(R.string.diag_private_dns_auto),
            )
            else -> DiagnosticCheck(str(R.string.diag_private_dns), DiagnosticStatus.PASS, str(R.string.diag_off))
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
        viewModelScope.launch { com.privacyguard.app.data.repository.LocalDataEraser.eraseAll(getApplication()) }
    }

    fun runDiagnostics() {
        if (_diagnosticsState.value.isRunning) return
        _diagnosticsState.value = NetworkDiagnosticsState(
            isRunning = true,
            checks = listOf(DiagnosticCheck(str(R.string.diag_diagnostics), DiagnosticStatus.RUNNING, str(R.string.diag_running))),
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

    private suspend fun refresh() {
        refreshRules()
        refreshPosture()
    }

    private suspend fun refreshRules() {
        val rules = configDb.rulesDao().getAllRules()
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
                label = str(R.string.diag_vpn_tunnel),
                status = if (PrivacyVpnService.isRunning) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = if (PrivacyVpnService.isRunning) str(R.string.diag_vpn_active) else str(R.string.diag_vpn_inactive),
            ),
            DiagnosticCheck(
                label = str(R.string.set_kill_switch),
                status = if (KillSwitch.lockdownActive) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = when {
                    KillSwitch.lockdownActive -> str(R.string.diag_lockdown_on)
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> str(R.string.diag_lockdown_unknown)
                    else -> str(R.string.diag_lockdown_off)
                },
            ),
            batteryCheck(),
            privateDnsCheck(),
            resolveHostCheck(str(R.string.diag_dns_resolution), "google.com"),
            upstreamDnsCheck(),
            externalIpCheck(),
            dnsLeakCheck(),
            DiagnosticCheck(
                label = str(R.string.diag_ipv6),
                status = DiagnosticStatus.PASS,
                detail = getApplication<Application>().resources.getQuantityString(R.plurals.diag_ipv6_detail, snapshot.ipv6PacketsBlocked.toInt().coerceAtLeast(0), snapshot.ipv6PacketsBlocked.toInt()),
            ),
            DiagnosticCheck(
                label = str(R.string.diag_sni),
                status = DiagnosticStatus.PASS,
                detail = str(R.string.diag_sni_detail, snapshot.sniBlocked),
            ),
            // Dropped QUIC is not a failure: the app falls back to TCP, where the SNI is checked.
            DiagnosticCheck(
                label = str(R.string.diag_quic),
                status = DiagnosticStatus.PASS,
                detail = str(R.string.diag_quic_detail, snapshot.quicNameBlocked, snapshot.quicOffDropped,
                    snapshot.quicUnreadableDropped, snapshot.quicHoldLimitDropped),
            ),
            // Written + merged = every blocked connection attempt; the history keeps one row per minute.
            DiagnosticCheck(
                label = str(R.string.diag_block_rows),
                status = DiagnosticStatus.PASS,
                detail = str(R.string.diag_block_rows_detail, snapshot.blockedRowsWritten + snapshot.blockedRowsMerged,
                    snapshot.blockedRowsWritten, snapshot.blockedRowsMerged),
            ),
            DiagnosticCheck(
                label = str(R.string.diag_inventory),
                status = if (installedNetworkApps > 0) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = getApplication<Application>().resources.getQuantityString(R.plurals.diag_inventory_detail, installedNetworkApps, installedNetworkApps),
            ),
            DiagnosticCheck(
                label = str(R.string.diag_captured),
                status = if (snapshot.totalPackets > 0) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                detail = getApplication<Application>().resources.getQuantityString(R.plurals.diag_captured_detail, snapshot.totalPackets.toInt().coerceAtLeast(0), snapshot.totalPackets.toInt()),
            ),
        )
    }

    private fun resolveHostCheck(label: String, host: String): DiagnosticCheck {
        return runCatching {
            val addresses = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
            DiagnosticCheck(
                label = label,
                status = if (addresses.isNotEmpty()) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                detail = addresses.take(3).joinToString(", ").ifBlank { str(R.string.diag_no_records) },
            )
        }.getOrElse { error ->
            DiagnosticCheck(label, DiagnosticStatus.FAIL, error.message ?: str(R.string.diag_dns_failed))
        }
    }

    /**
     * Asks the running VPN to look up a name the way it forwards every query (DoH or
     * plain UDP to the chosen resolver). A socket opened here would itself go through
     * the tunnel, where DNS to outside resolvers is dropped, and always fail.
     */
    private fun upstreamDnsCheck(): DiagnosticCheck {
        val label = str(R.string.diag_upstream_tcp)
        val target = if (settingsPreferences.dohEnabled.value) java.net.URL(settingsPreferences.dohProvider.value).host
            else settingsPreferences.upstreamDns.value
        return when (PrivacyVpnService.probeUpstreamDns()) {
            null -> DiagnosticCheck(label, DiagnosticStatus.WARN, str(R.string.diag_upstream_vpn_off))
            true -> DiagnosticCheck(label, DiagnosticStatus.PASS, str(R.string.diag_reachable, target))
            false -> DiagnosticCheck(label, DiagnosticStatus.FAIL, str(R.string.diag_upstream_failed, target))
        }
    }

    private fun externalIpCheck(): DiagnosticCheck {
        if (!settingsPreferences.externalIpCheckEnabled.value) {
            return DiagnosticCheck(str(R.string.diag_external_ip), DiagnosticStatus.PASS, str(R.string.diag_external_ip_skipped, str(R.string.set_external_ip)))
        }
        return runCatching {
            val url = java.net.URL("https://api.ipify.org?format=text")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            val ip = conn.inputStream.use { it.readUtf8Capped(256) }.trim()
            conn.disconnect()
            val isPrivate = ip.startsWith("10.") || ip.startsWith("192.168.") ||
                ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip == "10.0.0.2"
            DiagnosticCheck(
                label = str(R.string.diag_external_ip),
                status = if (isPrivate) DiagnosticStatus.WARN else DiagnosticStatus.PASS,
                detail = if (isPrivate) str(R.string.diag_external_ip_vpn, ip) else ip,
            )
        }.getOrElse { error ->
            DiagnosticCheck(str(R.string.diag_external_ip), DiagnosticStatus.WARN, error.message ?: str(R.string.diag_ipify_failed))
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
            label = str(R.string.diag_dns_privacy),
            status = if (isLeakFree) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
            detail = if (isLeakFree)
                str(R.string.diag_doh_on, upstreamDns)
            else
                str(R.string.diag_doh_off, upstreamDns),
        )
    }

    companion object {
        private const val RULE_ID_BLOCK_CLEARTEXT = "global:block:cleartext"
        private const val RULE_ID_BLOCK_WEAK_TLS = "global:block:weak_tls"
    }
}

