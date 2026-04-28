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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun SettingsScreen(
    onLanguageChanged: () -> Unit,
    onOpenSecurityAnalysis: () -> Unit = {},
    onOpenAds: () -> Unit = {},
    onOpenPayloads: (() -> Unit)? = null,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }
    val notificationsEnabled by settingsPreferences.notificationsEnabled.collectAsState()
    val weeklyReport by settingsPreferences.weeklyReport.collectAsState()
    val dohEnabled by settingsPreferences.dohEnabled.collectAsState()
    val dohProvider by settingsPreferences.dohProvider.collectAsState()
    val retentionDays by settingsPreferences.retentionDays.collectAsState()
    val upstreamDns by settingsPreferences.upstreamDns.collectAsState()
    val protectionLevel by settingsPreferences.protectionLevel.collectAsState()
    val blocklistSize by BlocklistManager.size.collectAsState()
    val securityState by settingsViewModel.securityState.collectAsState()
    val diagnosticsState by settingsViewModel.diagnosticsState.collectAsState()
    val runProtectedAction = rememberProtectedActionRunner()

    // Dual VPN state
    val dualVpnEnabled by settingsPreferences.dualVpnEnabled.collectAsState()
    val dualVpnHop1Host by settingsPreferences.dualVpnHop1Host.collectAsState()
    val dualVpnHop1Port by settingsPreferences.dualVpnHop1Port.collectAsState()
    val dualVpnHop1User by settingsPreferences.dualVpnHop1User.collectAsState()
    val dualVpnHop1Pass by settingsPreferences.dualVpnHop1Pass.collectAsState()
    val dualVpnHop2Host by settingsPreferences.dualVpnHop2Host.collectAsState()
    val dualVpnHop2Port by settingsPreferences.dualVpnHop2Port.collectAsState()
    val dualVpnHop2User by settingsPreferences.dualVpnHop2User.collectAsState()
    val dualVpnHop2Pass by settingsPreferences.dualVpnHop2Pass.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Settings",
                subtitle = "Protection controls and privacy defaults"
            ) {
                SectionLabel("Protection level")
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectablePill("Minimal", protectionLevel == FilterEngine.BlockLevel.MINIMAL.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.MINIMAL.name)
                    }
                    SelectablePill("Standard", protectionLevel == FilterEngine.BlockLevel.STANDARD.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.STANDARD.name)
                    }
                    SelectablePill("Strict", protectionLevel == FilterEngine.BlockLevel.STRICT.name) {
                        settingsPreferences.setProtectionLevel(FilterEngine.BlockLevel.STRICT.name)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Current engine level: ${protectionLevel.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
        }

        item {
            SettingBlock(
                title = "Security rules",
                items = listOf(
                    SettingUiItem("Block Cleartext (HTTP)", "Deny unencrypted connections", securityState.blockCleartext, Icons.Default.Lock, onClick = {
                        settingsViewModel.toggleBlockCleartext(!securityState.blockCleartext)
                    }),
                    SettingUiItem("Block Weak TLS", "Deny deprecated TLS 1.0 and 1.1", securityState.blockWeakTls, Icons.Default.Lock, onClick = {
                        settingsViewModel.toggleBlockWeakTls(!securityState.blockWeakTls)
                    }),
                    SettingUiItem("Kill Switch", "Restart protection if the VPN drops", securityState.killSwitch, Icons.Default.Warning, onClick = {
                        settingsViewModel.toggleKillSwitch(!securityState.killSwitch)
                    }),
                    SettingUiItem("Notifications", "Alerts and summaries", notificationsEnabled, Icons.Default.Notifications, onClick = {
                        settingsPreferences.setNotificationsEnabled(!notificationsEnabled)
                    }),
                    SettingUiItem("Weekly report", "Deliver privacy summary", weeklyReport, Icons.Default.Notifications, onClick = {
                        val enabled = !weeklyReport
                        settingsPreferences.setWeeklyReport(enabled)
                        if (enabled) {
                            WeeklyReportWorker.scheduleWeekly(context)
                        } else {
                            WeeklyReportWorker.cancelReports(context)
                        }
                    }),
                    SettingUiItem("DNS over HTTPS", "Encrypt DNS queries", dohEnabled, Icons.Default.Lock, onClick = {
                        settingsPreferences.setDohEnabled(!dohEnabled)
                    })
                )
            )
        }

        item {
            val posture = securityState.posture
            SettingBlock(
                title = "Runtime integrity",
                footer = {
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Runtime posture",
                            when (posture.riskLevel.name) {
                                "HIGH" -> DiagnosticStatus.FAIL
                                "ELEVATED" -> DiagnosticStatus.WARN
                                else -> DiagnosticStatus.PASS
                            },
                            posture.summary,
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "App signature",
                            if (posture.signatureValid) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                            if (posture.signatureValid) "Signature matches trusted signing certificate" else "Signature mismatch detected",
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Signing mode",
                            if (BuildConfig.IS_RELEASE_SIGNING_CONFIGURED || BuildConfig.DEBUG) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                            BuildConfig.APP_SIGNING_MODE,
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Root / tamper",
                            if (!posture.rooted && posture.suspiciousPackages.isEmpty()) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                            when {
                                posture.rooted && posture.suspiciousPackages.isNotEmpty() ->
                                    "Root indicators present · ${posture.suspiciousPackages.joinToString()}"
                                posture.rooted -> "Root indicators present on this device"
                                posture.suspiciousPackages.isNotEmpty() -> "Suspicious tooling detected: ${posture.suspiciousPackages.joinToString()}"
                                else -> "No root or tamper tooling detected"
                            },
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Debugger",
                            if (posture.debuggerAttached) DiagnosticStatus.FAIL else DiagnosticStatus.PASS,
                            if (posture.debuggerAttached) "Debugger attached to process" else "No debugger detected",
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Emulator",
                            if (posture.emulatorDetected) DiagnosticStatus.WARN else DiagnosticStatus.PASS,
                            if (posture.emulatorDetected) "Emulator-like environment detected" else "Physical device heuristics look normal",
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        DiagnosticCheck(
                            "Keystore backing",
                            if (posture.hardwareBackedKeystore) DiagnosticStatus.PASS else DiagnosticStatus.WARN,
                            if (posture.hardwareBackedKeystore) "Master key is backed by secure hardware" else "Hardware-backed keystore unavailable",
                        )
                    )
                },
                items = listOf(
                    SettingUiItem(
                        "Memory-level hardening",
                        "Keys stay in Android Keystore; risky runtimes get secure-window protection",
                        securityState.posture.riskLevel.name != "HIGH",
                        Icons.Default.Security,
                        onClick = { settingsViewModel.runDiagnostics() }
                    )
                )
            )
        }

        item {
            SettingBlock(
                title = "Upstream DNS",
                footer = {
                    OutlinedTextField(
                        value = upstreamDns,
                        onValueChange = { value ->
                            if (value.length <= 45 && value.all { it.isDigit() || it == '.' || it == ':' }) {
                                settingsPreferences.setUpstreamDns(value)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.titleMedium,
                        label = { Text("Resolver") }
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
                        SelectablePill("Custom", upstreamDns !in setOf("1.1.1.1", "8.8.8.8", "9.9.9.9")) { }
                    }
                    if (dohEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("DoH provider: ${providerLabel(dohProvider)}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                },
                items = listOf(
                    SettingUiItem("Language", "English", true, Icons.Default.Language, onClick = { onLanguageChanged() })
                )
            )
        }

        item {
            SettingBlock(
                title = "Network diagnostics",
                footer = {
                    Spacer(modifier = Modifier.height(12.dp))
                    diagnosticsState.checks.forEach { check ->
                        DiagnosticRow(check)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                },
                items = listOf(
                    SettingUiItem(
                        "Run connectivity checks",
                        if (diagnosticsState.isRunning) "Checking VPN, DNS, IPv6, and app inventory" else "Validate VPN/DNS status on this device",
                        !diagnosticsState.isRunning,
                        Icons.Default.NetworkCheck,
                        onClick = {
                            settingsViewModel.runDiagnostics()
                            Toast.makeText(context, "Diagnostics started", Toast.LENGTH_SHORT).show()
                        }
                    )
                )
            )
        }

        item {
            SettingBlock(
                title = "Blocklists",
                footer = {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Total domains loaded: $blocklistSize", style = MaterialTheme.typography.titleMedium, color = PgAccent)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PanelCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    BlocklistUpdateWorker.updateNow(context)
                                    Toast.makeText(context, "Blocklist refresh started", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text("Refresh Now", style = MaterialTheme.typography.titleMedium, color = PgAccent)
                        }
                        PanelCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    WeeklyReportWorker.generateNow(context)
                                    Toast.makeText(context, "Weekly report requested", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text("Run Report", style = MaterialTheme.typography.titleMedium, color = PgAccent)
                        }
                    }
                },
                items = listOf(
                    SettingUiItem("StevenBlack Unified", "~145K domains", true, Icons.Default.Storage, onClick = { }),
                    SettingUiItem("OISD Full", "~180K domains", true, Icons.Default.Storage, onClick = { })
                )
            )
        }

        item {
            SettingBlock(
                title = "Data management",
                footer = {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30, 90).forEach { days ->
                            SelectablePill("${days}d", retentionDays == days) {
                                settingsPreferences.setRetentionDays(days)
                            }
                        }
                    }
                },
                items = listOf(
                    SettingUiItem("Data retention", "$retentionDays days", true, Icons.Default.Storage, onClick = { }),
                    SettingUiItem("Clear all connection data", "Danger zone", false, Icons.Default.Warning, onClick = {
                        runProtectedAction(
                            "Clear connection data",
                            "Confirm access before deleting local connection history",
                        ) {
                            settingsViewModel.clearConnectionData()
                            Toast.makeText(context, "Connection history cleared", Toast.LENGTH_SHORT).show()
                        }
                    }, danger = true)
                )
            )
        }
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("ANALYSIS & INSIGHTS", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(12.dp))
                NavRow("Security Analysis", "JA3 threats, cipher alerts, certificate transparency", Icons.Default.Security, PgAccent, onOpenSecurityAnalysis)
                Spacer(modifier = Modifier.height(12.dp))
                NavRow("Ads & Trackers", "Per-app tracker detection and SDK inventory", Icons.Default.MonetizationOn, PgWarning, onOpenAds)
                if (onOpenPayloads != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    NavRow("Payload Inspector", "Intercepted HTTPS traffic logs", Icons.Default.Search, PgInfo, onOpenPayloads)
                }
            }
        }

        // Dual VPN section
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("DUAL VPN", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { settingsPreferences.setDualVpnEnabled(!dualVpnEnabled) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Dual VPN", style = MaterialTheme.typography.titleMedium, color = PgText)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Chain two SOCKS5 hops. Neither server knows both who you are and your destination.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                    }
                    ToggleChip(dualVpnEnabled)
                }

                if (dualVpnEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("First Hop (Server 1)", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop1Host,
                            onValueChange = { settingsPreferences.setDualVpnHop1Host(it) },
                            label = { Text("Host") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop1Port.toString(),
                            onValueChange = { settingsPreferences.setDualVpnHop1Port(it.toIntOrNull() ?: 1080) },
                            label = { Text("Port") },
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
                            label = { Text("Username (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop1Pass,
                            onValueChange = { settingsPreferences.setDualVpnHop1Pass(it) },
                            label = { Text("Password (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Second Hop (Server 2)", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dualVpnHop2Host,
                            onValueChange = { settingsPreferences.setDualVpnHop2Host(it) },
                            label = { Text("Host") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop2Port.toString(),
                            onValueChange = { settingsPreferences.setDualVpnHop2Port(it.toIntOrNull() ?: 1080) },
                            label = { Text("Port") },
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
                            label = { Text("Username (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = dualVpnHop2Pass,
                            onValueChange = { settingsPreferences.setDualVpnHop2Pass(it) },
                            label = { Text("Password (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Traffic: You → Hop1 → Hop2 → Internet. Neither hop has the full picture.",
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
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = PgTextFaint, modifier = Modifier.size(18.dp))
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
        StatusPill(check.status.name, background, content)
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
                    StatusPill("Danger", PgDangerDim, PgDanger)
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
