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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import com.privacyguard.app.BuildConfig
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

@Composable
fun AppDetailScreen(
    packageName: String,
    appName: String,
    onBack: () -> Unit,
    vm: AppDetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var domainSort by remember { mutableStateOf(DomainSort.COUNT) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenScaffold(
                title = state.appName.ifBlank { appName.ifBlank { packageName.substringAfterLast('.') } },
                subtitle = state.packageName.ifBlank { packageName },
                badge = if (state.detectedSdks.isNotEmpty()) "TRACKERS" else "APP",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PgTextMuted)
                        }
                        AppIconImage(packageName = state.packageName.ifBlank { packageName }, size = 32.dp, cornerRadius = 8.dp)
                    }
                    val shareContext = LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            val report = buildAppReport(state)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "PrivacyGuard report: ${state.appName}")
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            shareContext.startActivity(Intent.createChooser(intent, "Share App Report"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share report", tint = PgTextMuted, modifier = Modifier.size(20.dp))
                        }
                        StatusPill(
                            "${state.mismatchFindings.size} flags",
                            if (state.mismatchFindings.isEmpty()) PgBackgroundAlt else PgWarningDim,
                            if (state.mismatchFindings.isEmpty()) PgTextMuted else PgWarning,
                        )
                        StatusPill("${state.domains.size} domains", PgInfoDim, PgInfo)
                        StatusPill(
                            "${state.detectedSdks.size} SDKs",
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
                            if (state.isBlocked) "App Blocked — tap to unblock" else "App Active — tap to block",
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
                        "Block background network access",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isBackgroundBlocked) PgWarning else PgTextMuted,
                    )
                    com.privacyguard.app.ui.components.ToggleChip(state.isBackgroundBlocked)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailTab("Mismatch", selectedTab == 0) { selectedTab = 0 }
                    DetailTab("Connections", selectedTab == 1) { selectedTab = 1 }
                    DetailTab("Topology", selectedTab == 2) { selectedTab = 2 }
                    DetailTab("SDKs", selectedTab == 3) { selectedTab = 3 }
                    DetailTab("Activity", selectedTab == 4) { selectedTab = 4 }
                    if (BuildConfig.MITM_AVAILABLE) {
                        DetailTab("Payload", selectedTab == 5) { selectedTab = 5 }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> mismatchTab(state)
            1 -> connectionsTab(state, domainSort) { domainSort = it }
            2 -> topologyTab(state)
            3 -> sdkTab(state)
            4 -> activityTab(state)
            5 -> if (BuildConfig.MITM_AVAILABLE) item { PayloadTabContent(state.packageName) }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

private fun LazyListScope.activityTab(state: AppDetailState) {
    item {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Activity summary")
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActivityStat("DNS", state.dnsQueryCount.toString(), "${state.idleDnsQueryCount} idle", PgInfo, Modifier.weight(1f))
                ActivityStat("Connections", state.connectionCount.toString(), "${state.idleConnectionCount} bg", PgAccent, Modifier.weight(1f))
            }
        }
    }
    item { ActivityTimelineChart(state.activityTimeline) }
    item {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Top DNS domains")
            Spacer(modifier = Modifier.height(10.dp))
            if (state.dnsDomains.isEmpty()) {
                Text("No DNS queries recorded for this app yet.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            } else {
                state.dnsDomains.take(8).forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.domain, style = MaterialTheme.typography.bodySmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.idleQueries > 0) {
                                Text("${row.idleQueries} while idle", style = MaterialTheme.typography.labelSmall, color = PgWarning)
                            }
                        }
                        StatusPill(
                            "${row.totalQueries}x",
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
            SectionLabel("Recent activity")
            Spacer(modifier = Modifier.height(10.dp))
            if (state.recentActivity.isEmpty()) {
                Text("No recent app activity recorded yet.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            } else {
                state.recentActivity.forEachIndexed { index, row ->
                    RecentActivityItem(row)
                    if (index != state.recentActivity.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
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
        SectionLabel("24h timeline")
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
                        Text("${row.hour}h", style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text("Green allowed · Yellow idle/background · Red blocked", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
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
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(row.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = PgTextFaint,
        )
    }
}

private fun LazyListScope.mismatchTab(state: AppDetailState) {
    when {
        state.isLoadingMismatch || state.isScanning -> {
            item { LoadingCard("Correlating permissions with observed traffic...") }
        }
        state.mismatchFindings.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("No permission mismatch found", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Declared Android permissions and observed traffic look consistent for this app so far.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
        }
        else -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Permission vs Traffic")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Cross-checking declared permissions against live destinations and tracker infrastructure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
            items(state.mismatchFindings) { finding ->
                MismatchCard(finding)
            }
        }
    }
}

private enum class DomainSort { COUNT, DATA, BACKGROUND }

private fun LazyListScope.connectionsTab(
    state: AppDetailState,
    sort: DomainSort,
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
            DomainSortChip("Count",      sort == DomainSort.COUNT)      { onSortChange(DomainSort.COUNT) }
            DomainSortChip("Data",       sort == DomainSort.DATA)       { onSortChange(DomainSort.DATA) }
            DomainSortChip("Background", sort == DomainSort.BACKGROUND) { onSortChange(DomainSort.BACKGROUND) }
        }
    }
    when {
        state.isLoadingDomains -> {
            item { LoadingCard("Loading connection history...") }
        }
        state.domains.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("No connections recorded yet", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Start the VPN and open this app. Destinations, DNS names, and tracker companies will appear here.",
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
            items(sorted) { row -> DomainCard(row) }
        }
    }
}

private fun LazyListScope.topologyTab(state: AppDetailState) {
    when {
        state.isLoadingDomains -> {
            item { LoadingCard("Tracing DNS and connection path...") }
        }
        state.routeSummary == null || state.topologyHops.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Topology will appear after traffic flows", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Open the app while the VPN is running and PrivacyGuard will build the DNS and route path here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PgTextMuted,
                    )
                }
            }
        }
        else -> {
            val summary = state.routeSummary
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResolverHeader(summary)
                    TopologyRoutePanel(summary)
                }
            }
            items(state.topologyHops) { hop ->
                TimelineHopCard(hop)
            }
            if (state.topologyQueryLog.isNotEmpty()) {
                item {
                    QueryLogCard(state.topologyQueryLog)
                }
            }
        }
    }
}

private fun LazyListScope.sdkTab(state: AppDetailState) {
    when {
        state.isScanning -> {
            item { LoadingCard("Scanning APK for embedded tracker SDKs...") }
        }
        state.detectedSdks.isEmpty() -> {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("No known tracker SDKs detected", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This scan checks APK metadata and signatures. Network behavior is still evaluated separately.",
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
private fun ResolverHeader(summary: TopologyRouteSummary) {
    PanelCard {
        SectionLabel("DNS Topology")
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PgInfoDim, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Public, contentDescription = null, tint = PgInfo, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.resolverLabel, style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${summary.dnsLatencyMs}ms · ${summary.policyLabel} · ${summary.countryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                )
            }
            StatusPill(
                if (summary.resolverLabel.contains("HTTPS")) "DoH" else "DNS",
                PgInfoDim,
                PgInfo,
            )
        }
    }
}

@Composable
private fun TopologyRoutePanel(summary: TopologyRouteSummary) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Route", style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${countryFlag(summary.countryCode)} ${summary.countryName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = PgText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(summary.org, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            StatusPill("${summary.totalLatencyMs}ms", PgAccentDim, PgAccent)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("APP", PgAccentDim, PgAccent)
            StatusPill(
                summary.policyLabel.uppercase(),
                if (summary.policyLabel.contains("Blocked")) PgDangerDim else PgInfoDim,
                if (summary.policyLabel.contains("Blocked")) PgDanger else PgInfo,
            )
            StatusPill(summary.countryCode.ifBlank { "NET" }, PgBackgroundAlt, PgTextMuted)
        }
        Spacer(modifier = Modifier.height(14.dp))
        RouteMap(summary)
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteMetric("DNS", "${summary.dnsLatencyMs}ms", PgInfo)
            RouteMetric("Transport", "${summary.transportLatencyMs}ms", PgAccent)
            RouteMetric("Security", summary.encryptionLabel, if (summary.encryptionLabel.contains("CLEAR")) PgDanger else PgAccent)
        }
    }
}

@Composable
private fun RouteMap(summary: TopologyRouteSummary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0x14111F2F), Color(0x20203B52), Color(0x10111620))),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RouteNode(
                    title = summary.appLabel,
                    subtitle = "Local app",
                    badge = "Phone",
                    tint = PgAccent,
                )
                RouteNode(
                    title = summary.destinationLabel,
                    subtitle = summary.destinationIp,
                    badge = summary.countryCode.ifBlank { "Net" },
                    tint = PgInfo,
                    alignEnd = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Phone", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Text(summary.resolverLabel, style = MaterialTheme.typography.labelSmall, color = PgInfo)
                Text(countryFlag(summary.countryCode), style = MaterialTheme.typography.titleMedium, color = PgText)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp),
            ) {
                val start = Offset(size.width * 0.12f, size.height * 0.75f)
                val end = Offset(size.width * 0.88f, size.height * 0.28f)

                drawLine(
                    color = PgTextFaint.copy(alpha = 0.25f),
                    start = Offset(0f, size.height * 0.5f),
                    end = Offset(size.width, size.height * 0.5f),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f),
                )

                drawCircle(color = PgAccent, radius = 8f, center = start)
                drawCircle(color = PgInfo, radius = 8f, center = end)
                drawCircle(color = Color(0xFF9C7AFF), radius = 7f, center = Offset(size.width * 0.34f, size.height * 0.38f))
                drawCircle(color = PgWarning, radius = 7f, center = Offset(size.width * 0.68f, size.height * 0.62f))

                drawLine(
                    brush = Brush.horizontalGradient(listOf(PgAccent, PgWarning, PgInfo)),
                    start = start,
                    end = end,
                    strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                )

                drawCircle(color = PgWarning, radius = 7f, center = Offset(size.width * 0.5f, size.height * 0.45f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("VPN intercept", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                Text(summary.policyLabel, style = MaterialTheme.typography.labelSmall, color = if (summary.policyLabel.contains("Blocked")) PgDanger else PgAccent)
                Text(summary.resolverLabel, style = MaterialTheme.typography.labelSmall, color = PgInfo)
            }
        }
    }
}

@Composable
private fun RouteNode(
    title: String,
    subtitle: String,
    badge: String,
    tint: Color,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        StatusPill(badge, tint.copy(alpha = 0.18f), tint)
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RouteMetric(label: String, value: String, tint: Color) {
    Column(
        modifier = Modifier
            .background(PgBackgroundAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TimelineHopCard(hop: TopologyHop) {
    val appearance = when (hop.state) {
        TopologyState.SAFE -> HopAppearance(PgAccent, PgAccent.copy(alpha = 0.35f), PgAccentDim, Icons.Default.Route)
        TopologyState.RESOLVER -> HopAppearance(PgInfo, PgInfo.copy(alpha = 0.35f), PgInfoDim, Icons.Default.Public)
        TopologyState.INTERCEPT -> HopAppearance(Color(0xFF9C7AFF), Color(0x409C7AFF), Color(0x229C7AFF), Icons.Default.TrackChanges)
        TopologyState.BLOCKED -> HopAppearance(PgDanger, PgDanger.copy(alpha = 0.35f), PgDangerDim, Icons.Default.Block)
        TopologyState.NEUTRAL -> HopAppearance(PgTextMuted, PgTextFaint.copy(alpha = 0.35f), PgBackgroundAlt, Icons.Default.Wifi)
    }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .size(12.dp)
                    .background(appearance.dotColor, CircleShape),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(80.dp)
                    .background(appearance.lineColor, RoundedCornerShape(999.dp)),
            )
        }
        PanelCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(appearance.cardTint, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(appearance.icon, contentDescription = null, tint = appearance.dotColor, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(hop.label, style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(hop.detail, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusPill(hop.latencyLabel, appearance.cardTint, appearance.dotColor)
                    hop.badge?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryLogCard(items: List<TopologyQueryLogItem>) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Live query log")
            Text("${items.size} recent", style = MaterialTheme.typography.labelSmall, color = PgAccent)
        }
        Spacer(modifier = Modifier.height(12.dp))
        items.forEachIndexed { index, item ->
            TopologyQueryRow(item)
            if (index != items.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
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
                Text(finding.title, style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(finding.summary, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            StatusPill(finding.severity.label, background, tint)
        }
        if (finding.evidence.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            finding.evidence.forEach { line ->
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
        Text("Activity by hour (24h)", style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
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
                            "${hour}h",
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
private fun DomainCard(row: DomainRow) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                    row.trackerName?.let { "$it - ${row.company}" } ?: row.company,
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${row.count}x",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.trackerName != null) PgDanger else PgTextFaint,
                )
                if (row.backgroundCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "${row.backgroundCount} bg",
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
private fun TopologyQueryRow(item: TopologyQueryLogItem) {
    val (background, tint, icon) = when (item.status) {
        "TRACKER", "BLOCKED", "IOC" -> Triple(PgDangerDim, PgDanger, Icons.Default.Block)
        "IP" -> Triple(PgWarningDim, PgWarning, Icons.Default.Route)
        else -> Triple(PgAccentDim, PgAccent, Icons.Default.Security)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(background, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            item.domain,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = PgText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(10.dp))
        StatusPill(item.status, background, tint, modifier = Modifier.width(78.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(item.latencyLabel, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
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
                Text("${sdk.company} - ${sdk.category.label}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            StatusPill(sdk.category.label.uppercase(), PgPanelMuted, tint)
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

private data class HopAppearance(
    val dotColor: Color,
    val lineColor: Color,
    val cardTint: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private fun buildAppReport(state: AppDetailState): String = buildString {
    appendLine("PrivacyGuard App Report")
    appendLine("======================")
    appendLine("App:     ${state.appName}")
    appendLine("Package: ${state.packageName}")
    appendLine()
    appendLine("Traffic (24h)")
    appendLine("  Domains contacted: ${state.domains.size}")
    appendLine("  Background conns:  ${state.domains.sumOf { it.backgroundCount }}")
    val totalBytes = state.domains.sumOf { it.bytesSent + it.bytesReceived }
    appendLine("  Data transferred:  ${formatBytes(totalBytes)}")
    appendLine()
    if (state.detectedSdks.isNotEmpty()) {
        appendLine("Tracker SDKs detected: ${state.detectedSdks.size}")
        state.detectedSdks.take(5).forEach { sdk ->
            appendLine("  · ${sdk.name} (${sdk.company})")
        }
        appendLine()
    }
    if (state.mismatchFindings.isNotEmpty()) {
        appendLine("Permission mismatches: ${state.mismatchFindings.size}")
        state.mismatchFindings.take(3).forEach { f ->
            appendLine("  · ${f.title}")
        }
        appendLine()
    }
    appendLine("Top domains:")
    state.domains.take(8).forEach { d ->
        val tracker = if (d.trackerName != null) " [TRACKER: ${d.trackerName}]" else ""
        appendLine("  ${d.count}x  ${d.domain}$tracker")
    }
    appendLine()
    appendLine("Generated by PrivacyGuard · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val offset = 0x1F1E6 - 'A'.code
    return countryCode.uppercase().map { Character.toChars(it.code + offset).concatToString() }.joinToString("")
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
        LoadingCard("Loading intercepted payloads...")
        return
    }

    if (logs.isEmpty()) {
        PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("No payloads captured yet", style = MaterialTheme.typography.titleMedium, color = PgText)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Enable MITM inspection in Settings → Payload Inspector and run the VPN with this app open.",
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
                            "${log.sizeBytes}B · ${log.protocol}",
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

