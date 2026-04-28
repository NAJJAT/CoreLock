package com.privacyguard.app.ui.connections

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.MonoFont
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgPanelRaised
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import java.util.Locale

@Composable
fun ConnectionsScreen(
    onAppClick: (packageName: String, appName: String) -> Unit = { _, _ -> },
    viewModel: ConnectionsViewModel = viewModel()
) {
    val liveConnections by viewModel.connections.collectAsState()
    val storedConnections by viewModel.recentConnections.collectAsState()
    val connections = viewModel.getFilteredConnections().ifEmpty {
        if (liveConnections.isNotEmpty()) liveConnections else storedConnections
    }
    val filter by viewModel.filter.collectAsState()
    val dnsLookup by viewModel.dnsLookup.collectAsState()
    val selectedConnection by viewModel.selectedConnection.collectAsState()

    selectedConnection?.let {
        ConnectionDetailDialog(
            detail = it,
            onClose = viewModel::clearSelectedConnection,
            onToggleBlock = { viewModel.toggleConnectionBlocked(it.connection) },
            onViewApp = { pkg, name ->
                viewModel.clearSelectedConnection()
                onAppClick(pkg, name)
            },
        )
    }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenScaffold(
                title = "Connections",
                subtitle = "Live network traffic and encryption posture",
                badge = "LIVE"
            ) {
                SearchShell(
                    query = filter.query,
                    onValueChange = { viewModel.setFilter(filter.copy(query = it)) }
                )
                if (dnsLookup.query.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    DnsLookupPanel(dnsLookup)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", !filter.showBlockedOnly && !filter.showCleartextOnly) {
                        viewModel.clearFilter()
                    }
                    FilterChip("Blocked", filter.showBlockedOnly, PgDanger, PgDangerDim) {
                        viewModel.setFilter(filter.copy(showBlockedOnly = !filter.showBlockedOnly))
                    }
                    FilterChip("Cleartext", filter.showCleartextOnly) {
                        viewModel.setFilter(filter.copy(showCleartextOnly = !filter.showCleartextOnly))
                    }
                }
            }
        }

        if (connections.isEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No connections yet", style = MaterialTheme.typography.titleMedium, color = PgTextMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Start the VPN from the Home screen. Connections will appear here once traffic flows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        items(connections) { connection ->
            PanelCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { viewModel.selectConnection(connection) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (statusTint, statusBg) = when {
                        connection.isBlocked -> PgDanger to PgDangerDim
                        connection.securityInfo.contains("TLS 1.0", true) || connection.securityInfo.contains("TLS 1.1", true) ->
                            PgWarning to PgWarningDim
                        connection.securityInfo == "CLEARTEXT" -> PgDanger to PgDangerDim
                        else -> PgAccent to PgAccentDim
                    }
                    Box(contentAlignment = Alignment.BottomEnd) {
                        AppIconImage(packageName = connection.packageName, size = 40.dp)
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(statusBg, CircleShape)
                                .padding(2.dp)
                                .background(statusTint, CircleShape),
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = connection.packageName.isNotBlank()) {
                                onAppClick(connection.packageName, connection.appName)
                            },
                    ) {
                        Text(
                            text = connection.appName.ifBlank { "Unknown app" },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (connection.isBlocked) PgDanger else PgText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = connection.packageName.ifBlank { "Unknown package" },
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(connection.dataRate, style = MaterialTheme.typography.labelMedium, color = PgTextFaint)
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(modifier = Modifier.clickable {
                            viewModel.toggleConnectionBlocked(connection)
                        }) {
                            StatusPill(
                                text = if (connection.isBlocked) "UNBLOCK" else "BLOCK",
                                background = if (connection.isBlocked) PgDangerDim else PgPanelRaised,
                                content = PgDanger
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connection.wasBackground) {
                        Text(
                            "BG",
                            style = MaterialTheme.typography.labelSmall,
                            color = PgWarning,
                            modifier = Modifier
                                .background(PgWarningDim, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = listOf(
                            connection.hostName ?: connection.destination,
                            connection.securityInfo.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) },
                            connection.encryptionInfo.takeIf { it.isNotBlank() },
                        ).filterNotNull().joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                        fontFamily = MonoFont,
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ConnectionDetailDialog(
    detail: ConnectionDetailState,
    onClose: () -> Unit,
    onToggleBlock: () -> Unit,
    onViewApp: (packageName: String, appName: String) -> Unit = { _, _ -> },
) {
    val connection = detail.connection
    Dialog(onDismissRequest = onClose) {
        PanelCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detail.displayHost,
                            style = MaterialTheme.typography.titleLarge,
                            color = PgText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${connection.destinationIp}:${connection.destinationPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                            fontFamily = MonoFont
                        )
                    }
                    StatusPill(
                        text = if (connection.isBlocked) "BLOCKED" else connection.protocol,
                        background = if (connection.isBlocked) PgDangerDim else PgAccentDim,
                        content = if (connection.isBlocked) PgDanger else PgAccent
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = PgBackgroundAlt)
                Spacer(modifier = Modifier.height(12.dp))

                DetailRow("App", connection.appName)
                DetailRow("Package", connection.packageName)
                DetailRow("DNS name", connection.hostName ?: detail.reverseDns ?: if (detail.isResolvingReverseDns) "Resolving..." else "Unknown")
                DetailRow("Security", listOf(connection.securityInfo, connection.encryptionInfo).filter { it.isNotBlank() }.joinToString(" / "))
                DetailRow("Transferred", "${formatBytes(connection.bytesSent)} up · ${formatBytes(connection.bytesReceived)} down")

                Spacer(modifier = Modifier.height(12.dp))
                PanelCard {
                    Column {
                        Text("Route", style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                        Spacer(modifier = Modifier.height(6.dp))
                        val geo = detail.geo
                        Text(
                            text = if (geo != null) {
                                "This device -> ${geo.countryName} (${geo.countryCode})"
                            } else {
                                "This device -> Unknown country"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = PgText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = geo?.org ?: "No local GeoIP match for this address",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                            fontFamily = MonoFont
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                if (connection.packageName.isNotBlank()) {
                    Button(
                        onClick = { onViewApp(connection.packageName, connection.appName) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PgAccentDim,
                            contentColor = PgAccent
                        )
                    ) {
                        Text("View App →", color = PgAccent)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onToggleBlock,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PgDangerDim,
                            contentColor = PgDanger
                        )
                    ) {
                        Text(if (connection.isBlocked) "Unblock" else "Block")
                    }
                    Button(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PgBackgroundAlt,
                            contentColor = PgTextMuted
                        )
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value.ifBlank { "Unknown" },
            style = MaterialTheme.typography.bodyMedium,
            color = PgText,
            fontFamily = MonoFont,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DnsLookupPanel(result: DnsLookupState) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DNS lookup",
                    style = MaterialTheme.typography.labelSmall,
                    color = PgTextFaint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.query,
                    style = MaterialTheme.typography.titleMedium,
                    color = PgText
                )
                Spacer(modifier = Modifier.height(4.dp))
                val detail = when {
                    result.isLoading -> "Resolving..."
                    result.error != null -> result.error
                    result.addresses.isNotEmpty() -> result.addresses.joinToString(" · ")
                    else -> "No result"
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.error != null) PgDanger else PgTextMuted,
                    fontFamily = MonoFont
                )
            }
            StatusPill(
                text = when {
                    result.isLoading -> "DNS"
                    result.error != null -> "FAIL"
                    else -> "OK"
                },
                background = if (result.error != null) PgDangerDim else PgAccentDim,
                content = if (result.error != null) PgDanger else PgAccent
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun SearchShell(query: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search host, app, IP...", color = PgTextFaint) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = PgTextFaint, modifier = Modifier.size(16.dp))
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun FilterChip(label: String, active: Boolean, activeColor: Color = PgAccent, activeBackground: Color = PgAccentDim, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        StatusPill(
            text = label,
            background = if (active) activeBackground else PgBackgroundAlt,
            content = if (active) activeColor else PgTextMuted
        )
    }
}
