package com.privacyguard.app.ui.dashboard

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.privacyguard.app.ui.components.MetricRow
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.security.rememberProtectedActionRunner
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import java.io.File

@Composable
fun DashboardScreen(
    onRequestVpn: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val runProtectedAction = rememberProtectedActionRunner()

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
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Privacy Score ${uiState.privacyScore.score}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = PgInfo,
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
                    StatTile("Privacy score", uiState.privacyScore.score.toString(), PgInfo, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel("Security systems")
                Spacer(modifier = Modifier.height(10.dp))
                SecuritySystemsGrid(
                    cards = uiState.securityCards,
                    killSwitchEnabled = uiState.securityCards.firstOrNull { it.title == "Kill Switch" }?.status == "Armed",
                    onToggleKillSwitch = { enabled -> viewModel.setKillSwitch(enabled) }
                )

                Spacer(modifier = Modifier.height(18.dp))
                WeeklyPrivacyReportCard(uiState.weeklyReport)

                Spacer(modifier = Modifier.height(14.dp))
                PcapExportCard(
                    isCapturing = uiState.isPcapCapturing,
                    path = uiState.pcapPath,
                    onToggle = {
                        runProtectedAction(
                            if (uiState.isPcapCapturing) "Stop packet capture" else "Start packet capture",
                            "Confirm access to raw packet capture controls",
                        ) {
                            viewModel.togglePcapCapture()
                        }
                    },
                    onShare = { path ->
                        runProtectedAction(
                            "Share PCAP capture",
                            "Confirm access before exporting raw network traffic",
                        ) {
                            val file = File(path)
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.tcpdump.pcap"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Export PCAP"))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel("Recommendations / Security Notes")
                Spacer(modifier = Modifier.height(10.dp))
                RecommendationsCard(uiState.recommendations)

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
private fun SecuritySystemsGrid(
    cards: List<SecurityCardState>,
    killSwitchEnabled: Boolean,
    onToggleKillSwitch: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { rowCards ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEach { card ->
                    SecuritySystemCard(
                        card = card,
                        modifier = Modifier.weight(1f),
                        killSwitchEnabled = killSwitchEnabled,
                        onToggleKillSwitch = onToggleKillSwitch,
                    )
                }
                if (rowCards.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SecuritySystemCard(
    card: SecurityCardState,
    modifier: Modifier = Modifier,
    killSwitchEnabled: Boolean,
    onToggleKillSwitch: (Boolean) -> Unit,
) {
    val (tint, bg) = when (card.severity) {
        CardSeverity.GOOD -> PgAccent to PgAccentDim
        CardSeverity.INFO -> PgInfo to PgInfoDim
        CardSeverity.WARNING -> PgWarning to PgWarningDim
        CardSeverity.CRITICAL -> PgDanger to PgDangerDim
    }
    PanelCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (card.title.contains("Upload")) Icons.Default.Upload else Icons.Default.Security,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(card.value, style = MaterialTheme.typography.labelMedium, color = PgTextFaint)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(card.title, style = MaterialTheme.typography.titleMedium, color = PgText)
        Spacer(modifier = Modifier.height(3.dp))
        Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(card.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint)
            if (card.title == "Kill Switch") {
                PrivacySwitch(checked = killSwitchEnabled, onToggle = { onToggleKillSwitch(!killSwitchEnabled) })
            }
        }
    }
}

@Composable
private fun WeeklyPrivacyReportCard(report: WeeklyPrivacyReport) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Weekly Privacy Report", style = MaterialTheme.typography.titleLarge, color = PgText)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Behavioral summary from the last 7 days", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            Text(report.trustLevel.uppercase(), style = MaterialTheme.typography.labelSmall, color = PgAccent)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Connections", report.totalConnections.toString(), PgInfo, Modifier.weight(1f))
            StatTile("Blocked", report.blockedConnections.toString(), PgDanger, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Behavior", report.behaviorAlerts.toString(), PgWarning, Modifier.weight(1f))
            StatTile("Score", report.privacyScore.toString(), PgAccent, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Transferred ${report.dataTransferred}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
    }
}

@Composable
private fun PcapExportCard(
    isCapturing: Boolean,
    path: String?,
    onToggle: () -> Unit,
    onShare: (String) -> Unit,
) {
    PanelCard {
        MetricRow(
            icon = Icons.Default.Router,
            tint = if (isCapturing) PgDanger else PgInfo,
            title = "PCAP Export",
            subtitle = path ?: "Capture raw IP packets for Wireshark analysis",
            trailing = if (isCapturing) "REC" else "READY",
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isCapturing) PgDangerDim else PgAccentDim,
                contentColor = if (isCapturing) PgDanger else PgAccent
            )
        ) {
            Text(if (isCapturing) "Stop Capture" else "Start Capture")
        }
        if (!isCapturing && path != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onShare(path) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PgInfoDim,
                    contentColor = PgInfo
                )
            ) {
                Text("Share Last Capture")
            }
        }
    }
}

@Composable
private fun RecommendationsCard(notes: List<String>) {
    PanelCard {
        notes.forEachIndexed { index, note ->
            Row(verticalAlignment = Alignment.Top) {
                Text("#${index + 1}", style = MaterialTheme.typography.labelMedium, color = PgAccent)
                Spacer(modifier = Modifier.size(10.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = PgTextMuted, modifier = Modifier.weight(1f))
            }
            if (index != notes.lastIndex) Spacer(modifier = Modifier.height(10.dp))
        }
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
