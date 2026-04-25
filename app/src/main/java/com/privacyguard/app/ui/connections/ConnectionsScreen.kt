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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.lifecycle.viewmodel.compose.viewModel
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

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel = viewModel()
) {
    val liveConnections by viewModel.connections.collectAsState()
    val storedConnections by viewModel.recentConnections.collectAsState()
    val connections = viewModel.getFilteredConnections().ifEmpty {
        if (liveConnections.isNotEmpty()) liveConnections else storedConnections
    }
    val filter by viewModel.filter.collectAsState()

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
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint, bg) = when {
                        connection.isBlocked -> Triple(Icons.Default.Warning, PgDanger, PgDangerDim)
                        connection.securityInfo.contains("TLS 1.0", true) || connection.securityInfo.contains("TLS 1.1", true) ->
                            Triple(Icons.Default.Warning, PgWarning, PgWarningDim)
                        else -> Triple(Icons.Default.Lock, PgAccent, PgAccentDim)
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(bg, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connection.hostName ?: connection.destination,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (connection.isBlocked) PgDanger else PgText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${connection.appName} • :${connection.destinationPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(connection.dataRate, style = MaterialTheme.typography.labelMedium, color = PgTextFaint)
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(modifier = Modifier.clickable(enabled = !connection.isBlocked) {
                            viewModel.blockApp(connection.packageName)
                        }) {
                            StatusPill(
                                text = if (connection.isBlocked) "BLOCKED" else "BLOCK",
                                background = if (connection.isBlocked) PgDangerDim else PgPanelRaised,
                                content = PgDanger
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${connection.securityInfo} • ${connection.encryptionInfo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                    fontFamily = MonoFont
                )
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
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
