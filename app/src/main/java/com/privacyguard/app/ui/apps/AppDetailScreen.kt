package com.privacyguard.app.ui.apps

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.PayloadLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.core.detection.MismatchSeverity
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import com.privacyguard.app.core.tracker.TrackerCategory
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelMuted
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppDetailScreen(
    packageName: String,
    appName: String,
    onBack: () -> Unit,
    onOpenTraffic: () -> Unit = {},
    onOpenDomain: (String) -> Unit = {},
    vm: AppDetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val labelContext = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var domainSort by remember { mutableStateOf(DomainSort.COUNT) }
    // A background-only rule needs Usage Access to know the app is in the background.
    // Access can change in system settings: read it again on every resume.
    var usageAccess by remember { mutableStateOf(com.privacyguard.platform.android.ForegroundAppDetector(labelContext).hasUsageAccess()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        usageAccess = com.privacyguard.platform.android.ForegroundAppDetector(labelContext).hasUsageAccess()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenScaffold(
                // Friendly name only; the package name is not shown.
                title = com.privacyguard.app.ui.traffic.AppNames.label(labelContext, packageName, appName),
                subtitle = null,
                badge = (if (state.detectedSdks.isNotEmpty()) stringResource(R.string.appd_badge_trackers) else stringResource(R.string.appd_badge_app)).uppercase(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.appd_back), tint = PgTextMuted)
                        }
                        AppIconImage(packageName = state.packageName.ifBlank { packageName }, size = 32.dp, cornerRadius = 8.dp)
                    }
                    val shareContext = LocalContext.current
                    val resources = LocalResources.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            val report = buildAppReport(shareContext, state)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.appd_report_subject, state.appName))
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            shareContext.startActivity(Intent.createChooser(intent, resources.getString(R.string.appd_share_report)))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.appd_share_report), tint = PgTextMuted, modifier = Modifier.size(20.dp))
                        }
                        StatusPill(
                            pluralStringResource(R.plurals.appd_flags, state.mismatchFindings.size, state.mismatchFindings.size),
                            if (state.mismatchFindings.isEmpty()) PgBackgroundAlt else PgWarningDim,
                            if (state.mismatchFindings.isEmpty()) PgTextMuted else PgWarning,
                        )
                        StatusPill(pluralStringResource(R.plurals.appd_domains, state.domains.size, state.domains.size), PgInfoDim, PgInfo)
                        StatusPill(
                            pluralStringResource(R.plurals.appd_sdks, state.detectedSdks.size, state.detectedSdks.size),
                            if (state.detectedSdks.isEmpty()) PgAccentDim else PgDangerDim,
                            if (state.detectedSdks.isEmpty()) PgAccent else PgDanger,
                        )
                    } // end inner pills Row
                } // end outer header Row
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (state.isBlocked) PgDangerDim else PgAccentDim,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { vm.toggleBlock() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.isBlocked) Icons.Default.Block else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (state.isBlocked) PgDanger else PgAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (state.isBlocked) stringResource(R.string.appd_blocked_tap) else stringResource(R.string.appd_active_tap),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (state.isBlocked) PgDanger else PgAccent,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PgBackgroundAlt, RoundedCornerShape(10.dp))
                        .clickable { vm.toggleBackgroundBlock() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.appd_block_bg),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isBackgroundBlocked) PgWarning else PgTextMuted,
                    )
                    com.privacyguard.app.ui.components.ToggleChip(state.isBackgroundBlocked)
                }
                if (state.isBackgroundBlocked && !usageAccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    BackgroundRuleNeedsUsageAccess(appName.ifBlank { packageName })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PgInfoDim, RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenTraffic)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.trf_see_all), style = MaterialTheme.typography.titleSmall, color = PgInfo)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTab(stringResource(R.string.appd_tab_mismatch), selectedTab == 0) { selectedTab = 0 }
                    DetailTab(stringResource(R.string.appd_tab_connections), selectedTab == 1) { selectedTab = 1 }
                    DetailTab(stringResource(R.string.appd_tab_sdks), selectedTab == 2) { selectedTab = 2 }
                    DetailTab(stringResource(R.string.appd_tab_activity), selectedTab == 3) { selectedTab = 3 }
                    DetailTab(stringResource(R.string.appd_tab_payload), selectedTab == 4) { selectedTab = 4 }
                }
            }
        }

        when (selectedTab) {
            0 -> mismatchTab(state)
            1 -> connectionsTab(state, domainSort, onOpenDomain) { domainSort = it }
            2 -> sdkTab(state)
            3 -> activityTab(state, onOpenDomain)
            4 -> item { PayloadTabContent(state.packageName) }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

private fun LazyListScope.activityTab(state: AppDetailState, onOpenDomain: (String) -> Unit) {
    item {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel(stringResource(R.string.appd_activity_summary))
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActivityStat(stringResource(R.string.appd_dns), state.dnsQueryCount.toString(), pluralStringResource(R.plurals.appd_idle_count, state.idleDnsQueryCount, state.idleDnsQueryCount), PgInfo, Modifier.weight(1f))
                ActivityStat(stringResource(R.string.appd_tab_connections), state.connectionCount.toString(), pluralStringResource(R.plurals.appd_bg_count, state.idleConnectionCount, state.idleConnectionCount), PgAccent, Modifier.weight(1f))
            }
        }
    }
    item { ActivityTimelineChart(state.activityTimeline) }
    item {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel(stringResource(R.string.appd_top_dns))
            Spacer(modifier = Modifier.height(10.dp))
            if (state.dnsDomains.isEmpty()) {
                Text(stringResource(R.string.appd_no_dns), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            } else {
                state.dnsDomains.take(8).forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenDomain(row.domain) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.domain, style = MaterialTheme.typography.bodySmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.idleQueries > 0) {
                                Text(pluralStringResource(R.plurals.appd_idle_count, row.idleQueries, row.idleQueries), style = MaterialTheme.typography.labelSmall, color = PgWarning)
                            }
                        }
                        StatusPill(
                            pluralStringResource(R.plurals.appd_times, row.totalQueries, row.totalQueries),
                            if (row.blockedQueries > 0) PgDangerDim else PgInfoDim,
                            if (row.blockedQueries > 0) PgDanger else PgInfo,
                        )
                    }
                    if (index != state.dnsDomains.take(8).lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
    item {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel(stringResource(R.string.appd_recent_activity))
            Spacer(modifier = Modifier.height(10.dp))
            if (state.recentActivity.isEmpty()) {
                Text(stringResource(R.string.appd_no_recent), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            } else {
                state.recentActivity.forEachIndexed { index, row ->
                    RecentActivityItem(row)
                    if (index != state.recentActivity.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/** A background-only rule never applies without Usage Access: say so, and offer to grant it. */
@Composable
private fun BackgroundRuleNeedsUsageAccess(appLabel: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PgWarningDim, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.appd_bg_needs_usage, appLabel), style = MaterialTheme.typography.bodySmall, color = PgWarning)
        androidx.compose.material3.TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text(stringResource(R.string.trf_usage_grant), color = PgWarning) }
    }
}

@Composable
private fun ActivityStat(
    label: String,
    value: String,
    sub: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PgBackgroundAlt, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = tint, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
    }
}

@Composable
private fun ActivityTimelineChart(rows: List<ActivityHourRow>) {
    val peak = rows.maxOfOrNull { it.allowed + it.blocked + it.suspicious }?.coerceAtLeast(1) ?: 1
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionLabel(stringResource(R.string.appd_timeline_24h))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            rows.forEach { row ->
                val total = row.allowed + row.blocked + row.suspicious
                val frac = total.toFloat() / peak.toFloat()
                val color = when {
                    row.blocked > 0 -> PgDanger
                    row.suspicious > 0 -> PgWarning
                    total > 0 -> PgAccent
                    else -> PgTextFaint.copy(alpha = 0.18f)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((44f * frac.coerceAtLeast(if (total > 0) 0.08f else 0.02f)).dp)
                            .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                    if (row.hour % 6 == 0) {
                        Text(stringResource(R.string.appd_hour, row.hour), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(stringResource(R.string.appd_legend), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
    }
}

@Composable
private fun RecentActivityItem(row: RecentActivityRow) {
    val tint = when {
        row.blocked -> PgDanger
        row.idle -> PgWarning
        else -> PgAccent
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(tint, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodySmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.detail, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        }
        Text(
            android.text.format.DateUtils.formatDateTime(LocalContext.current, row.timestamp, android.text.format.DateUtils.FORMAT_SHOW_TIME),
            style = MaterialTheme.typography.labelSmall,
            color = PgTextFaint,
        )
    }
}

private fun LazyListScope.mismatchTab(state: AppDetailState) {
    when {
        // Don't wait for the APK scan (tens of seconds on large apps): permission vs
        // traffic findings are ready first, and the scan refines them when it ends.
        state.isLoadingMismatch -> {
            item { LoadingCard(stringResource(R.string.appd_loading_mismatch)) }
        }
        state.mismatchFindings.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.appd_no_mismatch), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.appd_no_mismatch_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
        }
        else -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.appd_perm_vs_traffic))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.appd_perm_vs_traffic_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
            items(state.mismatchFindings, key = { "finding:${it.id}" }) { finding ->
                MismatchCard(finding)
            }
        }
    }
}

private enum class DomainSort { COUNT, DATA, BACKGROUND }

private fun LazyListScope.connectionsTab(
    state: AppDetailState,
    sort: DomainSort,
    onOpenDomain: (String) -> Unit,
    onSortChange: (DomainSort) -> Unit,
) {
    if (state.hourlyActivity.any { it > 0 } && !state.isLoadingDomains) {
        item { HourlyActivityChart(state.hourlyActivity) }
    }
    item {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DomainSortChip(stringResource(R.string.appd_sort_count),      sort == DomainSort.COUNT)      { onSortChange(DomainSort.COUNT) }
            DomainSortChip(stringResource(R.string.appd_sort_data),       sort == DomainSort.DATA)       { onSortChange(DomainSort.DATA) }
            DomainSortChip(stringResource(R.string.appd_sort_background), sort == DomainSort.BACKGROUND) { onSortChange(DomainSort.BACKGROUND) }
        }
    }
    when {
        state.isLoadingDomains -> {
            item { LoadingCard(stringResource(R.string.appd_loading_connections)) }
        }
        state.domains.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.appd_no_connections), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.appd_no_connections_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
        }
        else -> {
            val sorted = when (sort) {
                DomainSort.COUNT      -> state.domains.sortedByDescending { it.count }
                DomainSort.DATA       -> state.domains.sortedByDescending { it.bytesSent + it.bytesReceived }
                DomainSort.BACKGROUND -> state.domains.sortedByDescending { it.backgroundCount }
            }
            items(sorted, key = { "domain:${it.domain}" }) { row -> DomainCard(row) { onOpenDomain(row.domain) } }
        }
    }
}

private fun LazyListScope.sdkTab(state: AppDetailState) {
    when {
        state.isScanning -> {
            item { LoadingCard(stringResource(R.string.appd_scanning)) }
        }
        state.detectedSdks.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.appd_no_sdks), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.appd_no_sdks_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
        }
        else -> {
            items(state.detectedSdks) { sdk ->
                SdkCard(sdk)
            }
        }
    }
}

@Composable
private fun DetailTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        StatusPill(
            text = label,
            background = if (active) PgAccentDim else PgBackgroundAlt,
            content = if (active) PgAccent else PgTextMuted,
        )
    }
}

@Composable
private fun MismatchCard(finding: PermissionMismatchFinding) {
    val (background, tint) = when (finding.severity) {
        MismatchSeverity.LOW -> PgInfoDim to PgInfo
        MismatchSeverity.MEDIUM -> PgWarningDim to PgWarning
        MismatchSeverity.HIGH -> PgDangerDim to PgDanger
    }
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(background, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(com.privacyguard.app.ui.text.FindingText.title(LocalContext.current, finding), style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(com.privacyguard.app.ui.text.FindingText.summary(LocalContext.current, finding), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            StatusPill(com.privacyguard.app.ui.text.FindingText.severity(LocalContext.current, finding.severity).uppercase(), background, tint)
        }
        val evidence = com.privacyguard.app.ui.text.FindingText.evidence(LocalContext.current, finding)
        if (evidence.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            evidence.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodySmall, color = PgTextFaint)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PgAccent)
            Spacer(modifier = Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
    }
}

@Composable
private fun HourlyActivityChart(hourly: List<Int>) {
    if (hourly.isEmpty()) return
    val peak = (hourly.maxOrNull() ?: 1).coerceAtLeast(1)
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.appd_activity_by_hour), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            hourly.forEachIndexed { hour, count ->
                val frac = count.toFloat() / peak.toFloat()
                val barColor = when {
                    hour in 0..5  -> PgInfo.copy(alpha = 0.7f)
                    hour in 22..23 -> PgInfo.copy(alpha = 0.7f)
                    frac > 0.7f   -> PgDanger
                    frac > 0.3f   -> PgWarning
                    else          -> PgAccent.copy(alpha = 0.5f)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((36f * frac.coerceAtLeast(if (count > 0) 0.08f else 0f)).dp)
                            .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                    if (hour % 6 == 0) {
                        Text(
                            stringResource(R.string.appd_hour, hour),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            color = PgTextFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainSortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) PgAccentDim else PgBackgroundAlt,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) PgAccent else PgTextMuted,
        )
    }
}

@Composable
private fun DomainCard(row: DomainRow, onClick: () -> Unit) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (row.trackerName != null) PgDangerDim else PgInfoDim, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (row.trackerName != null) Icons.Default.TrackChanges else Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (row.trackerName != null) PgDanger else PgInfo,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.domain, style = MaterialTheme.typography.titleMedium, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    (row.company ?: stringResource(R.string.trf_company_unknown)).let { owner ->
                        row.trackerName?.let { stringResource(R.string.appd_tracker_company, it, owner) } ?: owner
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    pluralStringResource(R.plurals.appd_times, row.count, row.count),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.trackerName != null) PgDanger else PgTextFaint,
                )
                if (row.backgroundCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        pluralStringResource(R.plurals.appd_bg_count, row.backgroundCount, row.backgroundCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = PgWarning,
                    )
                }
                val totalBytes = row.bytesSent + row.bytesReceived
                if (totalBytes > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        formatBytes(totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = PgTextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun SdkCard(sdk: TrackerEntry) {
    val tint = categoryColor(sdk.category)
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(categoryIcon(sdk.category), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(sdk.name, style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(stringResource(R.string.appd_tracker_company, sdk.company, com.privacyguard.app.ui.text.FindingText.trackerCategory(LocalContext.current, sdk.category)), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            StatusPill(com.privacyguard.app.ui.text.FindingText.trackerCategory(LocalContext.current, sdk.category).uppercase(), PgPanelMuted, tint)
        }
    }
}

private fun categoryColor(cat: TrackerCategory): Color = when (cat) {
    TrackerCategory.ADVERTISING -> PgDanger
    TrackerCategory.ANALYTICS -> PgWarning
    TrackerCategory.CRASH_REPORTING -> PgInfo
    TrackerCategory.FINGERPRINTING -> Color(0xFFB56CFF)
    TrackerCategory.SOCIAL -> Color(0xFF6C8CFF)
    TrackerCategory.PROFILING -> Color(0xFFFF6FAE)
    TrackerCategory.OTHER -> PgTextFaint
}

private fun categoryIcon(cat: TrackerCategory) = when (cat) {
    TrackerCategory.ADVERTISING -> Icons.Default.MonetizationOn
    TrackerCategory.ANALYTICS -> Icons.Default.BarChart
    TrackerCategory.CRASH_REPORTING -> Icons.Default.BugReport
    TrackerCategory.FINGERPRINTING -> Icons.Default.Fingerprint
    TrackerCategory.SOCIAL -> Icons.Default.People
    TrackerCategory.PROFILING -> Icons.Default.Person
    TrackerCategory.OTHER -> Icons.Default.Info
}

private fun buildAppReport(context: android.content.Context, state: AppDetailState): String = buildString {
    appendLine(context.getString(R.string.appd_r_title))
    appendLine("======================")
    appendLine(context.getString(R.string.appd_r_app, state.appName))
    appendLine(context.getString(R.string.appd_r_package, state.packageName))
    appendLine()
    appendLine(context.getString(R.string.appd_r_traffic))
    appendLine(context.getString(R.string.appd_r_domains, state.domains.size))
    appendLine(context.getString(R.string.appd_r_bg, state.domains.sumOf { it.backgroundCount }))
    val totalBytes = state.domains.sumOf { it.bytesSent + it.bytesReceived }
    appendLine(context.getString(R.string.appd_r_data, formatBytes(totalBytes)))
    appendLine()
    if (state.detectedSdks.isNotEmpty()) {
        appendLine(context.getString(R.string.appd_r_sdks, state.detectedSdks.size))
        state.detectedSdks.take(5).forEach { sdk ->
            appendLine("  · ${sdk.name} (${sdk.company})")
        }
        appendLine()
    }
    if (state.mismatchFindings.isNotEmpty()) {
        appendLine(context.getString(R.string.appd_r_mismatches, state.mismatchFindings.size))
        state.mismatchFindings.take(3).forEach { f ->
            appendLine("  · ${f.title}")
        }
        appendLine()
    }
    appendLine(context.getString(R.string.appd_r_top))
    state.domains.take(8).forEach { d ->
        val tracker = if (d.trackerName != null) context.getString(R.string.appd_r_tracker_tag, d.trackerName) else ""
        appendLine("  ${d.count}x  ${d.domain}$tracker")
    }
    appendLine()
    appendLine(context.getString(R.string.appd_r_generated, android.text.format.DateUtils.formatDateTime(context, System.currentTimeMillis(), android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR)))
}

@Composable
private fun PayloadTabContent(packageName: String) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf<List<PayloadLogEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(packageName) {
        loading = true
        logs = withContext(Dispatchers.IO) {
            AppDatabase.getInstance(context).payloadLogDao().logsForPackage(packageName, 100)
        }
        loading = false
    }

    if (loading) {
        LoadingCard(stringResource(R.string.appd_loading_payloads))
        return
    }

    if (logs.isEmpty()) {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.appd_no_payloads), style = MaterialTheme.typography.titleMedium, color = PgText)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.appd_no_payloads_desc),
                style = MaterialTheme.typography.bodySmall,
                color = PgTextMuted,
            )
        }
        return
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        logs.forEach { log ->
            PanelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${log.method ?: log.direction} ${log.sniHostname ?: log.destinationIp}${log.urlPath?.let { " $it" } ?: ""}",
                            style = MaterialTheme.typography.titleSmall,
                            color = PgText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${android.text.format.Formatter.formatShortFileSize(LocalContext.current, log.sizeBytes.toLong())} · ${log.protocol}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PgTextMuted,
                        )
                    }
                    if (log.piiRedacted) {
                        StatusPill("PII", PgDangerDim, PgDanger)
                    } else {
                        StatusPill(log.direction, PgAccentDim, PgAccent)
                    }
                }
                if (!log.body.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        log.body.take(200),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = PgTextFaint,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

