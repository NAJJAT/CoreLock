package com.privacyguard.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.R
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.core.blocklist.PrefsBlocklistUpdateTimes
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.core.security.SecurityPosture
import com.privacyguard.app.core.security.SecurityRiskLevel
import com.privacyguard.app.data.db.SourceStats
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.PrimaryToggleRow
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.app.workers.BlocklistUpdateWorker
import com.privacyguard.app.workers.WeeklyReportWorker
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.vpn.forwarder.DnsHandler

// ─────────────────────────────────────────────────────────────────────────────
// Advanced (entry)
// ─────────────────────────────────────────────────────────────────────────────

data class AdvancedNav(
    val onBack: () -> Unit = {},
    val onOpenNetwork: () -> Unit = {},
    /** Null in a build without HTTPS inspection: the row is not shown. */
    val onOpenHttpsInspection: (() -> Unit)? = null,
    val onOpenAnalysis: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
)

/**
 * Settings › Advanced (DESIGN_SPEC_1.1.md §6.6): four rows, each opening one screen. The
 * warning under the title is the same as on the Settings row, so nobody lands here by
 * surprise.
 */
@Composable
fun AdvancedScreen(nav: AdvancedNav) {
    AdvancedPage(stringResource(R.string.ui_advanced), nav.onBack, subtitle = stringResource(R.string.ui_advanced_sub)) {
        item(key = "rows") {
            SettingsCard(Modifier.padding(top = CoreLockTheme.spacing.s)) {
                SettingRow(stringResource(R.string.ui_adv_network), nav.onOpenNetwork, subtitle = stringResource(R.string.ui_adv_network_sub), modifier = Modifier.testTag(TAG_ADV_NETWORK))
                nav.onOpenHttpsInspection?.let { open ->
                    SettingsDivider()
                    SettingRow(stringResource(R.string.ui_adv_https), open, subtitle = stringResource(R.string.ui_adv_https_sub), modifier = Modifier.testTag(TAG_ADV_HTTPS))
                }
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_adv_analysis), nav.onOpenAnalysis, subtitle = stringResource(R.string.ui_adv_analysis_sub), modifier = Modifier.testTag(TAG_ADV_ANALYSIS))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_adv_diagnostics), nav.onOpenDiagnostics, subtitle = stringResource(R.string.ui_adv_diagnostics_sub), modifier = Modifier.testTag(TAG_ADV_DIAGNOSTICS))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Network
// ─────────────────────────────────────────────────────────────────────────────

data class NetworkState(
    val dohEnabled: Boolean = false,
    val dohProvider: String = SettingsPreferences.DOH_CLOUDFLARE,
    val upstreamDns: String = AdvancedLogic.DnsPreset.CLOUDFLARE.ip,
    val blockQuic: Boolean = false,
    /** ADR 0015: remove ECH keys from DNS answers; applies at Strict only. */
    val stripEch: Boolean = false,
    val isStrict: Boolean = false,
    val blockWeakTls: Boolean = false,
    val blockCleartext: Boolean = false,
    val publicIpCheck: Boolean = false,
    val proxyChain: Boolean = false,
)

data class NetworkActions(
    val onBack: () -> Unit = {},
    val onDoh: (Boolean) -> Unit = {},
    val onDnsPreset: (AdvancedLogic.DnsPreset) -> Unit = {},
    /** Called only with a complete IP address. */
    val onCustomDns: (String) -> Unit = {},
    val onQuic: (Boolean) -> Unit = {},
    val onEch: (Boolean) -> Unit = {},
    val onWeakTls: (Boolean) -> Unit = {},
    val onCleartext: (Boolean) -> Unit = {},
    val onPublicIp: (Boolean) -> Unit = {},
    val onOpenProxies: () -> Unit = {},
)

/** Advanced › Network: the expert network settings of 1.0, each with its plain title (§7). */
@Composable
fun NetworkScreen(onBack: () -> Unit, onOpenProxies: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember(context) { SettingsPreferences.getInstance(context) }
    val security by vm.securityState.collectAsStateWithLifecycle()
    val doh by prefs.dohEnabled.collectAsStateWithLifecycle()
    val provider by prefs.dohProvider.collectAsStateWithLifecycle()
    val upstream by prefs.upstreamDns.collectAsStateWithLifecycle()
    val level by prefs.protectionLevel.collectAsStateWithLifecycle()
    val quicChoice by prefs.blockQuicChoice.collectAsStateWithLifecycle()
    val stripEch by prefs.stripEch.collectAsStateWithLifecycle()
    val publicIp by prefs.externalIpCheckEnabled.collectAsStateWithLifecycle()
    val proxies by prefs.dualVpnEnabled.collectAsStateWithLifecycle()
    NetworkContent(
        NetworkState(
            dohEnabled = doh, dohProvider = provider, upstreamDns = upstream,
            // The user's choice, else the level default (on at Strict), as QuicGate applies it.
            blockQuic = quicChoice ?: (level == FilterEngine.BlockLevel.STRICT.name),
            stripEch = stripEch, isStrict = level == FilterEngine.BlockLevel.STRICT.name,
            blockWeakTls = security.blockWeakTls, blockCleartext = security.blockCleartext,
            publicIpCheck = publicIp, proxyChain = proxies,
        ),
        NetworkActions(
            onBack = onBack,
            onDoh = prefs::setDohEnabled,
            onDnsPreset = { preset -> prefs.setUpstreamDns(preset.ip); prefs.setDohProvider(preset.dohProvider) },
            onCustomDns = { ip -> if (DnsHandler.isIpLiteral(ip)) prefs.setUpstreamDns(ip) },
            onQuic = prefs::setBlockQuic,
            onEch = prefs::setStripEch,
            onWeakTls = vm::toggleBlockWeakTls,
            onCleartext = vm::toggleBlockCleartext,
            onPublicIp = prefs::setExternalIpCheckEnabled,
            onOpenProxies = onOpenProxies,
        ),
    )
}

@Composable
fun NetworkContent(state: NetworkState, actions: NetworkActions) {
    var dnsSheet by rememberSaveable { mutableStateOf(false) }
    val preset = AdvancedLogic.presetOf(state.upstreamDns)
    AdvancedPage(stringResource(R.string.ui_adv_network), actions.onBack) {
        group("lookups", R.string.ui_net_group_lookups) {
            PrimaryToggleRow(stringResource(R.string.ui_net_doh), state.dohEnabled, actions.onDoh, subtitle = stringResource(R.string.ui_net_doh_sub), modifier = Modifier.testTag(TAG_NET_DOH))
            if (state.dohEnabled) {
                // ADR 0012: a DoH failure has no fallback. Said where it is switched on.
                Note(stringResource(R.string.ui_net_doh_note, AdvancedLogic.providerLabel(state.dohProvider)), Modifier.testTag(TAG_NET_DOH_NOTE))
            }
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_net_dns), { dnsSheet = true },
                subtitle = stringResource(R.string.ui_net_dns_sub), value = preset?.label ?: state.upstreamDns,
                modifier = Modifier.testTag(TAG_NET_DNS),
            )
        }
        group("blocking", R.string.ui_net_group_blocking) {
            PrimaryToggleRow(stringResource(R.string.ui_net_quic), state.blockQuic, actions.onQuic, subtitle = stringResource(R.string.ui_net_quic_sub), modifier = Modifier.testTag(TAG_NET_QUIC))
            SettingsDivider()
            // ADR 0015: the privacy cost is said in the row itself; off below Strict.
            val echSub = stringResource(R.string.ui_net_ech_sub)
            PrimaryToggleRow(
                stringResource(R.string.ui_net_ech), state.stripEch && state.isStrict, actions.onEch,
                subtitle = if (state.isStrict) echSub else echSub + " " + stringResource(R.string.ui_net_ech_strict_only),
                enabled = state.isStrict, modifier = Modifier.testTag(TAG_NET_ECH),
            )
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_net_weak_tls), state.blockWeakTls, actions.onWeakTls, subtitle = stringResource(R.string.ui_net_weak_tls_sub))
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_net_cleartext), state.blockCleartext, actions.onCleartext, subtitle = stringResource(R.string.ui_net_cleartext_sub))
        }
        group("more", R.string.ui_net_group_more) {
            SettingRow(
                stringResource(R.string.ui_net_proxy), actions.onOpenProxies,
                subtitle = stringResource(R.string.ui_net_proxy_sub),
                value = stringResource(if (state.proxyChain) R.string.ui_on else R.string.ui_off),
                modifier = Modifier.testTag(TAG_NET_PROXY),
            )
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_net_public_ip), state.publicIpCheck, actions.onPublicIp, subtitle = stringResource(R.string.ui_net_public_ip_sub))
        }
    }

    if (dnsSheet) {
        CoreLockBottomSheet(stringResource(R.string.ui_net_dns), onDismiss = { dnsSheet = false }) {
            DnsServerChoice(state.upstreamDns, onPreset = { actions.onDnsPreset(it); dnsSheet = false }, onCustom = { actions.onCustomDns(it); dnsSheet = false })
        }
    }
}

/**
 * The DNS server sheet: three known servers, or another address. A typed address is saved
 * only when it is a complete IP address, by the button; 1.0 saved every keystroke that
 * happened to parse ("1.1.1.1" on the way to "1.1.1.10").
 */
@Composable
internal fun ColumnScope.DnsServerChoice(current: String, onPreset: (AdvancedLogic.DnsPreset) -> Unit, onCustom: (String) -> Unit) {
    val preset = AdvancedLogic.presetOf(current)
    var other by rememberSaveable { mutableStateOf(preset == null) }
    var draft by rememberSaveable { mutableStateOf(if (preset == null) current else "") }
    val options: List<AdvancedLogic.DnsPreset?> = AdvancedLogic.DnsPreset.entries + listOf(null)
    val otherLabel = stringResource(R.string.ui_net_dns_other)
    Choices(options, if (other) null else preset, { it?.label ?: otherLabel }) { picked ->
        if (picked == null) other = true else onPreset(picked)
    }
    if (other) {
        val valid = DnsHandler.isIpLiteral(draft.trim())
        val error = draft.isNotBlank() && !valid
        TokenField(
            value = draft,
            onValue = { if (it.length <= 45) draft = it },
            label = stringResource(R.string.ui_net_dns_field),
            error = if (error) stringResource(R.string.ui_net_dns_invalid) else null,
            keyboard = KeyboardType.Uri,
            modifier = Modifier.testTag(TAG_NET_DNS_FIELD),
        )
        PrimaryButton(stringResource(R.string.ui_net_dns_use), { onCustom(draft.trim()) }, Modifier.fillMaxWidth().testTag(TAG_NET_DNS_USE), enabled = valid)
    }
    Note(stringResource(R.string.ui_net_dns_doh_note))
}

// ─────────────────────────────────────────────────────────────────────────────
// Use two proxy servers
// ─────────────────────────────────────────────────────────────────────────────

data class ProxyState(
    val enabled: Boolean = false,
    val host1: String = "", val port1: Int = 1080, val user1: String = "", val pass1: String = "",
    val host2: String = "", val port2: Int = 1080, val user2: String = "", val pass2: String = "",
    val blockOther: Boolean = false,
)

/** One setter per field; ports are passed only when valid (see [AdvancedLogic.parsePort]). */
data class ProxyActions(
    val onBack: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onHost1: (String) -> Unit = {}, val onPort1: (Int) -> Unit = {}, val onUser1: (String) -> Unit = {}, val onPass1: (String) -> Unit = {},
    val onHost2: (String) -> Unit = {}, val onPort2: (Int) -> Unit = {}, val onUser2: (String) -> Unit = {}, val onPass2: (String) -> Unit = {},
    val onBlockOther: (Boolean) -> Unit = {},
)

@Composable
fun ProxyChainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = remember(context) { SettingsPreferences.getInstance(context) }
    val enabled by p.dualVpnEnabled.collectAsStateWithLifecycle()
    val h1 by p.dualVpnHop1Host.collectAsStateWithLifecycle()
    val o1 by p.dualVpnHop1Port.collectAsStateWithLifecycle()
    val u1 by p.dualVpnHop1User.collectAsStateWithLifecycle()
    val w1 by p.dualVpnHop1Pass.collectAsStateWithLifecycle()
    val h2 by p.dualVpnHop2Host.collectAsStateWithLifecycle()
    val o2 by p.dualVpnHop2Port.collectAsStateWithLifecycle()
    val u2 by p.dualVpnHop2User.collectAsStateWithLifecycle()
    val w2 by p.dualVpnHop2Pass.collectAsStateWithLifecycle()
    val block by p.dualVpnBlockUdp.collectAsStateWithLifecycle()
    ProxyChainContent(
        ProxyState(enabled, h1, o1, u1, w1, h2, o2, u2, w2, block),
        ProxyActions(
            onBack = onBack, onEnabled = p::setDualVpnEnabled,
            onHost1 = p::setDualVpnHop1Host, onPort1 = p::setDualVpnHop1Port, onUser1 = p::setDualVpnHop1User, onPass1 = p::setDualVpnHop1Pass,
            onHost2 = p::setDualVpnHop2Host, onPort2 = p::setDualVpnHop2Port, onUser2 = p::setDualVpnHop2User, onPass2 = p::setDualVpnHop2Pass,
            onBlockOther = p::setDualVpnBlockUdp,
        ),
    )
}

@Composable
fun ProxyChainContent(state: ProxyState, actions: ProxyActions) {
    AdvancedPage(stringResource(R.string.ui_net_proxy), actions.onBack) {
        item(key = "switch") {
            SettingsCard(Modifier.padding(top = CoreLockTheme.spacing.s)) {
                PrimaryToggleRow(stringResource(R.string.ui_net_proxy), state.enabled, actions.onEnabled, subtitle = stringResource(R.string.ui_net_proxy_sub), modifier = Modifier.testTag(TAG_PROXY_SWITCH))
            }
        }
        // 1.0's full explanation, unchanged: what goes through the servers and what each one sees.
        item(key = "explain") { Note(stringResource(R.string.set_dual_desc), Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.l)) }
        if (state.enabled) {
            if (!AdvancedLogic.proxyChainComplete(state.host1, state.port1, state.host2, state.port2)) {
                item(key = "incomplete") {
                    Text(
                        stringResource(R.string.ui_proxy_incomplete),
                        style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.attention,
                        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.s).testTag(TAG_PROXY_INCOMPLETE),
                    )
                }
            }
            item(key = "hop1") {
                Hop(R.string.ui_proxy_first, state.host1, state.port1, state.user1, state.pass1, actions.onHost1, actions.onPort1, actions.onUser1, actions.onPass1, "1")
            }
            item(key = "hop2") {
                Hop(R.string.ui_proxy_second, state.host2, state.port2, state.user2, state.pass2, actions.onHost2, actions.onPort2, actions.onUser2, actions.onPass2, "2")
            }
            item(key = "block") {
                SettingsCard(Modifier.padding(top = CoreLockTheme.spacing.xl)) {
                    PrimaryToggleRow(stringResource(R.string.ui_proxy_block), state.blockOther, actions.onBlockOther, subtitle = stringResource(R.string.ui_proxy_block_sub))
                }
            }
            item(key = "flow") { Note(stringResource(R.string.set_dual_flow), Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.m)) }
        }
    }
}

@Composable
private fun Hop(
    title: Int, host: String, port: Int, user: String, pass: String,
    onHost: (String) -> Unit, onPort: (Int) -> Unit, onUser: (String) -> Unit, onPass: (String) -> Unit, tag: String,
) {
    SectionHeader(stringResource(title))
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen),
    ) {
        TokenField(
            value = host, onValue = onHost, label = stringResource(R.string.set_host),
            error = if (host.isNotBlank() && !AdvancedLogic.isValidProxyHost(host)) stringResource(R.string.ui_proxy_host_invalid) else null,
            keyboard = KeyboardType.Uri, modifier = Modifier.testTag(TAG_PROXY_HOST + tag),
        )
        // The typed text is kept here; only a valid port is saved (AdvancedLogic.parsePort).
        var portDraft by rememberSaveable(port) { mutableStateOf(port.toString()) }
        TokenField(
            value = portDraft,
            onValue = { text -> if (text.length <= 5 && text.all(Char::isDigit)) { portDraft = text; AdvancedLogic.parsePort(text)?.let(onPort) } },
            label = stringResource(R.string.set_port),
            error = if (AdvancedLogic.parsePort(portDraft) == null) stringResource(R.string.ui_proxy_port_invalid) else null,
            keyboard = KeyboardType.Number, modifier = Modifier.testTag(TAG_PROXY_PORT + tag),
        )
        TokenField(value = user, onValue = onUser, label = stringResource(R.string.set_username_opt))
        TokenField(value = pass, onValue = onPass, label = stringResource(R.string.set_password_opt), password = true)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Analysis
// ─────────────────────────────────────────────────────────────────────────────

data class AnalysisNav(
    val onBack: () -> Unit = {},
    val onOpenSecurityAnalysis: () -> Unit = {},
    val onOpenStatistics: () -> Unit = {},
    val onOpenAlerts: () -> Unit = {},
    val onOpenSecuritySystems: () -> Unit = {},
    val onOpenAdPrivacy: () -> Unit = {},
    val onOpenAds: () -> Unit = {},
)

/**
 * Advanced › Analysis: every analysis screen of 1.0, one row each, plus the two settings
 * that belong to them (certificate checks, a report now). The screens behind the rows are
 * the 1.0 screens, unchanged (restyled in Phase 10).
 */
@Composable
fun AnalysisScreen(nav: AnalysisNav) {
    val context = LocalContext.current
    val prefs = remember(context) { SettingsPreferences.getInstance(context) }
    val ct by prefs.ctMonitoringEnabled.collectAsStateWithLifecycle()
    val started = stringResource(R.string.ui_an_report_started)
    AnalysisContent(nav, ct, onCt = prefs::setCtMonitoringEnabled, onReportNow = {
        WeeklyReportWorker.generateNow(context)
        Toast.makeText(context, started, Toast.LENGTH_SHORT).show()
    })
}

@Composable
fun AnalysisContent(nav: AnalysisNav, ctEnabled: Boolean, onCt: (Boolean) -> Unit, onReportNow: () -> Unit) {
    AdvancedPage(stringResource(R.string.ui_adv_analysis), nav.onBack) {
        item(key = "rows") {
            SettingsCard(Modifier.padding(top = CoreLockTheme.spacing.s)) {
                SettingRow(stringResource(R.string.ui_an_stats), nav.onOpenStatistics, subtitle = stringResource(R.string.ui_an_stats_sub), modifier = Modifier.testTag(TAG_AN_STATS))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_alerts), nav.onOpenAlerts, subtitle = stringResource(R.string.ui_an_alerts_sub), modifier = Modifier.testTag(TAG_AN_ALERTS))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_systems), nav.onOpenSecuritySystems, subtitle = stringResource(R.string.ui_an_systems_sub), modifier = Modifier.testTag(TAG_AN_SYSTEMS))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_security), nav.onOpenSecurityAnalysis, subtitle = stringResource(R.string.set_nav_security_desc), modifier = Modifier.testTag(TAG_AN_SECURITY))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_ad_privacy), nav.onOpenAdPrivacy, subtitle = stringResource(R.string.adp_nav_subtitle), modifier = Modifier.testTag(TAG_AN_AD_PRIVACY))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_ads), nav.onOpenAds, subtitle = stringResource(R.string.set_nav_ads_desc), modifier = Modifier.testTag(TAG_AN_ADS))
            }
        }
        item(key = "settings") {
            SettingsCard(Modifier.padding(top = CoreLockTheme.spacing.xl)) {
                PrimaryToggleRow(stringResource(R.string.ui_an_ct), ctEnabled, onCt, subtitle = stringResource(R.string.ui_an_ct_sub), modifier = Modifier.testTag(TAG_AN_CT))
                SettingsDivider()
                SettingRow(stringResource(R.string.ui_an_report), onReportNow, subtitle = stringResource(R.string.ui_an_report_sub), modifier = Modifier.testTag(TAG_AN_REPORT))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Diagnostics
// ─────────────────────────────────────────────────────────────────────────────

data class DiagnosticsState(
    val checks: List<DiagnosticCheck> = emptyList(),
    val running: Boolean = false,
    val posture: SecurityPosture = SecurityPosture(),
    /** Last successful download per downloaded list; 0 = never. */
    val listTimes: List<Pair<BlocklistSource, Long>> = emptyList(),
    val namesPerList: List<SourceStats> = emptyList(),
    val totalNames: Int = 0,
    val battery: BatteryCheck.State = BatteryCheck.State.UNRESTRICTED,
)

data class DiagnosticsActions(
    val onBack: () -> Unit = {},
    val onRunChecks: () -> Unit = {},
    val onRefreshLists: () -> Unit = {},
    val onOpenBattery: () -> Unit = {},
)

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val security by vm.securityState.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnosticsState.collectAsStateWithLifecycle()
    val total by BlocklistManager.size.collectAsStateWithLifecycle()
    // Changed in system settings or by a download: read again on every resume.
    var battery by remember { mutableStateOf(BatteryCheck.state(context)) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        battery = BatteryCheck.state(context)
        resumes++
        onPauseOrDispose { }
    }
    val times by produceState(emptyList<Pair<BlocklistSource, Long>>(), resumes, total) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t = PrefsBlocklistUpdateTimes(context)
            SettingsLogic.DOWNLOADED_LISTS.map { it to t.lastSuccess(it) }
        }
    }
    val perList by produceState(emptyList<SourceStats>(), total) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.privacyguard.app.data.db.AppDatabase.getInstance(context).blocklistDao().getStatsBySource()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Diagnostics", "List counts failed: ${e.javaClass.simpleName}")
                emptyList()
            }
        }
    }
    val refreshStarted = stringResource(R.string.set_refresh_started)
    DiagnosticsContent(
        DiagnosticsState(diagnostics.checks, diagnostics.isRunning, security.posture, times, perList, total, battery),
        DiagnosticsActions(
            onBack = onBack,
            onRunChecks = vm::runDiagnostics,
            onRefreshLists = {
                BlocklistUpdateWorker.updateNow(context)
                Toast.makeText(context, refreshStarted, Toast.LENGTH_SHORT).show()
            },
            onOpenBattery = { BatteryCheck.openSettings(context) },
        ),
    )
}

@Composable
fun DiagnosticsContent(state: DiagnosticsState, actions: DiagnosticsActions, now: Long = System.currentTimeMillis()) {
    val spacing = CoreLockTheme.spacing
    AdvancedPage(stringResource(R.string.ui_adv_diagnostics), actions.onBack) {
        group("network", R.string.ui_diag_network) {
            Note(stringResource(R.string.ui_diag_network_sub), Modifier.padding(horizontal = spacing.l, vertical = spacing.m))
            when {
                state.checks.isEmpty() && !state.running -> Note(stringResource(R.string.ui_diag_not_run), Modifier.padding(horizontal = spacing.l))
                else -> state.checks.forEach { CheckRow(it.label, it.status, it.detail) }
            }
            PrimaryButton(
                stringResource(if (state.running) R.string.ui_diag_running else R.string.ui_diag_run), actions.onRunChecks,
                Modifier.fillMaxWidth().padding(spacing.l).testTag(TAG_DIAG_RUN), enabled = !state.running,
            )
        }

        group("integrity", R.string.ui_diag_integrity) {
            Note(stringResource(R.string.ui_diag_integrity_sub), Modifier.padding(horizontal = spacing.l, vertical = spacing.m))
            IntegrityRows(state.posture)
        }

        group("lists", R.string.ui_diag_lists) {
            state.listTimes.forEach { (source, at) ->
                CheckRow(
                    AdvancedLogic.listName(source),
                    if (at > 0) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                    if (at > 0) stringResource(R.string.ui_diag_list_updated, relativeTime(at, now)) else stringResource(R.string.ui_diag_list_never),
                )
            }
            Note(stringResource(R.string.set_domains_loaded, java.text.NumberFormat.getIntegerInstance().format(state.totalNames)), Modifier.padding(horizontal = spacing.l, vertical = spacing.m))
            if (state.namesPerList.isNotEmpty()) {
                Text(stringResource(R.string.ui_diag_names), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = spacing.l))
                val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
                state.namesPerList.forEach { src ->
                    val name = when (val list = AdvancedLogic.storedList(src.source)) {
                        is AdvancedLogic.StoredList.Named -> list.name
                        is AdvancedLogic.StoredList.Unknown -> list.key
                        is AdvancedLogic.StoredList.Snapshot -> stringResource(R.string.ui_diag_list_snapshot, list.name, list.date)
                        is AdvancedLogic.StoredList.BuiltIn -> stringResource(
                            R.string.ui_diag_builtin,
                            stringResource(
                                when (list.category) {
                                    AdvancedLogic.BuiltInCategory.ADS -> R.string.ads_cat_ads
                                    AdvancedLogic.BuiltInCategory.TRACKERS -> R.string.ads_cat_trackers
                                    AdvancedLogic.BuiltInCategory.TELEMETRY -> R.string.ads_cat_telemetry
                                    AdvancedLogic.BuiltInCategory.MALWARE -> R.string.ads_cat_malware
                                },
                            ),
                        )
                    }
                    Note(
                        stringResource(R.string.ui_diag_list_count, name, pluralStringResource(R.plurals.ui_diag_names_n, src.count, numbers.format(src.count))),
                        Modifier.padding(horizontal = spacing.l, vertical = 2.dp),
                    )
                }
                if (state.namesPerList.size > 1) Note(stringResource(R.string.set_lists_overlap), Modifier.padding(horizontal = spacing.l, vertical = spacing.s))
            }
            SecondaryButton(stringResource(R.string.set_refresh_now), actions.onRefreshLists, Modifier.fillMaxWidth().padding(spacing.l).testTag(TAG_DIAG_REFRESH))
        }

        group("battery", R.string.ui_diag_battery) {
            CheckRow(
                stringResource(R.string.ui_diag_battery),
                when (state.battery) {
                    BatteryCheck.State.UNRESTRICTED -> DiagnosticStatus.PASS
                    BatteryCheck.State.OPTIMIZED -> DiagnosticStatus.WARN
                    BatteryCheck.State.RESTRICTED -> DiagnosticStatus.FAIL
                },
                stringResource(
                    when (state.battery) {
                        BatteryCheck.State.UNRESTRICTED -> R.string.ui_diag_battery_ok
                        BatteryCheck.State.OPTIMIZED -> R.string.diag_battery_optimized
                        BatteryCheck.State.RESTRICTED -> R.string.diag_battery_restricted
                    },
                ),
            )
            SecondaryButton(stringResource(R.string.set_open_battery_settings), actions.onOpenBattery, Modifier.fillMaxWidth().padding(spacing.l).testTag(TAG_DIAG_BATTERY))
        }
    }
}

/** The 1.0 runtime-integrity rows, same checks and texts, as status rows. */
@Composable
private fun IntegrityRows(posture: SecurityPosture) {
    CheckRow(
        stringResource(R.string.set_runtime_posture),
        when (posture.riskLevel) {
            SecurityRiskLevel.HIGH -> DiagnosticStatus.FAIL
            SecurityRiskLevel.ELEVATED -> DiagnosticStatus.WARN
            else -> DiagnosticStatus.PASS
        },
        stringResource(
            when (posture.riskLevel) {
                SecurityRiskLevel.HIGH -> R.string.set_posture_high
                SecurityRiskLevel.ELEVATED -> R.string.set_posture_elevated
                else -> R.string.set_posture_low
            },
        ),
    )
    CheckRow(
        stringResource(R.string.set_app_signature),
        if (posture.signatureValid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
        stringResource(if (posture.signatureValid) R.string.set_signature_ok else R.string.set_signature_bad),
    )
    CheckRow(
        stringResource(R.string.set_signing_mode),
        if (BuildConfig.IS_RELEASE_SIGNING_CONFIGURED || BuildConfig.DEBUG) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
        BuildConfig.APP_SIGNING_MODE,
    )
    CheckRow(
        stringResource(R.string.set_root_tamper),
        if (!posture.rooted && posture.suspiciousPackages.isEmpty()) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
        when {
            posture.rooted && posture.suspiciousPackages.isNotEmpty() -> stringResource(R.string.set_root_and_tools, posture.suspiciousPackages.joinToString())
            posture.rooted -> stringResource(R.string.set_root)
            posture.suspiciousPackages.isNotEmpty() -> stringResource(R.string.set_tools, posture.suspiciousPackages.joinToString())
            else -> stringResource(R.string.set_no_root)
        },
    )
    CheckRow(
        stringResource(R.string.set_debugger),
        if (posture.debuggerAttached) DiagnosticStatus.FAIL else DiagnosticStatus.PASS,
        stringResource(if (posture.debuggerAttached) R.string.set_debugger_on else R.string.set_debugger_off),
    )
    CheckRow(
        stringResource(R.string.set_emulator),
        if (posture.emulatorDetected) DiagnosticStatus.WARN else DiagnosticStatus.PASS,
        stringResource(if (posture.emulatorDetected) R.string.set_emulator_on else R.string.set_emulator_off),
    )
    CheckRow(
        stringResource(R.string.set_keystore),
        if (posture.hardwareBackedKeystore) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
        stringResource(if (posture.hardwareBackedKeystore) R.string.set_keystore_hw else R.string.set_keystore_sw),
    )
}

/**
 * One check: label, detail, and its result as an icon plus a word ("Pass", "Warn",
 * "Fail"), never colour alone. Replaces 1.0's ALL CAPS pill.
 */
@Composable
internal fun CheckRow(label: String, status: DiagnosticStatus, detail: String) {
    val colors = CoreLockTheme.colors
    val (icon, tint, word) = when (status) {
        DiagnosticStatus.PASS -> Triple(Icons.Rounded.CheckCircle, colors.protected, R.string.set_status_pass)
        DiagnosticStatus.WARN -> Triple(Icons.Rounded.Warning, colors.attention, R.string.set_status_warn)
        DiagnosticStatus.FAIL -> Triple(Icons.Rounded.Error, colors.danger, R.string.set_status_fail)
        DiagnosticStatus.RUNNING -> Triple(Icons.Rounded.HourglassTop, colors.onSurfaceMuted, R.string.set_status_running)
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(CoreLockTheme.spacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        }
        Spacer(Modifier.width(CoreLockTheme.spacing.s))
        Text(stringResource(word), style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AdvancedPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(title = title, onBack = onBack, subtitle = subtitle)
        LazyColumn(Modifier.fillMaxSize().testTag(TAG_ADV_LIST)) {
            content()
            item(key = "bottom") { Spacer(Modifier.padding(bottom = CoreLockTheme.spacing.xxxl)) }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = CoreLockTheme.colors.onSurfaceMuted,
    modifier = modifier,
)

@Composable
private fun TokenField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    val colors = CoreLockTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        shape = CoreLockTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            cursorColor = colors.primary,
            errorBorderColor = colors.danger,
            errorSupportingTextColor = colors.danger,
            errorLabelColor = colors.danger,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

internal const val TAG_ADV_LIST = "adv_list"
internal const val TAG_ADV_NETWORK = "adv_network"
internal const val TAG_ADV_HTTPS = "adv_https"
internal const val TAG_ADV_ANALYSIS = "adv_analysis"
internal const val TAG_ADV_DIAGNOSTICS = "adv_diagnostics"
internal const val TAG_NET_DOH = "net_doh"
internal const val TAG_NET_DOH_NOTE = "net_doh_note"
internal const val TAG_NET_DNS = "net_dns"
internal const val TAG_NET_DNS_FIELD = "net_dns_field"
internal const val TAG_NET_DNS_USE = "net_dns_use"
internal const val TAG_NET_QUIC = "net_quic"
internal const val TAG_NET_ECH = "net_ech"
internal const val TAG_NET_PROXY = "net_proxy"
internal const val TAG_PROXY_SWITCH = "proxy_switch"
internal const val TAG_PROXY_INCOMPLETE = "proxy_incomplete"
internal const val TAG_PROXY_HOST = "proxy_host:"
internal const val TAG_PROXY_PORT = "proxy_port:"
internal const val TAG_AN_STATS = "an_stats"
internal const val TAG_AN_ALERTS = "an_alerts"
internal const val TAG_AN_SYSTEMS = "an_systems"
internal const val TAG_AN_SECURITY = "an_security"
internal const val TAG_AN_AD_PRIVACY = "an_ad_privacy"
internal const val TAG_AN_ADS = "an_ads"
internal const val TAG_AN_CT = "an_ct"
internal const val TAG_AN_REPORT = "an_report"
internal const val TAG_DIAG_RUN = "diag_run"
internal const val TAG_DIAG_REFRESH = "diag_refresh"
internal const val TAG_DIAG_BATTERY = "diag_battery"
