package com.privacyguard.app.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.data.db.TlsAlertEntity
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanel
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

@Composable
fun SecurityAnalysisScreen(
    onOpenAlerts: () -> Unit = {},
    viewModel: SecurityAnalysisViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Crypto Analysis",
                subtitle = "TLS fingerprinting · Cipher suites · CT monitor",
                badge = "LIVE"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "JA3 Threats",
                        state.ja3ThreatCount.toString(),
                        if (state.ja3ThreatCount > 0) PgDanger else PgAccent,
                        Modifier.weight(1f)
                    )
                    StatTile(
                        "Weak Ciphers",
                        state.weakCipherCount.toString(),
                        if (state.weakCipherCount > 0) PgWarning else PgAccent,
                        Modifier.weight(1f)
                    )
                    StatTile(
                        "CT Events",
                        state.ctNewCertCount.toString(),
                        PgInfo,
                        Modifier.weight(1f)
                    )
                }
                val totalAlerts = state.ja3ThreatCount + state.weakCipherCount + state.ctNewCertCount
                if (totalAlerts > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ViewAllAlertsButton(totalAlerts, onOpenAlerts)
                }
            }
        }

        // JA3 / JA4 section
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("JA3 Fingerprint Threats")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Each TLS ClientHello is fingerprinted and checked against known malware C2 patterns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.ja3Threats.isEmpty()) {
                    EmptyBanner("No malicious JA3 fingerprints detected", isGood = true)
                } else {
                    state.ja3Threats.forEachIndexed { index, alert ->
                        if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                        TlsAlertRow(alert)
                    }
                }
            }
        }

        // Cipher Suite Analysis section
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Cipher Suite Analysis")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Detects FREAK (RSA_EXPORT), LOGJAM (DHE_EXPORT), NULL, RC4, and anonymous ciphers offered by apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.cipherAlerts.isEmpty()) {
                    EmptyBanner("No weak cipher suites observed", isGood = true)
                } else {
                    state.cipherAlerts.forEachIndexed { index, alert ->
                        if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                        TlsAlertRow(alert)
                    }
                }
            }
        }

        // Certificate Transparency section
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Certificate Transparency Monitor")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Polls crt.sh every 6 hours for new certificates issued for domains your device contacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.ctAlerts.isEmpty()) {
                    EmptyBanner("CT monitor is active — new cert events appear here", isGood = false)
                } else {
                    state.ctAlerts.forEachIndexed { index, alert ->
                        if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                        TlsAlertRow(alert)
                    }
                }
            }
        }

        // Dual VPN info
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Dual VPN")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Chain two SOCKS5 servers so no single server knows both who you are and where you go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PgPanelStrong, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        DualVpnHopRow("You", "Your real IP", isDevice = true)
                        HopArrow()
                        DualVpnHopRow("Server 1", "Knows your IP, NOT your target")
                        HopArrow()
                        DualVpnHopRow("Server 2", "Knows your target, NOT your IP")
                        HopArrow()
                        DualVpnHopRow("Internet", "Neither server has the full picture")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Configure Dual VPN servers in Settings → Dual VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
        }

        // Rust engine status
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Native Processing Engine")
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rust Core Engine", style = MaterialTheme.typography.titleSmall, color = PgText)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (com.privacyguard.core.native_engine.RustBridge.isAvailable)
                                "5× faster packet processing · 40% less battery"
                            else
                                "Kotlin engine active · build with NDK+Cargo to enable",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                    }
                    StatusPill(
                        text = if (com.privacyguard.core.native_engine.RustBridge.isAvailable) "ACTIVE" else "KOTLIN",
                        background = if (com.privacyguard.core.native_engine.RustBridge.isAvailable) PgAccentDim else PgInfoDim,
                        content = if (com.privacyguard.core.native_engine.RustBridge.isAvailable) PgAccent else PgInfo,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun TlsAlertRow(alert: TlsAlertEntity) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val (bg, fg) = when {
        alert.severity >= 9 -> PgDangerDim to PgDanger
        alert.severity >= 6 -> PgWarningDim to PgWarning
        else                -> PgInfoDim to PgInfo
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    alert.malwareName ?: alert.category ?: alert.alertType,
                    style = MaterialTheme.typography.titleSmall,
                    color = fg,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                if (!alert.sni.isNullOrBlank()) {
                    Text(alert.sni, style = MaterialTheme.typography.bodySmall, color = PgText)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill("SEV ${alert.severity}", bg, fg)
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider(color = fg.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            if (!alert.packageName.isNullOrBlank()) {
                DetailLine("App", alert.packageName.substringAfterLast('.'))
            }
            if (!alert.hash.isNullOrBlank()) {
                DetailLine("JA3 hash", alert.hash)
            }
            if (!alert.ja3String.isNullOrBlank()) {
                DetailLine("JA3 string", alert.ja3String)
            }
            if (!alert.category.isNullOrBlank()) {
                DetailLine("Category", alert.category)
            }
            if (!alert.detail.isNullOrBlank()) {
                DetailLine("Detail", alert.detail)
            }
            DetailLine("Detected", formatAgo(alert.timestamp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = PgText,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun EmptyBanner(message: String, isGood: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isGood) PgAccentDim else PgInfoDim, RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = if (isGood) PgAccent else PgInfo)
    }
}

@Composable
private fun DualVpnHopRow(label: String, description: String, isDevice: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(if (isDevice) PgAccentDim else PgInfoDim, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (isDevice) PgAccent else PgInfo)
        }
        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
    }
}

@Composable
private fun HopArrow() {
    Text("   ↓", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
}

@Composable
private fun ViewAllAlertsButton(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PgDangerDim, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "View all $count alerts in inbox",
            style = MaterialTheme.typography.titleSmall,
            color = PgDanger,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = PgDanger,
        )
    }
}
