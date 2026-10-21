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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.privacyguard.app.R
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SecurityAnalysisScreen(
    onOpenAlerts: () -> Unit = {},
    viewModel: SecurityAnalysisViewModel = viewModel()
) {
    val ctMonitoringEnabled by com.privacyguard.app.data.local.preferences.SettingsPreferences
        .getInstance(androidx.compose.ui.platform.LocalContext.current)
        .ctMonitoringEnabled.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = stringResource(R.string.sec_title),
                subtitle = stringResource(R.string.sec_subtitle),
                badge = stringResource(R.string.dash_badge_live).uppercase()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        stringResource(R.string.sec_ja3_threats),
                        state.ja3ThreatCount.toString(),
                        if (state.ja3ThreatCount > 0) PgDanger else PgAccent,
                        Modifier.weight(1f)
                    )
                    StatTile(
                        stringResource(R.string.sec_weak_ciphers),
                        state.weakCipherCount.toString(),
                        if (state.weakCipherCount > 0) PgWarning else PgAccent,
                        Modifier.weight(1f)
                    )
                    StatTile(
                        stringResource(R.string.sec_ct_events),
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

        // Per-app JA4 baseline
        item {
            TlsBaselinePanel(state.baseline, onTrust = viewModel::trust)
        }

        // JA3 threat list
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel(stringResource(R.string.sec_ja3_section))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sec_ja3_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.ja3Threats.isEmpty()) {
                    EmptyBanner(stringResource(R.string.sec_ja3_none), isGood = true)
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
                SectionLabel(stringResource(R.string.sec_cipher_section))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sec_cipher_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.cipherAlerts.isEmpty()) {
                    EmptyBanner(stringResource(R.string.sec_cipher_none), isGood = true)
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
                SectionLabel(stringResource(R.string.sec_ct_section))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sec_ct_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (state.ctAlerts.isEmpty()) {
                    EmptyBanner(
                        if (ctMonitoringEnabled) stringResource(R.string.sec_ct_on)
                        else stringResource(R.string.sec_ct_off),
                        isGood = false,
                    )
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
                SectionLabel(stringResource(R.string.set_dual_vpn))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sec_dual_desc),
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
                        DualVpnHopRow(stringResource(R.string.sec_hop_you), stringResource(R.string.sec_hop_you_desc), isDevice = true)
                        HopArrow()
                        DualVpnHopRow(stringResource(R.string.sec_hop_1), stringResource(R.string.sec_hop_1_desc))
                        HopArrow()
                        DualVpnHopRow(stringResource(R.string.sec_hop_2), stringResource(R.string.sec_hop_2_desc))
                        HopArrow()
                        DualVpnHopRow(stringResource(R.string.sec_hop_net), stringResource(R.string.sec_hop_net_desc))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sec_dual_config),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun TlsBaselinePanel(summary: TlsBaselineSummary, onTrust: (TlsChangeItem) -> Unit) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionLabel(stringResource(R.string.tls_fp_section_title))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(R.string.tls_fp_section_body),
            style = MaterialTheme.typography.bodySmall,
            color = PgTextMuted,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (summary.appCount == 0) {
            EmptyBanner(stringResource(R.string.tls_fp_no_apps_yet), isGood = false)
            return@PanelCard
        }
        Text(
            pluralStringResource(R.plurals.tls_fp_known_count, summary.knownFingerprints, summary.knownFingerprints),
            style = MaterialTheme.typography.bodySmall,
            color = PgText,
        )
        if (summary.appsLearning > 0) {
            Text(
                pluralStringResource(R.plurals.tls_fp_apps_learning, summary.appsLearning, summary.appsLearning),
                style = MaterialTheme.typography.bodySmall,
                color = PgTextMuted,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (summary.changes.isEmpty()) {
            EmptyBanner(stringResource(R.string.tls_fp_no_changes), isGood = true)
        } else {
            summary.changes.forEachIndexed { index, item ->
                if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                TlsChangeRow(item, onTrust)
            }
        }
    }
}

@Composable
private fun TlsChangeRow(item: TlsChangeItem, onTrust: (TlsChangeItem) -> Unit) {
    var expanded by remember { mutableStateOf(!item.trusted) }
    val fp = item.fingerprint
    val (bg, fg) = if (item.trusted) PgInfoDim to PgInfo else PgWarningDim to PgWarning
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tls_fp_alert_title, item.appLabel),
                style = MaterialTheme.typography.titleSmall,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            if (item.trusted) StatusPill(stringResource(R.string.tls_fp_trusted), bg, fg)
        }
        Text(formatAgo(fp.firstSeen), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            val what = if (fp.firstSni.isNullOrBlank())
                stringResource(R.string.tls_fp_alert_what_no_domain, item.appLabel)
            else stringResource(R.string.tls_fp_alert_what_domain, item.appLabel, fp.firstSni)
            Text(what, style = MaterialTheme.typography.bodySmall, color = PgText)
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(R.string.tls_fp_alert_reasons), style = MaterialTheme.typography.bodySmall, color = PgText)
            Spacer(modifier = Modifier.height(6.dp))
            Text(stringResource(R.string.tls_fp_alert_actions), style = MaterialTheme.typography.bodySmall, color = PgText)
            Spacer(modifier = Modifier.height(8.dp))
            DetailLine("JA4", fp.ja4)
            DetailLine("JA3", fp.ja3)
            if (!item.trusted) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = { onTrust(item) }) {
                    Text(stringResource(R.string.tls_fp_trust))
                }
            }
        }
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
                StatusPill(stringResource(R.string.sec_sev, alert.severity).uppercase(), bg, fg)
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider(color = fg.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            if (!alert.packageName.isNullOrBlank()) {
                DetailLine(stringResource(R.string.conn_app), LocalContext.current.packageManager.let { pm -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(alert.packageName, 0)).toString() }.getOrDefault(alert.packageName) })
            }
            if (!alert.hash.isNullOrBlank()) {
                DetailLine(stringResource(R.string.sec_ja3_hash), alert.hash)
            }
            if (!alert.ja3String.isNullOrBlank()) {
                DetailLine(stringResource(R.string.sec_ja3_string), alert.ja3String)
            }
            if (!alert.category.isNullOrBlank()) {
                DetailLine(stringResource(R.string.sec_category), alert.category)
            }
            if (!alert.detail.isNullOrBlank()) {
                DetailLine(stringResource(R.string.sec_detail), alert.detail)
            }
            DetailLine(stringResource(R.string.sec_detected), formatAgo(alert.timestamp))
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
            pluralStringResource(R.plurals.sec_view_all, count, count),
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
