package com.privacyguard.app.ui.dashboard

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Bedtime
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import com.privacyguard.app.vpn.KillSwitch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.LabeledProgress
import com.privacyguard.app.ui.components.MetricRow
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.security.rememberProtectedActionRunner
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackgroundAlt
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
import com.privacyguard.app.ui.traffic.EvidenceKind
import com.privacyguard.vpn.interception.InspectionStatus
import com.privacyguard.vpn.interception.TlsInterceptionFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DashboardScreen(
    onRequestVpn: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenConnections: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenSleepReport: (at: Long) -> Unit = {},
    onOpenEvidence: (EvidenceKind, at: Long) -> Unit = { _, _ -> },
    onOpenStatistics: () -> Unit = {},
    /** Null in builds without HTTPS inspection. */
    onOpenPayloads: (() -> Unit)? = null,
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val runProtectedAction = rememberProtectedActionRunner()
    // Lists open for the same window the numbers were computed for.
    val open: (EvidenceKind) -> Unit = { kind -> onOpenEvidence(kind, uiState.evidenceAt) }
    // Private DNS with a hostname makes every lookup fail while the VPN runs. It is
    // changed in system settings, so read it again on every resume.
    var privateDnsHost by remember { mutableStateOf(com.privacyguard.app.core.network.PrivateDnsCheck.strictHostname(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        privateDnsHost = com.privacyguard.app.core.network.PrivateDnsCheck.strictHostname(context)
        onPauseOrDispose { }
    }
    // HTTPS inspection is never silent: shown while it decrypts, updated the moment
    // it is switched in the app or by an MDM policy.
    val inspection by androidx.compose.runtime.produceState(TlsInterceptionFactory.status(context)) {
        val watch = TlsInterceptionFactory.observeStatus(context) { value = TlsInterceptionFactory.status(context) }
        awaitDispose { watch.close() }
    }
    var confirmStartHost by remember { mutableStateOf<String?>(null) }
    confirmStartHost?.let { host ->
        PrivateDnsStartDialog(
            host = host,
            onOpenSettings = { confirmStartHost = null; com.privacyguard.app.core.network.PrivateDnsCheck.openSettings(context) },
            onStartAnyway = { confirmStartHost = null; onRequestVpn() },
            onDismiss = { confirmStartHost = null },
        )
    }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenScaffold(
                title = stringResource(R.string.dash_title),
                subtitle = if (uiState.isVpnActive) stringResource(R.string.dash_subtitle_on) else stringResource(R.string.dash_subtitle_off),
                badge = (if (uiState.isVpnActive) stringResource(R.string.dash_badge_live) else stringResource(R.string.dash_badge_offline)).uppercase()
            ) {
                Row(
                    // Room for a "99+" badge, which hangs past the bell icon.
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.set_title), tint = PgTextMuted)
                    }
                    IconButton(onClick = onOpenAlerts) {
                        if (uiState.alertBadgeCount > 0) {
                            BadgedBox(badge = {
                                Badge { Text(if (uiState.alertBadgeCount > 99) "99+" else uiState.alertBadgeCount.toString()) }
                            }) {
                                Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.dash_alerts), tint = PgTextMuted)
                            }
                        } else {
                            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.dash_alerts), tint = PgTextMuted)
                        }
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
                                    Text(stringResource(R.string.dash_status).uppercase(), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (uiState.isVpnActive) stringResource(R.string.dash_protected) else stringResource(R.string.dash_not_protected),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = if (uiState.isVpnActive) PgAccent else PgDanger
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.dash_privacy_score_n, uiState.privacyScore.score),
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
                                            // Checked now, not at the last resume: the setting may have changed.
                                            val host = com.privacyguard.app.core.network.PrivateDnsCheck.strictHostname(context)
                                            privateDnsHost = host
                                            // MainActivity asks for notifications, then VPN consent.
                                            if (host != null) confirmStartHost = host else onRequestVpn()
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
                                modifier = Modifier.clickable { open(EvidenceKind.ENCRYPTION_24H) },
                                label = stringResource(R.string.dash_encryption_health),
                                valueText = stringResource(R.string.dash_tls_percent, (uiState.encryptionHealth * 100).toInt()),
                                progress = uiState.encryptionHealth,
                                color = PgAccent
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.dash_blocked_24h), formatCount(uiState.trackersBlocked), PgAccent, Modifier.weight(1f).clickable { open(EvidenceKind.BLOCKED_24H) },
                        detail = pluralStringResource(R.plurals.trf_n_domains, uiState.blockedDomains24h, uiState.blockedDomains24h))
                    StatTile(stringResource(R.string.dash_cleartext_24h), formatCount(uiState.cleartextCount), PgDanger, Modifier.weight(1f).clickable { open(EvidenceKind.CLEARTEXT_24H) })
                    StatTile(stringResource(R.string.dash_privacy_score), formatCount(uiState.privacyScore.score), PgInfo, Modifier.weight(1f).clickable(onClick = onOpenStatistics))
                }
                if (uiState.isVpnActive) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            stringResource(R.string.dash_throughput),
                            formatThroughput(context, uiState.throughputBytesPerSec),
                            PgAccent,
                            Modifier.weight(1f),
                        )
                        StatTile(
                            stringResource(R.string.dash_active_conns),
                            formatCount(uiState.activeConnectionCount),
                            PgInfo,
                            // Live sessions: the Connections screen lists the same live table.
                            Modifier.weight(1f).clickable(onClick = onOpenConnections),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                SleepActivityCard(
                    summary = uiState.sleepActivity,
                    onClick = { onOpenSleepReport(uiState.evidenceAt) },
                )

                Spacer(modifier = Modifier.height(18.dp))
                if (inspection != InspectionStatus.OFF) {
                    InspectionOnCard(byOrganisation = inspection == InspectionStatus.ON_BY_ORGANISATION)
                    Spacer(modifier = Modifier.height(18.dp))
                }
                privateDnsHost?.let { host ->
                    PrivateDnsWarningCard(host, onOpenSettings = { com.privacyguard.app.core.network.PrivateDnsCheck.openSettings(context) })
                    Spacer(modifier = Modifier.height(18.dp))
                }
                uiState.strictSuggestion?.let { suggestion ->
                    StrictSuggestionCard(
                        suggestion = suggestion,
                        onAccept = viewModel::acceptStrictSuggestion,
                        onDismiss = viewModel::dismissStrictSuggestion,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }
                SectionLabel(stringResource(R.string.set_protection_level))
                Spacer(modifier = Modifier.height(8.dp))
                ProtectionLevelPicker(
                    current = uiState.protectionLevel,
                    onSelect = { viewModel.setProtectionLevel(it) },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(protectionLevelDescription(uiState.protectionLevel)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(stringResource(R.string.dash_security_systems))
                Spacer(modifier = Modifier.height(10.dp))
                SecuritySystemsGrid(
                    cards = uiState.securityCards,
                    // Only Android can block traffic once the tunnel is gone; send the user there.
                    onOpenVpnSettings = { KillSwitch.openAlwaysOnSettings(context) },
                    onCardClick = { card ->
                        val evidence = card.evidence
                        when {
                            evidence != null -> open(evidence)
                            card.kind == CardKind.KILL_SWITCH -> KillSwitch.openAlwaysOnSettings(context)
                            card.kind == CardKind.TRUST -> onOpenStatistics()
                            card.kind == CardKind.NO_DECRYPTION && card.severity == CardSeverity.WARNING -> onOpenPayloads?.invoke()
                            // "No decryption" states a fact about the app, not a count: nothing to list.
                            else -> {}
                        }
                    },
                )

                Spacer(modifier = Modifier.height(18.dp))
                WeeklyPrivacyReportCard(uiState.weeklyReport, onOpen = { open(it) }, onOpenScore = onOpenStatistics)

                Spacer(modifier = Modifier.height(14.dp))
                PcapExportCard(
                    isCapturing = uiState.isPcapCapturing,
                    path = uiState.pcapPath,
                    stoppedAtLimit = uiState.pcapStoppedAtLimit,
                    onToggle = {
                        runProtectedAction(
                            if (uiState.isPcapCapturing) resources.getString(R.string.dash_pcap_stop) else resources.getString(R.string.dash_pcap_start),
                            resources.getString(R.string.dash_pcap_confirm),
                        ) {
                            viewModel.togglePcapCapture()
                        }
                    },
                    onShare = { path ->
                        runProtectedAction(
                            resources.getString(R.string.dash_pcap_share),
                            resources.getString(R.string.dash_pcap_share_confirm),
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
                                context.startActivity(Intent.createChooser(share, resources.getString(R.string.dash_export_pcap)))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(stringResource(R.string.dash_recommendations))
                Spacer(modifier = Modifier.height(10.dp))
                RecommendationsCard(uiState.recommendations)

                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(stringResource(R.string.dash_recent_alerts))
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (uiState.recentAlerts.isEmpty()) {
            item {
                PanelCard(
                    modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = onOpenAlerts),
                ) {
                    Text(stringResource(R.string.dash_no_alerts), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.dash_no_alerts_desc), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
        } else {
            items(uiState.recentAlerts, key = { it.id }) { alert ->
                val tint = if (alert.severity >= 8) PgDanger else PgWarning
                val bg = if (alert.severity >= 8) PgDangerDim else PgWarningDim
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = onOpenAlerts)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (alert.severity >= 8) Icons.Default.Warning else Icons.Default.Security,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(alert.title, style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("${com.privacyguard.app.ui.text.FindingText.appLabel(context, alert.packageName.orEmpty())} · ${alert.detail}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted, maxLines = 2)
                        }
                        Text(formatAgo(alert.timestamp), style = MaterialTheme.typography.labelMedium, color = PgTextFaint)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun SleepActivityCard(
    summary: SleepActivitySummary,
    onClick: () -> Unit,
) {
    PanelCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PgInfoDim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = PgInfo, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column {
                    Text(stringResource(R.string.dash_sleep_title), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.dash_sleep_summary, pluralStringResource(R.plurals.dash_apps_active, summary.activeApps, summary.activeApps), pluralStringResource(R.plurals.dash_dns_queries, summary.dnsQueries, summary.dnsQueries)),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
            StatusPill(pluralStringResource(R.plurals.dash_tracking_count, summary.trackingDomains, summary.trackingDomains), PgWarningDim, PgWarning)
        }
    }
}

@Composable
private fun ProtectionLevelPicker(current: String, onSelect: (String) -> Unit) {
    val levels = listOf(
        Triple("MINIMAL",  stringResource(R.string.set_level_minimal),  PgInfo),
        Triple("STANDARD", stringResource(R.string.set_level_standard), PgAccent),
        Triple("STRICT",   stringResource(R.string.set_level_strict),   PgDanger),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        levels.forEach { (key, label, color) ->
            val selected = current == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) color.copy(alpha = 0.18f) else PgBackgroundAlt,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) color else PgTextFaint,
                )
            }
        }
    }
}

@Composable
private fun SecuritySystemsGrid(
    cards: List<SecurityCardState>,
    onOpenVpnSettings: () -> Unit,
    onCardClick: (SecurityCardState) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { rowCards ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowCards.forEach { card ->
                    SecuritySystemCard(
                        card = card,
                        modifier = Modifier.weight(1f),
                        onOpenVpnSettings = onOpenVpnSettings,
                        onCardClick = { onCardClick(card) },
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
    onOpenVpnSettings: () -> Unit,
    onCardClick: () -> Unit = {},
) {
    val (tint, bg) = when (card.severity) {
        CardSeverity.GOOD -> PgAccent to PgAccentDim
        CardSeverity.INFO -> PgInfo to PgInfoDim
        CardSeverity.WARNING -> PgWarning to PgWarningDim
        CardSeverity.CRITICAL -> PgDanger to PgDangerDim
    }
    PanelCard(modifier = modifier.clickable(onClick = onCardClick)) {
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
                    imageVector = if (card.kind == CardKind.UPLOAD) Icons.Default.Upload else Icons.Default.Security,
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
        }
        // A status and a way to Android's settings, not a switch: only Android controls
        // Always-on VPN and its lockdown. On its own line, as the cards are half width.
        if (card.kind == CardKind.KILL_SWITCH) {
            androidx.compose.material3.TextButton(onClick = onOpenVpnSettings) {
                Text(stringResource(R.string.dash_ks_open_vpn_settings))
            }
        }
    }
}

@Composable
private fun WeeklyPrivacyReportCard(
    report: WeeklyPrivacyReport,
    onOpen: (EvidenceKind) -> Unit,
    onOpenScore: () -> Unit,
) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(stringResource(R.string.dash_weekly_title), style = MaterialTheme.typography.titleLarge, color = PgText)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.dash_weekly_desc), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.dash_connections), formatCount(report.totalConnections), PgInfo, Modifier.weight(1f).clickable { onOpen(EvidenceKind.CONNECTIONS_7D) })
            StatTile(stringResource(R.string.dash_blocked), formatCount(report.blockedConnections), PgDanger, Modifier.weight(1f).clickable { onOpen(EvidenceKind.BLOCKED_7D) },
                detail = pluralStringResource(R.plurals.trf_n_domains, report.blockedDomains, report.blockedDomains))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.dash_behavior), formatCount(report.behaviorAlerts), PgWarning, Modifier.weight(1f).clickable { onOpen(EvidenceKind.BEHAVIOR_7D) })
            StatTile(stringResource(R.string.dash_score), formatCount(report.privacyScore), PgAccent, Modifier.weight(1f).clickable(onClick = onOpenScore))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.dash_transferred, report.dataTransferred),
            style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
            modifier = Modifier.clickable { onOpen(EvidenceKind.DATA_7D) },
        )
    }
}

@Composable
private fun PcapExportCard(
    isCapturing: Boolean,
    path: String?,
    stoppedAtLimit: Boolean,
    onToggle: () -> Unit,
    onShare: (String) -> Unit,
) {
    PanelCard {
        MetricRow(
            icon = Icons.Default.Router,
            tint = if (isCapturing) PgDanger else PgInfo,
            title = stringResource(R.string.dash_pcap_title),
            subtitle = stringResource(R.string.dash_pcap_desc),
            trailing = (if (isCapturing) stringResource(R.string.dash_pcap_rec) else stringResource(R.string.dash_pcap_ready)).uppercase(),
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
            Text(if (isCapturing) stringResource(R.string.dash_stop_capture) else stringResource(R.string.dash_start_capture))
        }
        if (!isCapturing && path != null) {
            val file = File(path)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(
                    if (stoppedAtLimit) R.string.dash_pcap_file_limit else R.string.dash_pcap_file,
                    file.name, android.text.format.Formatter.formatShortFileSize(LocalContext.current, file.length()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PgTextMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onShare(path) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PgInfoDim,
                    contentColor = PgInfo
                )
            ) {
                Text(stringResource(R.string.dash_share_capture))
            }
        }
    }
}

@Composable
private fun RecommendationsCard(notes: List<String>) {
    PanelCard {
        notes.forEachIndexed { index, note ->
            Row(verticalAlignment = Alignment.Top) {
                Text(formatCount(index + 1), style = MaterialTheme.typography.labelMedium, color = PgAccent)
                Spacer(modifier = Modifier.size(10.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = PgTextMuted, modifier = Modifier.weight(1f))
            }
            if (index != notes.lastIndex) Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PrivacySwitch(checked: Boolean, onToggle: () -> Unit) {
    val protectionLabel = stringResource(R.string.dash_protection)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) PgAccent else PgPanelStrong)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = protectionLabel }
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

// Locale-aware units and digits (e.g. "1,2 MB/s" in Dutch, Arabic-Indic digits in Arabic).
private fun formatThroughput(context: android.content.Context, bytesPerSec: Long): String =
    context.getString(R.string.dash_per_second, android.text.format.Formatter.formatShortFileSize(context, bytesPerSec))

private fun formatCount(n: Number): String = java.text.NumberFormat.getIntegerInstance().format(n)



/** What the level blocks, in words; the mapping itself is ProtectionLevels. */
@androidx.annotation.StringRes
internal fun protectionLevelDescription(level: String): Int = when (level) {
    "MINIMAL" -> R.string.set_level_minimal_desc
    "STRICT" -> R.string.set_level_strict_desc
    else -> R.string.set_level_standard_desc
}

/** HTTPS inspection is decrypting traffic; says so plainly, and who turned it on. */
@Composable
private fun InspectionOnCard(byOrganisation: Boolean) {
    PanelCard {
        Text(stringResource(R.string.dash_inspection_title), style = MaterialTheme.typography.titleMedium, color = PgWarning)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(if (byOrganisation) R.string.dash_inspection_org_body else R.string.dash_inspection_body),
            style = MaterialTheme.typography.bodySmall,
            color = PgTextMuted,
        )
    }
}

/** Private DNS in hostname mode: DNS fails while the VPN runs. */
@Composable
private fun PrivateDnsWarningCard(host: String, onOpenSettings: () -> Unit) {
    PanelCard {
        Text(stringResource(R.string.dash_private_dns_title), style = MaterialTheme.typography.titleMedium, color = PgWarning)
        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.dash_private_dns_body, host), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PgWarningDim, contentColor = PgWarning),
        ) { Text(stringResource(R.string.dash_private_dns_open)) }
    }
}

/** Asked before starting protection while Private DNS has a hostname. */
@Composable
private fun PrivateDnsStartDialog(host: String, onOpenSettings: () -> Unit, onStartAnyway: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dash_private_dns_title)) },
        text = { Text(stringResource(R.string.dash_private_dns_body, host)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.dash_private_dns_open)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onStartAnyway) { Text(stringResource(R.string.dash_private_dns_start_anyway)) }
        },
    )
}

@Composable
private fun StrictSuggestionCard(
    suggestion: StrictSuggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    PanelCard {
        Text(
            stringResource(R.string.dash_strict_title, suggestion.networkLabel),
            style = MaterialTheme.typography.titleMedium,
            color = PgText,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.dash_strict_body, suggestion.score),
            style = MaterialTheme.typography.bodySmall,
            color = PgTextMuted,
        )
        // Without location access every Wi-Fi shares one key (see NetworkIdentity), so a
        // dismissal covers all Wi-Fi networks; say so instead of implying "this one".
        val sharedWifiKey = suggestion.networkKey == com.privacyguard.app.core.network.NetworkIdentity.SHARED_WIFI_KEY
        if (sharedWifiKey) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.dash_strict_shared_note), style = MaterialTheme.typography.bodySmall, color = PgTextFaint)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PgWarningDim, contentColor = PgWarning),
            ) { Text(stringResource(R.string.dash_strict_accept)) }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PgBackgroundAlt, contentColor = PgTextMuted),
            ) { Text(stringResource(if (sharedWifiKey) R.string.dash_strict_dismiss_wifi else R.string.dash_strict_dismiss)) }
        }
    }
}
