package com.privacyguard.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
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
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.ToggleChip
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.workers.BlocklistUpdateWorker
import com.privacyguard.app.workers.WeeklyReportWorker

@Composable
fun SettingsScreen(
    onLanguageChanged: () -> Unit,
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
    val blocklistSize by BlocklistManager.size.collectAsState()
    val securityState by settingsViewModel.securityState.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Settings",
                subtitle = "Protection controls and privacy defaults"
            ) {
                SectionLabel("Protection level")
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Minimal", background = MaterialTheme.colorScheme.surfaceVariant, content = PgTextMuted)
                    StatusPill("Standard", background = PgAccentDim, content = PgAccent)
                    StatusPill("Strict", background = MaterialTheme.colorScheme.surfaceVariant, content = PgTextMuted)
                }
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
            SettingBlock(
                title = "Upstream DNS",
                footer = {
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
                    }
                    if (dohEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("DoH provider: ${providerLabel(dohProvider)}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                },
                items = listOf(
                    SettingUiItem("Resolver", upstreamDns, true, Icons.Default.Lock, onClick = { }),
                    SettingUiItem("Language", "English", true, Icons.Default.Language, onClick = { onLanguageChanged() })
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
                        settingsViewModel.clearConnectionData()
                        Toast.makeText(context, "Connection history cleared", Toast.LENGTH_SHORT).show()
                    }, danger = true)
                )
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
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
