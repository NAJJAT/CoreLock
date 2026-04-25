package com.privacyguard.app.ui.mitm

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.StatusPill
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

/**
 * Enterprise Payloads screen.
 *
 * This screen intentionally exposes metadata-only inspection from the VPN
 * history. It does not decrypt or inspect third-party TLS payload bodies.
 */
@Composable
fun MitmScreen(
    viewModel: MitmViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var showConsent by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Enterprise Payload Inspection - Important Notice") },
            text = {
                Text(
                    "This enterprise feature exposes network metadata for company-managed devices. " +
                        "It must only be enabled where employees have written consent and local law permits monitoring. " +
                        "By enabling this feature you confirm the legal prerequisites have been met."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.recordConsentNow()
                        viewModel.setEnabled(true)
                        showConsent = false
                    }
                ) { Text("I Confirm - Enable") }
            },
            dismissButton = {
                Button(onClick = { showConsent = false }, colors = ButtonDefaults.buttonColors(containerColor = PgDangerDim, contentColor = PgDanger)) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text("Managed-device notice") },
            text = {
                Text(
                    "This export is for company-managed devices only. It shares PrivacyGuard's " +
                        "enterprise inspection notice and opens Android security settings so your " +
                        "administrator can finish the trust flow."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                CaInstallHelper.buildShareIntent(context),
                                "Share enterprise inspection notice",
                            )
                        )
                        context.startActivity(CaInstallHelper.openSecuritySettingsIntent())
                        Toast.makeText(context, "Inspection notice exported", Toast.LENGTH_SHORT).show()
                        showInstallDialog = false
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showInstallDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = PgBackgroundAlt, contentColor = PgTextMuted),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Payloads",
                subtitle = "Enterprise metadata inspection",
                badge = if (state.enabled) "ENABLED" else "OFF"
            ) {
                PanelCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Sessions", state.interceptedSessions.toString(), PgInfo, Modifier.weight(1f))
                        StatTile("Pinned bypass", state.pinnedBypassed.toString(), PgWarning, Modifier.weight(1f))
                        StatTile("Pending", state.pendingEvents.toString(), PgAccent, Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!state.enabled && !state.consentFresh) {
                                    showConsent = true
                                } else {
                                    viewModel.setEnabled(!state.enabled)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.enabled) PgDangerDim else PgAccentDim,
                                contentColor = if (state.enabled) PgDanger else PgAccent,
                            )
                        ) {
                            Text(if (state.enabled) "Disable" else "Enable")
                        }
                        Button(
                            onClick = { viewModel.shipNow() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isShipping,
                            colors = ButtonDefaults.buttonColors(containerColor = PgInfoDim, contentColor = PgInfo),
                        ) {
                            Text(if (state.isShipping) "Shipping..." else "Ship Now")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val file = viewModel.exportLogs()
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Export Metadata Logs"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export")
                        }
                        Button(
                            onClick = { showInstallDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PgInfoDim, contentColor = PgInfo),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Trust Flow")
                        }
                    }
                    state.shipMessage?.let { message ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("shipped", ignoreCase = true)) PgAccent else PgWarning,
                        )
                    }
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Queued batches")
                    if (state.queuedBatches.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.clearPendingQueue() },
                            colors = ButtonDefaults.buttonColors(containerColor = PgDangerDim, contentColor = PgDanger),
                        ) {
                            Text("Clear queue")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (state.queuedBatches.isEmpty()) {
                    Text(
                        "No local batches queued right now. If SIEM shipping is off or unavailable, Ship Now will save a fallback batch here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                } else {
                    state.queuedBatches.take(3).forEachIndexed { index, batch ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PgPanelStrong, MaterialTheme.shapes.medium)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = batch.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = PgText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${batch.eventCount} events · ${com.privacyguard.app.ui.components.formatBytes(batch.sizeBytes)} · ${com.privacyguard.app.ui.components.formatAgo(batch.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PgTextMuted,
                                )
                            }
                            StatusPill("LOCAL", PgInfoDim, PgInfo)
                        }
                    }
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Enterprise controls")
                Spacer(modifier = Modifier.height(12.dp))
                ControlRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "SIEM shipping",
                    subtitle = if (state.shipToSiem) "Metadata batches can be shipped to your enterprise endpoint" else "Keep metadata local to this managed device",
                    checked = state.shipToSiem,
                    onToggle = { viewModel.setShipToSiem(!state.shipToSiem) },
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.siemEndpoint,
                    onValueChange = viewModel::setSiemEndpoint,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("SIEM endpoint") },
                    placeholder = { Text("https://soc.example.com") },
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.siemApiKey,
                    onValueChange = viewModel::setSiemApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("SIEM API key") },
                )
                Spacer(modifier = Modifier.height(10.dp))
                ControlRow(
                    icon = Icons.Default.Lock,
                    title = "Local encrypted exports",
                    subtitle = "Keep a local export path available when SIEM shipping is disabled",
                    checked = state.writeLocalLog,
                    onToggle = { viewModel.setWriteLocalLog(!state.writeLocalLog) },
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (state.consentFresh) "Consent recorded for this device." else "Consent renewal is required before enterprise inspection can be enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.consentFresh) PgAccent else PgWarning,
                )
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("What you see here")
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "This enterprise build shows connection metadata from the VPN layer: package, hostname, protocol, timing, encryption posture, and transfer size. It does not decrypt third-party app HTTPS bodies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
                Spacer(modifier = Modifier.height(10.dp))
                StatusPill("Metadata only", PgInfoDim, PgInfo)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "That keeps the feature useful for blue-team workflows without turning the app into a general interception tool.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextFaint,
                )
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Filters")
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", state.directionFilter == PayloadDirectionFilter.ALL) {
                        viewModel.setDirectionFilter(PayloadDirectionFilter.ALL)
                    }
                    FilterChip("Outbound", state.directionFilter == PayloadDirectionFilter.OUTBOUND) {
                        viewModel.setDirectionFilter(PayloadDirectionFilter.OUTBOUND)
                    }
                    FilterChip("Inbound", state.directionFilter == PayloadDirectionFilter.INBOUND) {
                        viewModel.setDirectionFilter(PayloadDirectionFilter.INBOUND)
                    }
                    FilterChip("Preview", state.withBodyOnly) {
                        viewModel.setWithBodyOnly(!state.withBodyOnly)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null) },
                    label = { Text("Search package, host, IP") },
                )
            }
        }

        items(state.logs) { item ->
            PanelCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { }
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.appName, style = MaterialTheme.typography.titleSmall, color = PgText, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(item.packageName, style = MaterialTheme.typography.bodySmall, color = PgTextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(item.relativeTime, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(item.hostname, style = MaterialTheme.typography.bodyMedium, color = PgTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(item.direction, if (item.direction == "OUTBOUND") PgDangerDim else PgAccentDim, if (item.direction == "OUTBOUND") PgDanger else PgAccent)
                    StatusPill(item.protocol, PgInfoDim, PgInfo)
                    item.methodHint?.let { StatusPill(it, PgAccentDim, PgAccent) }
                    StatusPill(item.encryptionLabel, PgBackgroundAlt, PgTextMuted)
                    if (item.wasBlocked) {
                        StatusPill("BLOCKED", PgDangerDim, PgDanger)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(item.preview, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(6.dp))
                Text("${item.destinationIp}:${item.destinationPort} · ${item.sizeLabel}", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    StatusPill(
        text = label,
        background = if (selected) PgAccentDim else MaterialTheme.colorScheme.surfaceVariant,
        content = if (selected) PgAccent else PgTextMuted,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ControlRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(PgPanelStrong, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(PgBackgroundAlt, MaterialTheme.shapes.medium)
                    .padding(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = PgAccent)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = PgText)
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
