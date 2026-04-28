package com.privacyguard.app.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertInboxScreen(
    onBack: () -> Unit,
    vm: AlertInboxViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    val visibleItems = when (state.filter) {
        AlertSeverityFilter.ALL      -> state.items
        AlertSeverityFilter.CRITICAL -> state.items.filter { it.severity >= 9 }
        AlertSeverityFilter.HIGH     -> state.items.filter { it.severity in 7..8 }
        AlertSeverityFilter.MEDIUM   -> state.items.filter { it.severity in 4..6 }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenScaffold(
                title = "Alert Inbox",
                subtitle = "${state.items.size} events · last 7 days",
                badge = if (state.items.any { it.severity >= 9 }) "CRITICAL" else "${state.items.size}",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PgTextMuted)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SeverityChip("All",      state.filter == AlertSeverityFilter.ALL)      { vm.setFilter(AlertSeverityFilter.ALL) } }
                    item { SeverityChip("Critical", state.filter == AlertSeverityFilter.CRITICAL) { vm.setFilter(AlertSeverityFilter.CRITICAL) } }
                    item { SeverityChip("High",     state.filter == AlertSeverityFilter.HIGH)     { vm.setFilter(AlertSeverityFilter.HIGH) } }
                    item { SeverityChip("Medium",   state.filter == AlertSeverityFilter.MEDIUM)   { vm.setFilter(AlertSeverityFilter.MEDIUM) } }
                }
            }
        }

        when {
            state.isLoading -> item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Loading alerts…", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
            visibleItems.isEmpty() -> item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("No alerts", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "No events matched the selected filter. Run the VPN to start collecting data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
            else -> items(visibleItems, key = { it.id }) { alert ->
                AlertCard(alert)
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun AlertCard(alert: AlertItem) {
    val (bg, fg, icon) = alertAppearance(alert)
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(bg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        alert.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = PgText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusPill(severityLabel(alert.severity), bg, fg)
                }
                if (alert.detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        alert.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatTs(alert.timestamp), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                    if (!alert.packageName.isNullOrBlank()) {
                        Text("·", style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                        Text(
                            alert.packageName.substringAfterLast('.'),
                            style = MaterialTheme.typography.labelSmall,
                            color = PgTextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    StatusPill(
        text = label,
        background = if (selected) PgAccentDim else MaterialTheme.colorScheme.surfaceVariant,
        content = if (selected) PgAccent else PgTextMuted,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private data class AlertAppearance(val bg: Color, val fg: Color, val icon: ImageVector)

private fun alertAppearance(alert: AlertItem): AlertAppearance = when {
    alert.type == AlertType.JA3_THREAT  -> AlertAppearance(PgDangerDim, PgDanger, Icons.Default.Fingerprint)
    alert.type == AlertType.WEAK_CIPHER -> AlertAppearance(PgWarningDim, PgWarning, Icons.Default.Key)
    alert.type == AlertType.CT_EVENT    -> AlertAppearance(PgInfoDim, PgInfo, Icons.Default.VerifiedUser)
    alert.type == AlertType.CLEARTEXT   -> AlertAppearance(PgWarningDim, PgWarning, Icons.Default.Lock)
    alert.severity >= 7                 -> AlertAppearance(PgWarningDim, PgWarning, Icons.Default.Warning)
    else                                -> AlertAppearance(PgAccentDim, PgAccent, Icons.Default.Security)
}

private fun severityLabel(s: Int) = when {
    s >= 9 -> "CRITICAL"
    s >= 7 -> "HIGH"
    s >= 4 -> "MED"
    else   -> "LOW"
}

private fun formatTs(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000     -> "${diff / 1_000}s ago"
        diff < 3_600_000  -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else              -> SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
