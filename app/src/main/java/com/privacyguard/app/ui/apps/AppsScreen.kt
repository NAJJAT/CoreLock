package com.privacyguard.app.ui.apps

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
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
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.ToggleChip
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelMuted
import com.privacyguard.app.ui.theme.PgPanelRaised
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

@Composable
fun AppsScreen(
    onAppClick: (packageName: String, appName: String) -> Unit = { _, _ -> },
    viewModel: AppsViewModel = viewModel()
) {
    val apps by viewModel.apps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val highRiskOnly by viewModel.highRiskOnly.collectAsState()
    val blockedOnly by viewModel.blockedOnly.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()

    val filteredApps = apps
        .filter { app ->
            val matchesQuery = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, true) ||
                app.packageName.contains(searchQuery, true)
            val matchesRisk = !highRiskOnly || app.maxRiskScore >= 70
            val matchesBlocked = !blockedOnly || app.isBlocked
            matchesQuery && matchesRisk && matchesBlocked
        }
        .let { list ->
            when (sortBy) {
                AppSortBy.RISK        -> list.sortedByDescending { it.maxRiskScore }
                AppSortBy.DATA        -> list.sortedByDescending { it.totalBytesOut }
                AppSortBy.CONNECTIONS -> list.sortedByDescending { it.totalDestinations }
                AppSortBy.BACKGROUND  -> list.sortedByDescending { it.backgroundCount }
            }
        }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Apps",
                subtitle = "Per-app privacy risk and traffic visibility",
                badge = "${apps.count { it.maxRiskScore >= 70 || it.stalkerwareScore >= 70 }} HIGH RISK"
            ) {
                val stalkerwareAlerts = apps.count { it.stalkerwareScore >= 70 }
                if (stalkerwareAlerts > 0) {
                    PanelCard(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = PgDanger, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(10.dp))
                            Column {
                                Text("Potential stalkerware signals", style = MaterialTheme.typography.titleMedium, color = PgText)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("$stalkerwareAlerts apps matched hidden-install, night-activity, or background-tracking patterns.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                            }
                        }
                    }
                }
                SearchShell(
                    query = searchQuery,
                    onValueChange = viewModel::setSearchQuery
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All apps", !highRiskOnly && !blockedOnly) {
                        viewModel.setHighRiskOnly(false)
                        viewModel.setBlockedOnly(false)
                    }
                    FilterChip("High risk", highRiskOnly, PgDanger, PgDangerDim) {
                        viewModel.setHighRiskOnly(!highRiskOnly)
                    }
                    FilterChip("Blocked", blockedOnly, PgWarning, PgWarningDim) {
                        viewModel.setBlockedOnly(!blockedOnly)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("Risk",        sortBy == AppSortBy.RISK,        PgDanger,  PgDangerDim)  { viewModel.setSortBy(AppSortBy.RISK) }
                    FilterChip("Data",        sortBy == AppSortBy.DATA,        PgAccent,  PgAccentDim)  { viewModel.setSortBy(AppSortBy.DATA) }
                    FilterChip("Connections", sortBy == AppSortBy.CONNECTIONS, PgInfo,    PgInfoDim)    { viewModel.setSortBy(AppSortBy.CONNECTIONS) }
                    FilterChip("Background",  sortBy == AppSortBy.BACKGROUND,  PgWarning, PgWarningDim) { viewModel.setSortBy(AppSortBy.BACKGROUND) }
                }
            }
        }

        items(filteredApps) { app ->
            val (riskLabel, tint, bg) = when {
                app.stalkerwareScore >= 70 -> Triple("STALKER", PgDanger, PgDangerDim)
                app.maxRiskScore >= 70 -> Triple("HIGH", PgDanger, PgDangerDim)
                app.maxRiskScore >= 40 -> Triple("MED", PgWarning, PgWarningDim)
                else -> Triple("LOW", PgAccent, PgAccentDim)
            }
            PanelCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { onAppClick(app.packageName, app.appName) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconImage(packageName = app.packageName)
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                app.appName,
                                style = MaterialTheme.typography.titleMedium,
                                color = PgText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            StatusPill(riskLabel, bg, tint)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${app.totalDestinations} destinations · ${app.suspiciousCount} suspicious" +
                                (if (app.cleartextCount > 0) " · ${app.cleartextCount} cleartext" else "") +
                                (if (app.backgroundCount > 0) " · ${app.backgroundCount} bg" else "") +
                                (if (app.stalkerwareScore >= 40) " · stalkerware ${app.stalkerwareScore}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(PgBackgroundAlt, RoundedCornerShape(999.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((app.maxRiskScore / 100f).coerceAtLeast(0.08f))
                                    .height(4.dp)
                                    .background(tint, RoundedCornerShape(999.dp))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier.clickable {
                            viewModel.togglePackageBlocked(app.packageName, !app.isBlocked)
                        }) {
                            ToggleChip(checked = !app.isBlocked)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(formatBytes(app.totalBytesOut), style = MaterialTheme.typography.labelMedium, color = PgTextMuted)
                    }
                }
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
        placeholder = { Text("Search app or package...", color = PgTextFaint) },
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
