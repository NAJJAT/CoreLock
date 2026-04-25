package com.privacyguard.app.ui.dashboard

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.LabeledProgress
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

@Composable
fun DashboardScreen(
    onRequestVpn: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnManager.startVpn(context)
        }
    }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenScaffold(
                title = "Dashboard",
                subtitle = if (uiState.isVpnActive) "Protected tunnel running" else "Protection is paused",
                badge = if (uiState.isVpnActive) "LIVE" else "OFFLINE"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = PgTextMuted)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = PgTextMuted)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF0F1E30), Color(0xFF0D2819))
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("STATUS", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (uiState.isVpnActive) "Protected" else "Not protected",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = if (uiState.isVpnActive) PgAccent else PgDanger
                                    )
                                }
                                PrivacySwitch(
                                    checked = uiState.isVpnActive,
                                    onToggle = {
                                        if (uiState.isVpnActive) {
                                            VpnManager.stopVpn(context)
                                        } else {
                                            val intent = VpnManager.prepareVpn(context)
                                            if (intent != null) vpnPermissionLauncher.launch(intent) else VpnManager.startVpn(context)
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(CircleShape)
                                    .background(PgAccentDim),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x3300E5A0)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Shield, contentDescription = null, tint = PgAccent, modifier = Modifier.size(28.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            LabeledProgress(
                                label = "Encryption health",
                                valueText = "${(uiState.encryptionHealth * 100).toInt()}% TLS",
                                progress = uiState.encryptionHealth,
                                color = PgAccent
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Trackers blocked", uiState.trackersBlocked.toString(), PgAccent, Modifier.weight(1f))
                    StatTile("Cleartext conns", uiState.cleartextCount.toString(), PgDanger, Modifier.weight(1f))
                    StatTile("Domains loaded", uiState.blocklistDomains.toString(), PgInfo, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel("Recent alerts")
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (uiState.anomalies.isEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("No alerts yet", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Once traffic starts flowing, recent anomalies and blocks will appear here.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
        } else {
            items(uiState.anomalies) { anomaly ->
                val tint = if (anomaly.severity >= 8) PgDanger else PgWarning
                val bg = if (anomaly.severity >= 8) PgDangerDim else PgWarningDim
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (anomaly.severity >= 8) Icons.Default.Warning else Icons.Default.Security,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(anomaly.domain, style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("${anomaly.packageName} · ${anomaly.anomalyType}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                        }
                        Text(formatAgo(anomaly.timestamp), style = MaterialTheme.typography.labelMedium, color = PgTextFaint)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun PrivacySwitch(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) PgAccent else PgPanelStrong)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (checked) PgAccent else PgPanelStrong)
                .padding(start = if (checked) 26.dp else 0.dp, end = if (checked) 0.dp else 26.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp)
            )
        }
    }
    Spacer(
        modifier = Modifier
            .height(1.dp)
            .background(Color.Transparent)
    )
}
