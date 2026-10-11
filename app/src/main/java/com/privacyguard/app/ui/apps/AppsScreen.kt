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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppsScreen(
    onAppClick: (packageName: String, appName: String) -> Unit = { _, _ -> },
    viewModel: AppsViewModel = viewModel()
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val highRiskOnly by viewModel.highRiskOnly.collectAsStateWithLifecycle()
    val blockedOnly by viewModel.blockedOnly.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()

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
                title = stringResource(R.string.apps_title),
                subtitle = stringResource(R.string.apps_subtitle),
                badge = apps.count { it.maxRiskScore >= 70 || it.stalkerwareScore >= 70 }.let { pluralStringResource(R.plurals.apps_high_risk_n, it, it) }.uppercase()
            ) {
                val stalkerwareAlerts = apps.count { it.stalkerwareScore >= 70 }
                if (stalkerwareAlerts > 0) {
                    PanelCard(modifier = Modifier.padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = PgDanger, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(10.dp))
                            Column {
                                Text(stringResource(R.string.apps_stalker_title), style = MaterialTheme.typography.titleMedium, color = PgText)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(pluralStringResource(R.plurals.apps_stalker_desc, stalkerwareAlerts, stalkerwareAlerts), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
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
                    FilterChip(stringResource(R.string.apps_all), !highRiskOnly && !blockedOnly) {
                        viewModel.setHighRiskOnly(false)
                        viewModel.setBlockedOnly(false)
                    }
                    FilterChip(stringResource(R.string.apps_high_risk), highRiskOnly, PgDanger, PgDangerDim) {
                        viewModel.setHighRiskOnly(!highRiskOnly)
                    }
                    FilterChip(stringResource(R.string.dash_blocked), blockedOnly, PgWarning, PgWarningDim) {
                        viewModel.setBlockedOnly(!blockedOnly)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(stringResource(R.string.apps_sort_risk),        sortBy == AppSortBy.RISK,        PgDanger,  PgDangerDim)  { viewModel.setSortBy(AppSortBy.RISK) }
                    FilterChip(stringResource(R.string.appd_sort_data),        sortBy == AppSortBy.DATA,        PgAccent,  PgAccentDim)  { viewModel.setSortBy(AppSortBy.DATA) }
                    FilterChip(stringResource(R.string.appd_tab_connections), sortBy == AppSortBy.CONNECTIONS, PgInfo,    PgInfoDim)    { viewModel.setSortBy(AppSortBy.CONNECTIONS) }
                    FilterChip(stringResource(R.string.appd_sort_background),  sortBy == AppSortBy.BACKGROUND,  PgWarning, PgWarningDim) { viewModel.setSortBy(AppSortBy.BACKGROUND) }
                }
            }
        }

        items(filteredApps, key = { it.packageName }) { app ->
            val (riskLabel, tint, bg) = when {
                app.stalkerwareScore >= 70 -> Triple(stringResource(R.string.apps_stalker).uppercase(), PgDanger, PgDangerDim)
                app.maxRiskScore >= 70 -> Triple(stringResource(R.string.alerts_high).uppercase(), PgDanger, PgDangerDim)
                app.maxRiskScore >= 40 -> Triple(stringResource(R.string.alerts_medium).uppercase(), PgWarning, PgWarningDim)
                else -> Triple(stringResource(R.string.alerts_low).uppercase(), PgAccent, PgAccentDim)
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
                            listOfNotNull(
                                pluralStringResource(R.plurals.apps_destinations, app.totalDestinations, app.totalDestinations),
                                pluralStringResource(R.plurals.apps_suspicious, app.suspiciousCount, app.suspiciousCount),
                                if (app.cleartextCount > 0) pluralStringResource(R.plurals.stats_cleartext_n, app.cleartextCount, app.cleartextCount) else null,
                                if (app.backgroundCount > 0) pluralStringResource(R.plurals.appd_bg_count, app.backgroundCount, app.backgroundCount) else null,
                                if (app.stalkerwareScore >= 40) stringResource(R.string.apps_stalker_score, app.stalkerwareScore) else null,
                            ).joinToString(" · "),
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
                        GradeBadge(app.privacyGrade)
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.clickable {
                            viewModel.togglePackageBlocked(app.packageName, !app.isBlocked)
                        }) {
                            ToggleChip(checked = !app.isBlocked)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
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
        placeholder = { Text(stringResource(R.string.apps_search), color = PgTextFaint) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = PgTextFaint, modifier = Modifier.size(16.dp))
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun GradeBadge(grade: String) {
    val (bg, fg) = when (grade) {
        "A"  -> PgAccentDim  to PgAccent
        "B"  -> PgInfoDim    to PgInfo
        "C"  -> PgWarningDim to PgWarning
        "D"  -> PgDangerDim  to PgDanger
        else -> PgDangerDim  to PgDanger
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(grade, style = MaterialTheme.typography.titleMedium, color = fg)
    }
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
