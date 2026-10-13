package com.privacyguard.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import com.privacyguard.app.vpn.KillSwitch
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.ToggleChip
import com.privacyguard.app.ui.security.rememberProtectedActionRunner
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import com.privacyguard.app.workers.BlocklistUpdateWorker
import com.privacyguard.app.workers.WeeklyReportWorker
import com.privacyguard.core.filter.FilterEngine
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    onLanguageChanged: () -> Unit,
    onOpenSecurityAnalysis: () -> Unit = {},
    onOpenAds: () -> Unit = {},
    onOpenAdPrivacy: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenPayloads: (() -> Unit)? = null,
    onOpenFlutterUi: (() -> Unit)? = null,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }
    val notificationsEnabled by settingsPreferences.notificationsEnabled.collectAsStateWithLifecycle()
    val weeklyReport by settingsPreferences.weeklyReport.collectAsStateWithLifecycle()
    val dohEnabled by settingsPreferences.dohEnabled.collectAsStateWithLifecycle()
    val ctMonitoringEnabled by settingsPreferences.ctMonitoringEnabled.collectAsStateWithLifecycle()
    val externalIpCheckEnabled by settingsPreferences.externalIpCheckEnabled.collectAsStateWithLifecycle()
    val dohProvider by settingsPreferences.dohProvider.collectAsStateWithLifecycle()
    val retentionDays by settingsPreferences.retentionDays.collectAsStateWithLifecycle()
    val upstreamDns by settingsPreferences.upstreamDns.collectAsStateWithLifecycle()
    val protectionLevel by settingsPreferences.protectionLevel.collectAsStateWithLifecycle()
    val blocklistSize by BlocklistManager.size.collectAsStateWithLifecycle()
    val blocklistSources by produceState(emptyList<com.privacyguard.app.data.db.SourceStats>(), blocklistSize) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.privacyguard.app.data.db.AppDatabase.getInstance(context).blocklistDao().getStatsBySource() }.getOrDefault(emptyList())
        }
    }
    val securityState by settingsViewModel.securityState.collectAsStateWithLifecycle()
    val diagnosticsState by settingsViewModel.diagnosticsState.collectAsStateWithLifecycle()
    val runProtectedAction = rememberProtectedActionRunner()
    var showLanguagePicker by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showLanguagePicker) LanguagePickerDialog(onDismiss = { showLanguagePicker = false })

    // Dual VPN state
    val dualVpnEnabled by settingsPreferences.dualVpnEnabled.collectAsStateWithLifecycle()
    val dualVpnHop1Host by settingsPreferences.dualVpnHop1Host.collectAsStateWithLifecycle()
    val dualVpnHop1Port by settingsPreferences.dualVpnHop1Port.collectAsStateWithLifecycle()
    val dualVpnHop1User by settingsPreferences.dualVpnHop1User.collectAsStateWithLifecycle()
    val dualVpnHop1Pass by settingsPreferences.dualVpnHop1Pass.collectAsStateWithLifecycle()
    val dualVpnHop2Host by settingsPreferences.dualVpnHop2Host.collectAsStateWithLifecycle()
    val dualVpnHop2Port by settingsPreferences.dualVpnHop2Port.collectAsStateWithLifecycle()
    val dualVpnHop2User by settingsPreferences.dualVpnHop2User.collectAsStateWithLifecycle()
    val dualVpnHop2Pass by settingsPreferences.dualVpnHop2Pass.collectAsStateWithLifecycle()
    val dualVpnBlockUdp by settingsPreferences.dualVpnBlockUdp.collectAsStateWithLifecycle()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = stringResource(R.string.set_title),
                subtitle = stringResource(R.string.set_subtitle)
            ) {
                SectionLabel(stringResource(R.string.set_protection_level))
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectablePill(stringResource(R.string.set_level_minimal), protectionLevel == FilterEngine.BlockLevel.MINIMAL.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.MINIMAL.name)
                    }
                    SelectablePill(stringResource(R.string.set_level_standard), protectionLevel == FilterEngine.BlockLevel.STANDARD.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.STANDARD.name)
                    }
                    SelectablePill(stringResource(R.string.set_level_strict), protectionLevel == FilterEngine.BlockLevel.STRICT.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.STRICT.name)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.set_current_level, stringResource(when (protectionLevel) {
                        FilterEngine.BlockLevel.MINIMAL.name -> R.string.set_level_minimal
                        FilterEngine.BlockLevel.STRICT.name -> R.string.set_level_strict
                        else -> R.string.set_level_standard
                    })),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(com.privacyguard.app.ui.dashboard.protectionLevelDescription(protectionLevel)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
        }

        item {
            SettingBlock(
                title = stringResource(R.string.set_security_rules),
                items = listOf(
                    SettingUiItem(stringResource(R.string.set_block_cleartext), stringResource(R.string.set_block_cleartext_desc), securityState.blockCleartext, Icons.Default.Lock, onClick = {
                        settingsViewModel.toggleBlockCleartext(!securityState.blockCleartext)
                    }),
                    SettingUiItem(stringResource(R.string.set_block_weak_tls), stringResource(R.string.set_block_weak_tls_desc), securityState.blockWeakTls, Icons.Default.Lock, onClick = {
                        settingsViewModel.toggleBlockWeakTls(!securityState.blockWeakTls)
                    }),
                    SettingUiItem(
                        stringResource(R.string.set_kill_switch),
                        if (KillSwitch.lockdownActive) stringResource(R.string.set_kill_switch_on)
                        else stringResource(R.string.set_kill_switch_off),
                        KillSwitch.lockdownActive, Icons.Default.Lock, onClick = {
                            KillSwitch.openAlwaysOnSettings(context)
                        }),
                    SettingUiItem(stringResource(R.string.set_vpn_drop_alert), stringResource(R.string.set_vpn_drop_alert_desc), securityState.killSwitch, Icons.Default.Warning, onClick = {
                        settingsViewModel.toggleKillSwitch(!securityState.killSwitch)
                    }),
                    SettingUiItem(stringResource(R.string.set_notifications), stringResource(R.string.set_notifications_desc), notificationsEnabled, Icons.Default.Notifications, onClick = {
                        settingsPreferences.setNotificationsEnabled(!notificationsEnabled)
                    }),
                    SettingUiItem(stringResource(R.string.set_weekly_report), stringResource(R.string.set_weekly_report_desc), weeklyReport, Icons.Default.Notifications, onClick = {
                        val enabled = !weeklyReport
                        settingsPreferences.setWeeklyReport(enabled)
                        if (enabled) {
                            WeeklyReportWorker.scheduleWeekly(context)
                        } else {
                            WeeklyReportWorker.cancelReports(context)
                        }
                    }),
                    SettingUiItem(stringResource(R.string.set_doh), stringResource(R.string.set_doh_desc), dohEnabled, Icons.Default.Lock, onClick = {
                        settingsPreferences.setDohEnabled(!dohEnabled)
                    }),
                    SettingUiItem(stringResource(R.string.set_ct), stringResource(R.string.set_ct_desc), ctMonitoringEnabled, Icons.Default.Warning, onClick = {
                        settingsPreferences.setCtMonitoringEnabled(!ctMonitoringEnabled)
                    }),
                    SettingUiItem(stringResource(R.string.set_external_ip), stringResource(R.string.set_external_ip_desc), externalIpCheckEnabled, Icons.Default.Warning, onClick = {
                        settingsPreferences.setExternalIpCheckEnabled(!externalIpCheckEnabled)
                    })
                ),
                // The drop alert only notifies; Android's Always-on VPN is what keeps
                // protection on and blocks traffic without it.
                footer = {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.set_always_on_hint), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { KillSwitch.openAlwaysOnSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.set_open_vpn_settings)) }
                },
            )
        }

        item {
            val posture = securityState.posture
            SettingBlock(
                title = stringResource(R.string.set_runtime_integrity),
                footer = {
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_runtime_posture),
                            when (posture.riskLevel.name) {
                                "HIGH" -> DiagnosticStatus.FAIL
                                "ELEVATED" -> DiagnosticStatus.WARN
                                else -> DiagnosticStatus.PASS
                            },
                            stringResource(when (posture.riskLevel.name) {
                                "HIGH" -> R.string.set_posture_high
                                "ELEVATED" -> R.string.set_posture_elevated
                                else -> R.string.set_posture_low
                            }),
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_app_signature),
                            if (posture.signatureValid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                            if (posture.signatureValid) stringResource(R.string.set_signature_ok) else stringResource(R.string.set_signature_bad),
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_signing_mode),
                            if (BuildConfig.IS_RELEASE_SIGNING_CONFIGURED || BuildConfig.DEBUG) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                            BuildConfig.APP_SIGNING_MODE,
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_root_tamper),
                            if (!posture.rooted && posture.suspiciousPackages.isEmpty()) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                            when {
                                posture.rooted && posture.suspiciousPackages.isNotEmpty() ->
                                    stringResource(R.string.set_root_and_tools, posture.suspiciousPackages.joinToString())
                                posture.rooted -> stringResource(R.string.set_root)
                                posture.suspiciousPackages.isNotEmpty() -> stringResource(R.string.set_tools, posture.suspiciousPackages.joinToString())
                                else -> stringResource(R.string.set_no_root)
                            },
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_debugger),
                            if (posture.debuggerAttached) DiagnosticStatus.FAIL else DiagnosticStatus.PASS,
                            if (posture.debuggerAttached) stringResource(R.string.set_debugger_on) else stringResource(R.string.set_debugger_off),
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_emulator),
                            if (posture.emulatorDetected) DiagnosticStatus.WARN else DiagnosticStatus.PASS,
                            if (posture.emulatorDetected) stringResource(R.string.set_emulator_on) else stringResource(R.string.set_emulator_off),
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            stringResource(R.string.set_keystore),
                            if (posture.hardwareBackedKeystore) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                            if (posture.hardwareBackedKeystore) stringResource(R.string.set_keystore_hw) else stringResource(R.string.set_keystore_sw),
                        )
                    )
                },
                items = listOf(
                    SettingUiItem(
                        stringResource(R.string.set_hardening),
                        stringResource(R.string.set_hardening_desc),
                        securityState.posture.riskLevel.name != "HIGH",
                        Icons.Default.Security,
                        onClick = { settingsViewModel.runDiagnostics() }
                    )
                )
            )
        }

        item {
            SettingBlock(
                title = stringResource(R.string.set_upstream_dns),
                footer = {
                    // Edited as a draft; only a complete IP address is saved. Saving
                    // every keystroke stored half-typed values ("1.1.") as the resolver.
                    var dnsDraft by remember(upstreamDns) { mutableStateOf(upstreamDns) }
                    OutlinedTextField(
                        value = dnsDraft,
                        onValueChange = { value ->
                            if (value.length <= 45 && value.all { it.isLetterOrDigit() || it == '.' || it == ':' }) {
                                dnsDraft = value
                                if (com.privacyguard.vpn.forwarder.DnsHandler.isIpLiteral(value)) {
                                    settingsPreferences.setUpstreamDns(value)
                                }
                            }
                        },
                        isError = !com.privacyguard.vpn.forwarder.DnsHandler.isIpLiteral(dnsDraft),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.titleMedium,
                        label = { Text(stringResource(R.string.set_resolver)) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectablePill("Cloudflare", upstreamDns == "1.1.1.1") {
                            settingsPreferences.setUpstreamDns("1.1.1.1")
                            settingsPreferences.setDohProvider(SettingsPreferences.DOH_CLOUDFLARE)
                        }
                        SelectablePill("Google", upstreamDns == "8.8.8.8") {
                            settingsPreferences.setUpstreamDns("8.8.8.8")
                            settingsPreferences.setDohProvider(SettingsPreferences.DOH_GOOGLE)
                        }
                        SelectablePill("Quad9", upstreamDns == "9.9.9.9") {
                            settingsPreferences.setUpstreamDns("9.9.9.9")
                            settingsPreferences.setDohProvider(SettingsPreferences.DOH_QUAD9)
                        }
                        SelectablePill(stringResource(R.string.set_custom), upstreamDns !in setOf("1.1.1.1", "8.8.8.8", "9.9.9.9")) { }
                    }
                    if (dohEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(stringResource(R.string.set_doh_provider, providerLabel(dohProvider)), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                },
                items = listOf(
                    SettingUiItem(stringResource(R.string.settings_language), languageDisplayName(currentAppLanguageTag()), true, Icons.Default.Language, onClick = { showLanguagePicker = true })
                )
            )
        }

        item {
            SettingBlock(
                title = stringResource(R.string.set_diagnostics),
                footer = {
                    Spacer(modifier = Modifier.height(12.dp))
                    diagnosticsState.checks.forEach { check ->
                        DiagnosticRow(check)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                },
                items = listOf(
                    SettingUiItem(
                        stringResource(R.string.set_run_checks),
                        if (diagnosticsState.isRunning) stringResource(R.string.set_checks_running) else stringResource(R.string.set_checks_idle),
                        !diagnosticsState.isRunning,
                        Icons.Default.NetworkCheck,
                        onClick = {
                            settingsViewModel.runDiagnostics()
                            Toast.makeText(context, resources.getString(R.string.set_diagnostics_started), Toast.LENGTH_SHORT).show()
                        }
                    )
                )
            )
        }

        item {
            SettingBlock(
                title = stringResource(R.string.set_blocklists),
                footer = {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(stringResource(R.string.set_domains_loaded, java.text.NumberFormat.getIntegerInstance().format(blocklistSize)), style = MaterialTheme.typography.titleMedium, color = PgAccent)
                    if (blocklistSources.size > 1) {
                        Text(stringResource(R.string.set_lists_overlap), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PanelCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    BlocklistUpdateWorker.updateNow(context)
                                    Toast.makeText(context, resources.getString(R.string.set_refresh_started), Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(stringResource(R.string.set_refresh_now), style = MaterialTheme.typography.titleMedium, color = PgAccent)
                        }
                        PanelCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    WeeklyReportWorker.generateNow(context)
                                    Toast.makeText(context, resources.getString(R.string.set_report_requested), Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(stringResource(R.string.set_run_report), style = MaterialTheme.typography.titleMedium, color = PgAccent)
                        }
                    }
                },
                // Real per-source counts from the database (they used to be hardcoded
                // "~145K / ~180K" next to a total of 80 when the lists had not downloaded).
                items = if (blocklistSources.isEmpty()) listOf(
                    SettingUiItem(stringResource(R.string.set_lists_none), stringResource(R.string.set_lists_none_desc), false, Icons.Default.Storage, onClick = { })
                ) else blocklistSources.map { src ->
                    SettingUiItem(src.source, pluralStringResource(R.plurals.set_list_domains, src.count, src.count), true, Icons.Default.Storage, onClick = { })
                }
            )
        }

        item {
            SettingBlock(
                title = stringResource(R.string.set_data_management),
                footer = {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30, 90).forEach { days ->
                            SelectablePill(pluralStringResource(R.plurals.set_days_short, days, days), retentionDays == days) {
                                settingsPreferences.setRetentionDays(days)
                            }
                        }
                    }
                },
                items = listOf(
                    SettingUiItem(stringResource(R.string.set_retention), pluralStringResource(R.plurals.set_days_short, retentionDays, retentionDays), true, Icons.Default.Storage, onClick = { }),
                    SettingUiItem(stringResource(R.string.set_clear_data), stringResource(R.string.set_danger_zone), false, Icons.Default.Warning, onClick = {
                        runProtectedAction(
                            resources.getString(R.string.set_clear_confirm_title),
                            resources.getString(R.string.set_clear_confirm_body),
                        ) {
                            settingsViewModel.clearConnectionData()
                            Toast.makeText(context, resources.getString(R.string.set_cleared), Toast.LENGTH_SHORT).show()
                        }
                    }, danger = true)
                )
            )
        }
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.set_analysis).uppercase(), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(12.dp))
                NavRow(stringResource(R.string.set_nav_security), stringResource(R.string.set_nav_security_desc), Icons.Default.Security, PgAccent, onOpenSecurityAnalysis)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tls_fp_settings_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NavRow(stringResource(R.string.set_nav_ads), stringResource(R.string.set_nav_ads_desc), Icons.Default.MonetizationOn, PgWarning, onOpenAds)
                Spacer(modifier = Modifier.height(12.dp))
                NavRow(
                    stringResource(R.string.adp_title),
                    stringResource(R.string.adp_nav_subtitle),
                    androidx.compose.material.icons.Icons.Filled.Mic, PgInfo, onOpenAdPrivacy,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NavRow(stringResource(R.string.set_nav_rules), stringResource(R.string.set_nav_rules_desc), Icons.Default.Block, PgDanger, onOpenRules)
                if (onOpenPayloads != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    NavRow(stringResource(R.string.set_nav_payloads), stringResource(R.string.set_nav_payloads_desc), Icons.Default.Search, PgInfo, onOpenPayloads)
                }
                if (onOpenFlutterUi != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    NavRow(stringResource(R.string.set_nav_flutter), stringResource(R.string.set_nav_flutter_desc), Icons.Default.PhoneAndroid, PgAccent, onOpenFlutterUi)
                }
            }
        }

        // Dual VPN section
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.set_dual_vpn).uppercase(), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { settingsPreferences.setDualVpnEnabled(!dualVpnEnabled) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_dual_enable), style = MaterialTheme.typography.titleMedium, color = PgText)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.set_dual_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                    }
                    ToggleChip(dualVpnEnabled)
                }

                if (dualVpnEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(stringResource(R.string.set_hop1), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop1Host,
                            onValueChange = { settingsPreferences.setDualVpnHop1Host(it) },
                            label = { Text(stringResource(R.string.set_host)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop1Port.toString(),
                            onValueChange = { settingsPreferences.setDualVpnHop1Port(it.toIntOrNull() ?: 1080) },
                            label = { Text(stringResource(R.string.set_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop1User,
                            onValueChange = { settingsPreferences.setDualVpnHop1User(it) },
                            label = { Text(stringResource(R.string.set_username_opt)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop1Pass,
                            onValueChange = { settingsPreferences.setDualVpnHop1Pass(it) },
                            label = { Text(stringResource(R.string.set_password_opt)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(stringResource(R.string.set_hop2), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop2Host,
                            onValueChange = { settingsPreferences.setDualVpnHop2Host(it) },
                            label = { Text(stringResource(R.string.set_host)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop2Port.toString(),
                            onValueChange = { settingsPreferences.setDualVpnHop2Port(it.toIntOrNull() ?: 1080) },
                            label = { Text(stringResource(R.string.set_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop2User,
                            onValueChange = { settingsPreferences.setDualVpnHop2User(it) },
                            label = { Text(stringResource(R.string.set_username_opt)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop2Pass,
                            onValueChange = { settingsPreferences.setDualVpnHop2Pass(it) },
                            label = { Text(stringResource(R.string.set_password_opt)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { settingsPreferences.setDualVpnBlockUdp(!dualVpnBlockUdp) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.set_dual_block_udp), style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(stringResource(R.string.set_dual_block_udp_desc), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                        }
                        ToggleChip(dualVpnBlockUdp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.set_dual_flow),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgAccent,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        accentColor.copy(alpha = 0.15f),
                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = PgText)
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = PgTextFaint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DiagnosticRow(check: DiagnosticCheck) {
    val (background, content) = when (check.status) {
        DiagnosticStatus.PASS -> PgAccentDim to PgAccent
        DiagnosticStatus.WARN -> PgWarningDim to PgWarning
        DiagnosticStatus.FAIL -> PgDangerDim to PgDanger
        DiagnosticStatus.RUNNING -> PgInfoDim to PgInfo
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.titleSmall, color = PgText)
            Spacer(modifier = Modifier.height(2.dp))
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
        StatusPill(stringResource(when (check.status) {
            DiagnosticStatus.PASS -> R.string.set_status_pass
            DiagnosticStatus.WARN -> R.string.set_status_warn
            DiagnosticStatus.FAIL -> R.string.set_status_fail
            DiagnosticStatus.RUNNING -> R.string.set_status_running
        }).uppercase(), background, content)
    }
}

private data class SettingUiItem(
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val danger: Boolean = false
)

@Composable
private fun SettingBlock(
    title: String,
    items: List<SettingUiItem>,
    footer: @Composable (() -> Unit)? = null
) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        Spacer(modifier = Modifier.height(12.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = item.onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = if (item.danger) PgDanger else PgText)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = if (item.danger) PgDanger else PgTextMuted)
                }
                if (item.danger) {
                    StatusPill(stringResource(R.string.set_danger), PgDangerDim, PgDanger)
                } else {
                    ToggleChip(item.enabled)
                }
            }
            if (index != items.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun SelectablePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        StatusPill(
            text = label,
            background = if (selected) PgAccentDim else MaterialTheme.colorScheme.surfaceVariant,
            content = if (selected) PgAccent else PgTextMuted
        )
    }
}

private fun providerLabel(provider: String): String = when (provider) {
    SettingsPreferences.DOH_GOOGLE -> "Google"
    SettingsPreferences.DOH_QUAD9 -> "Quad9"
    else -> "Cloudflare"
}

