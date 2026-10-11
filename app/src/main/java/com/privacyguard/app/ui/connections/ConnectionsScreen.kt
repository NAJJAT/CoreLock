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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConnectionsScreen(
    onAppClick: (packageName: String, appName: String) -> Unit = { _, _ -> },
    viewModel: ConnectionsViewModel = viewModel()
) {
    // getFilteredConnections() picks the source and applies the filter and rule
    // state; every input is read here (as remember keys) so the list recomposes when
    // any of them changes, including BLOCK/UNBLOCK. Never fall back to the
    // unfiltered list: an empty result is the answer to the filter.
    val liveConnections by viewModel.connections.collectAsStateWithLifecycle()
    val storedConnections by viewModel.recentConnections.collectAsStateWithLifecycle()
    val blockedRuleKeys by viewModel.blockedRuleKeys.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val connections = remember(liveConnections, storedConnections, blockedRuleKeys, filter) {
        viewModel.getFilteredConnections()
    }
    val filterActive = filter.showBlockedOnly || filter.showCleartextOnly || filter.query.isNotBlank()
    val dnsLookup by viewModel.dnsLookup.collectAsStateWithLifecycle()
    val selectedConnection by viewModel.selectedConnection.collectAsStateWithLifecycle()

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
                title = stringResource(R.string.conn_title),
                subtitle = stringResource(R.string.conn_subtitle),
                badge = stringResource(R.string.dash_badge_live).uppercase()
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
                    FilterChip(stringResource(R.string.common_all) , !filter.showBlockedOnly && !filter.showCleartextOnly) {
                        viewModel.clearFilter()
                    }
                    FilterChip(stringResource(R.string.dash_blocked), filter.showBlockedOnly, PgDanger, PgDangerDim) {
                        viewModel.setFilter(filter.copy(showBlockedOnly = !filter.showBlockedOnly))
                    }
                    FilterChip(stringResource(R.string.conn_cleartext), filter.showCleartextOnly) {
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
                        Text(
                            if (filterActive) stringResource(R.string.conn_no_match) else stringResource(R.string.conn_none_yet),
                            style = MaterialTheme.typography.titleMedium, color = PgTextMuted,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (filterActive) stringResource(R.string.conn_no_match_desc)
                            else stringResource(R.string.conn_none_yet_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        items(connections, key = { it.id }) { connection ->
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
                            text = connection.appName.ifBlank { stringResource(R.string.conn_unknown_app) },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (connection.isBlocked) PgDanger else PgText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = connection.packageName.ifBlank { stringResource(R.string.conn_unknown_package) },
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
                                text = (if (connection.isBlocked) stringResource(R.string.conn_unblock) else stringResource(R.string.ads_block)).uppercase(),
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
                            stringResource(R.string.conn_bg_short),
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
                        text = if (connection.isBlocked) stringResource(R.string.dash_blocked).uppercase() else connection.protocol,
                        background = if (connection.isBlocked) PgDangerDim else PgAccentDim,
                        content = if (connection.isBlocked) PgDanger else PgAccent
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = PgBackgroundAlt)
                Spacer(modifier = Modifier.height(12.dp))

                DetailRow(stringResource(R.string.conn_app), connection.appName)
                DetailRow(stringResource(R.string.conn_package), connection.packageName)
                DetailRow(stringResource(R.string.conn_dns_name), connection.hostName ?: detail.reverseDns ?: if (detail.isResolvingReverseDns) stringResource(R.string.conn_resolving) else stringResource(R.string.conn_unknown))
                DetailRow(stringResource(R.string.conn_security), listOf(connection.securityInfo, connection.encryptionInfo).filter { it.isNotBlank() }.joinToString(" / "))
                DetailRow(stringResource(R.string.conn_transferred), stringResource(R.string.conn_up_down, formatBytes(connection.bytesSent), formatBytes(connection.bytesReceived)))

                Spacer(modifier = Modifier.height(12.dp))
                PanelCard {
                    Column {
                        Text(stringResource(R.string.conn_route), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                        Spacer(modifier = Modifier.height(6.dp))
                        val geo = detail.geo
                        Text(
                            text = if (geo != null) {
                                stringResource(R.string.conn_route_country, geo.countryName, geo.countryCode)
                            } else {
                                stringResource(R.string.conn_route_unknown)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = PgText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = geo?.org ?: stringResource(R.string.conn_no_geo),
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
                        Text(stringResource(R.string.conn_view_app), color = PgAccent)
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
                        Text(if (connection.isBlocked) stringResource(R.string.conn_unblock) else stringResource(R.string.ads_block))
                    }
                    Button(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PgBackgroundAlt,
                            contentColor = PgTextMuted
                        )
                    ) {
                        Text(stringResource(R.string.conn_close))
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
            text = value.ifBlank { stringResource(R.string.conn_unknown) },
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
                    text = stringResource(R.string.conn_dns_lookup),
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
                    result.isLoading -> stringResource(R.string.conn_resolving)
                    result.error != null -> result.error
                    result.addresses.isNotEmpty() -> result.addresses.joinToString(" · ")
                    else -> stringResource(R.string.conn_no_result)
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
                    result.error != null -> stringResource(R.string.set_status_fail).uppercase()
                    else -> stringResource(R.string.conn_ok)
                },
                background = if (result.error != null) PgDangerDim else PgAccentDim,
                content = if (result.error != null) PgDanger else PgAccent
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        // Default-locale digits; international units.
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.getDefault(), "%d B", bytes)
    }
}

@Composable
private fun SearchShell(query: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.conn_search), color = PgTextFaint) },
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
