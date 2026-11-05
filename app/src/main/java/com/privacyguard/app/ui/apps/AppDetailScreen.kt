package com.privacyguard.app.ui.apps

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.core.detection.MismatchSeverity
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.ui.activity.ActivityDetail
import com.privacyguard.app.ui.activity.ActivityItem
import com.privacyguard.app.ui.activity.kindOf
import com.privacyguard.app.ui.activity.reasonLabel
import com.privacyguard.app.ui.activity.rowShape
import com.privacyguard.app.ui.components.ActivityRow
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.AttentionBanner
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.PlaceholderAppIcon
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SegmentedControl
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.components.TechnicalDetailsHeader
import com.privacyguard.app.ui.components.TechnicalValue
import com.privacyguard.app.ui.components.WordTone
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.rules.SecurityAllowWarningDialog
import com.privacyguard.app.ui.text.FindingText
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.platform.android.ForegroundAppDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What App detail can ask for; a plain class so tests drive the content directly. */
class AppDetailActions(
    val onBack: () -> Unit = {},
    val onStatus: (AppStatus) -> Unit = {},
    val onSelect: (ActivityItem) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenTraffic: () -> Unit = {},
    val onOpenDomain: (String) -> Unit = {},
    val onGrantUsageAccess: () -> Unit = {},
    val onShare: () -> Unit = {},
)

/**
 * App detail (DESIGN_SPEC_1.1.md §6.4): what the app may do (one control), where it
 * connected and what is built into it. 1.0's tabs (Mismatch, Connections, SDKs,
 * Activity, Payload) are all still here: SDKs became "Built-in trackers", the rest is
 * under Technical details, so the first thing shown is never an expert finding.
 */
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
    val selected by vm.selected.collectAsStateWithLifecycle()
    val warning by vm.ruleActions.malwareWarning.collectAsStateWithLifecycle()
    val ruleMessage by vm.ruleActions.message.collectAsStateWithLifecycle()
    val statusMessage by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.ui_undo)

    // A background-only rule needs Usage Access to know the app is in the background.
    // Access can change in system settings: read it again on every resume.
    var usageAccess by remember { mutableStateOf(ForegroundAppDetector(context).hasUsageAccess()) }
    LifecycleResumeEffect(Unit) {
        usageAccess = ForegroundAppDetector(context).hasUsageAccess()
        onPauseOrDispose { }
    }

    LaunchedEffect(ruleMessage?.id) {
        val m = ruleMessage ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(m.text, actionLabel = m.undo?.let { undoLabel }, duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
        vm.ruleActions.messageShown(m.id)
    }
    LaunchedEffect(statusMessage?.id) {
        val m = statusMessage ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(m.text, actionLabel = m.undo?.let { undoLabel }, duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
        vm.messageShown(m.id)
    }

    Box(Modifier.fillMaxSize().background(CoreLockTheme.colors.background)) {
        AppDetailContent(
            state = state,
            usageAccess = usageAccess,
            actions = AppDetailActions(
                onBack = onBack,
                onStatus = vm::setStatus,
                onSelect = { vm.select(it) },
                onRetry = vm::retry,
                onOpenTraffic = onOpenTraffic,
                onOpenDomain = onOpenDomain,
                onGrantUsageAccess = {
                    com.privacyguard.app.adprivacy.AdPrivacyLinks.open(context, com.privacyguard.app.adprivacy.AdPrivacyLinks.usageAccess(context.packageName))
                },
                onShare = { shareReport(context, state) },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(CoreLockTheme.spacing.l))
    }

    selected?.let { item ->
        CoreLockBottomSheet(title = item.title, onDismiss = { vm.select(null) }) {
            ActivityDetail(
                item = item,
                onAction = { action -> vm.perform(action, item) },
                onOpenDomain = { host -> vm.select(null); onOpenDomain(host) },
                onOpenApp = null,
            )
        }
    }
    warning?.let { pending ->
        SecurityAllowWarningDialog(
            domain = pending.item.host.orEmpty(),
            onConfirm = { vm.perform(pending.action, pending.item, confirmedMalware = true) },
            onDismiss = vm.ruleActions::dismissMalwareWarning,
        )
    }
}

@Composable
fun AppDetailContent(state: AppDetailState, usageAccess: Boolean, actions: AppDetailActions) {
    val spacing = CoreLockTheme.spacing
    var explain by remember { mutableStateOf(false) }
    var technical by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().testTag(TAG_APP_DETAIL)) {
        item(key = "top") {
            CoreLockTopBar(title = state.appName, onBack = actions.onBack) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.ui_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_share_report)) }, onClick = { menu = false; actions.onShare() })
                    }
                }
            }
        }
        item(key = "header") { Header(state) { explain = true } }
        item(key = "status") { Column { StatusControl(state, usageAccess, actions) } }

        connectedRecently(state, actions)
        builtInTrackers(state)

        item(key = "tech") {
            TechnicalDetailsHeader(
                expanded = technical,
                onToggle = { technical = !technical },
                modifier = Modifier.padding(horizontal = spacing.screen).padding(top = spacing.xl).testTag(TAG_TECHNICAL),
            )
        }
        if (technical) technicalDetails(state, actions)

        item(key = "bottom") { Spacer(Modifier.height(spacing.xxxl)) }
    }

    if (explain) state.summary?.let { RiskWordSheet(it) { explain = false } }
}

@Composable
private fun Header(state: AppDetailState, onExplain: () -> Unit) {
    val spacing = CoreLockTheme.spacing
    val colors = CoreLockTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.s),
    ) {
        AppIconImage(state.packageName, size = 56.dp, cornerRadius = 16.dp)
        Spacer(Modifier.width(spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val status = state.status?.let { stringResource(it.label) }
            val blocked = state.summary?.blockedToday?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ui_blocked_today_n, it, it) }
            val line = listOfNotNull(status, blocked).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            state.summary?.let { summary ->
                val word = AppsLogic.riskWord(summary)
                val color = when (word.tone) {
                    WordTone.DANGER -> colors.danger
                    WordTone.ATTENTION -> colors.attention
                    WordTone.MUTED -> colors.onSurfaceMuted
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = spacing.minTouchTarget)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ui_risk_word_hint), onClick = onExplain)
                        .testTag(TAG_RISK_WORD),
                ) {
                    Text(stringResource(word.label), style = MaterialTheme.typography.labelLarge, color = color)
                    Spacer(Modifier.width(spacing.xs))
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).width(16.dp).height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusControl(state: AppDetailState, usageAccess: Boolean, actions: AppDetailActions) {
    val spacing = CoreLockTheme.spacing
    SectionHeader(stringResource(R.string.ui_app_status))
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s),
        modifier = Modifier.padding(horizontal = spacing.screen),
    ) {
        val status = state.status
        if (status == null) {
            LoadingSkeleton(rows = 1)
            return@Column
        }
        SegmentedControl(
            options = AppStatus.entries,
            selected = status,
            label = { stringResource(it.label) },
            onSelect = actions.onStatus,
            modifier = Modifier.testTag(TAG_STATUS),
        )
        status.description?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        }
        // The rule is saved either way (1.0 behaviour); without access it can't apply yet.
        if (status == AppStatus.BLOCKED_IN_BACKGROUND && !usageAccess) {
            AttentionBanner(
                message = stringResource(R.string.ui_usage_access_needed),
                actionLabel = stringResource(R.string.ui_open_settings),
                onAction = actions.onGrantUsageAccess,
                modifier = Modifier.testTag(TAG_USAGE_BANNER),
            )
        }
    }
}

private fun LazyListScope.connectedRecently(state: AppDetailState, actions: AppDetailActions) {
    item(key = "cr_header") {
        Column {
            SectionHeader(stringResource(R.string.ui_connected_recently))
            Text(
                stringResource(R.string.ui_connected_recently_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = CoreLockTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen).padding(bottom = CoreLockTheme.spacing.s),
            )
        }
    }
    when (val c = state.connected) {
        ConnectedRecently.Loading -> item(key = "cr_loading") { LoadingSkeleton(rows = 3) }
        ConnectedRecently.Error -> item(key = "cr_error") { ErrorState(stringResource(R.string.ui_error_app), onRetry = actions.onRetry) }
        is ConnectedRecently.Loaded -> if (c.sections.isEmpty()) {
            item(key = "cr_empty") { EmptyState(stringResource(R.string.ui_empty_app_connections)) }
        } else {
            c.sections.forEach { section ->
                val key = section.title ?: "\u0000other"
                item(key = "cr_s_$key") {
                    Text(
                        section.title ?: stringResource(R.string.ui_other_names),
                        style = MaterialTheme.typography.titleSmall,
                        color = CoreLockTheme.colors.onSurface,
                        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen).padding(top = CoreLockTheme.spacing.m, bottom = CoreLockTheme.spacing.xs),
                    )
                }
                itemsIndexed(section.items, key = { _, it -> "cr_${key}_${it.host ?: it.ip}" }) { i, item ->
                    NameRow(item, first = i == 0, last = i == section.items.lastIndex) { actions.onSelect(item) }
                }
            }
        }
    }
}

@Composable
private fun NameRow(item: ActivityItem, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = CoreLockTheme.spacing.screen)
            .clip(rowShape(first, last))
            .background(CoreLockTheme.colors.surface),
    ) {
        ActivityRow(
            destination = item.host ?: item.ip.orEmpty(),
            subtitle = if (item.count > 1) pluralStringResource(R.plurals.ui_times, item.count, item.count) else null,
            reason = stringResource(reasonLabel(item.reason)),
            kind = kindOf(item),
            onClick = onClick,
            time = DateUtils.formatSameDayTime(item.timestamp, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString(),
            appIcon = { PlaceholderAppIcon(Icons.Rounded.Public) },
            modifier = Modifier.testTag("$TAG_NAME_ROW${item.host ?: item.ip}"),
        )
        if (!last) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
    }
}

private fun LazyListScope.builtInTrackers(state: AppDetailState) {
    item(key = "bt_header") {
        Column {
            SectionHeader(stringResource(R.string.ui_built_in_trackers))
            Text(
                stringResource(R.string.ui_built_in_trackers_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = CoreLockTheme.colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen).padding(bottom = CoreLockTheme.spacing.s),
            )
        }
    }
    when {
        state.isScanning -> item(key = "bt_scan") { Muted(stringResource(R.string.ui_checking_app)) }
        state.detectedSdks.isEmpty() -> item(key = "bt_none") { Muted(stringResource(R.string.ui_no_trackers_found)) }
        else -> itemsIndexed(state.detectedSdks, key = { _, it -> "bt_${it.name}" }) { i, sdk ->
            TrackerRow(sdk, first = i == 0, last = i == state.detectedSdks.lastIndex)
        }
    }
}

@Composable
private fun TrackerRow(sdk: TrackerEntry, first: Boolean, last: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier
            .padding(horizontal = CoreLockTheme.spacing.screen)
            .clip(rowShape(first, last))
            .background(CoreLockTheme.colors.surface),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
        ) {
            Text(sdk.name, style = MaterialTheme.typography.titleMedium, color = CoreLockTheme.colors.onSurface)
            Text(
                stringResource(R.string.appd_tracker_company, sdk.company, FindingText.trackerCategory(context, sdk.category)),
                style = MaterialTheme.typography.bodyMedium,
                color = CoreLockTheme.colors.onSurfaceMuted,
            )
        }
        if (!last) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
    }
}

/** 1.0's Mismatch, Activity, Connections and Payload tabs, for experts. */
private fun LazyListScope.technicalDetails(state: AppDetailState, actions: AppDetailActions) {
    item(key = "t_values") {
        Column(
            verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
            modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen),
        ) {
            TechnicalValue(stringResource(R.string.conn_package), state.packageName)
            state.summary?.let { TechnicalValue(stringResource(R.string.ui_tech_risk_grade), it.privacyGrade) }
            TechnicalValue(
                stringResource(R.string.ui_tech_permissions),
                pluralStringResource(R.plurals.ui_tech_permissions_n, state.declaredPermissions.size, state.declaredPermissions.size) +
                    if (state.declaredPermissions.isEmpty()) "" else "\n" + state.declaredPermissions.sorted().joinToString("\n") { it.substringAfterLast('.') },
            )
            SettingRow(title = stringResource(R.string.trf_see_all), onClick = actions.onOpenTraffic)
        }
    }

    // Permissions vs traffic (1.0 "Mismatch")
    item(key = "t_mm") { TechHeading(stringResource(R.string.appd_perm_vs_traffic)) }
    when {
        state.isLoadingMismatch -> item(key = "t_mm_load") { Muted(stringResource(R.string.appd_loading_mismatch)) }
        state.mismatchFindings.isEmpty() -> item(key = "t_mm_none") {
            Muted(stringResource(R.string.appd_no_mismatch) + ". " + stringResource(R.string.appd_no_mismatch_desc))
        }
        else -> itemsIndexed(state.mismatchFindings, key = { _, it -> "t_mm_${it.id}" }) { _, f -> FindingRow(f) }
    }

    // Behaviour (1.0 "Activity")
    item(key = "t_beh") { TechHeading(stringResource(R.string.ui_tech_behaviour)) }
    item(key = "t_beh_values") {
        Column(
            verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
            modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen),
        ) {
            TechnicalValue(
                stringResource(R.string.appd_dns),
                state.dnsQueryCount.toString() + " · " + pluralStringResource(R.plurals.appd_idle_count, state.idleDnsQueryCount, state.idleDnsQueryCount),
            )
            TechnicalValue(
                stringResource(R.string.appd_tab_connections),
                state.connectionCount.toString() + " · " + pluralStringResource(R.plurals.appd_bg_count, state.idleConnectionCount, state.idleConnectionCount),
            )
            Timeline(state.activityTimeline)
            Text(stringResource(R.string.appd_top_dns), style = MaterialTheme.typography.labelMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            if (state.dnsDomains.isEmpty()) {
                Text(stringResource(R.string.appd_no_dns), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            } else {
                state.dnsDomains.take(8).forEach { row ->
                    TechLine(
                        row.domain,
                        pluralStringResource(R.plurals.appd_times, row.totalQueries, row.totalQueries) +
                            if (row.idleQueries > 0) " · " + pluralStringResource(R.plurals.appd_idle_count, row.idleQueries, row.idleQueries) else "",
                    ) { actions.onOpenDomain(row.domain) }
                }
            }
            Text(stringResource(R.string.appd_recent_activity), style = MaterialTheme.typography.labelMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            if (state.recentActivity.isEmpty()) {
                Text(stringResource(R.string.appd_no_recent), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            } else {
                val context = LocalContext.current
                state.recentActivity.forEach { row ->
                    TechLine(row.label, row.detail + " · " + DateUtils.formatDateTime(context, row.timestamp, DateUtils.FORMAT_SHOW_TIME), onClick = null)
                }
            }
        }
    }

    // Every name (1.0 "Connections")
    item(key = "t_names") { TechHeading(stringResource(R.string.ui_tech_names)) }
    if (state.domains.isEmpty()) {
        item(key = "t_names_none") { Muted(stringResource(R.string.ui_empty_app_connections)) }
    } else {
        itemsIndexed(state.domains, key = { _, it -> "t_n_${it.domain}" }) { _, row ->
            val owner = row.company ?: stringResource(R.string.trf_company_unknown)
            val who = row.trackerName?.let { stringResource(R.string.appd_tracker_company, it, owner) } ?: owner
            val extra = listOfNotNull(
                pluralStringResource(R.plurals.appd_times, row.count, row.count),
                row.backgroundCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.appd_bg_count, it, it) },
                (row.bytesSent + row.bytesReceived).takeIf { it > 0 }?.let { formatBytes(it) },
            ).joinToString(" · ")
            Box(Modifier.padding(horizontal = CoreLockTheme.spacing.screen)) {
                TechLine(row.domain, "$who\n$extra") { actions.onOpenDomain(row.domain) }
            }
        }
    }

    // Inspected content (1.0 "Payload")
    item(key = "t_payload") { TechHeading(stringResource(R.string.ui_tech_inspected)) }
    item(key = "t_payload_list") { PayloadList(state.packageName) }
}

@Composable
private fun TechHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = CoreLockTheme.colors.onSurface,
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen).padding(top = CoreLockTheme.spacing.l, bottom = CoreLockTheme.spacing.xs),
    )
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = CoreLockTheme.colors.onSurfaceMuted,
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.s),
    )
}

/** A technical name and a line about it; opens the name's page when [onClick] is set. */
@Composable
private fun TechLine(title: String, detail: String, onClick: (() -> Unit)?) {
    val tap = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(
        Modifier.fillMaxWidth().heightIn(min = CoreLockTheme.spacing.minTouchTarget).then(tap).padding(vertical = CoreLockTheme.spacing.xs),
    ) {
        Text(title, style = com.privacyguard.app.ui.theme.CoreLockTechnicalValue, color = CoreLockTheme.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = CoreLockTheme.colors.onSurfaceMuted)
    }
}

@Composable
private fun FindingRow(finding: PermissionMismatchFinding) {
    val context = LocalContext.current
    val colors = CoreLockTheme.colors
    val tint = when (finding.severity) {
        MismatchSeverity.HIGH -> colors.danger
        MismatchSeverity.MEDIUM -> colors.attention
        MismatchSeverity.LOW -> colors.onSurfaceMuted
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.s),
    ) {
        // Severity in words, not colour alone.
        Text(FindingText.severity(context, finding.severity), style = MaterialTheme.typography.labelMedium, color = tint)
        Text(FindingText.title(context, finding), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Text(FindingText.summary(context, finding), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        FindingText.evidence(context, finding).forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
    }
}

/** Connections per hour: allowed, screen off or app not on screen, blocked (1.0 legend). */
@Composable
private fun Timeline(rows: List<ActivityHourRow>) {
    val colors = CoreLockTheme.colors
    val peak = (rows.maxOfOrNull { it.allowed + it.blocked + it.suspicious } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs)) {
        Text(stringResource(R.string.appd_timeline_24h), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            rows.forEach { row ->
                val total = row.allowed + row.blocked + row.suspicious
                val color = when {
                    row.blocked > 0 -> colors.danger
                    row.suspicious > 0 -> colors.attention
                    total > 0 -> colors.protected
                    else -> colors.surfaceVariant
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height((44f * (total.toFloat() / peak).coerceAtLeast(if (total > 0) 0.08f else 0.03f)).dp)
                        .background(color, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text(stringResource(R.string.appd_hour, h), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, modifier = Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.appd_legend), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
    }
}

/** Inspected content for this app; read only when Technical details is open. */
@Composable
private fun PayloadList(packageName: String) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf<List<PayloadLogEntity>?>(null) }
    LaunchedEffect(packageName) {
        logs = runCatching {
            withContext(Dispatchers.IO) { AppDatabase.getInstance(context).payloadLogDao().logsForPackage(packageName, 100) }
        }.onFailure { android.util.Log.w("AppDetail", "Payloads failed: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
    }
    val list = logs
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen),
    ) {
        when {
            list == null -> Text(stringResource(R.string.appd_loading_payloads), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            list.isEmpty() -> Text(
                stringResource(R.string.appd_no_payloads) + ". " + stringResource(R.string.appd_no_payloads_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = CoreLockTheme.colors.onSurfaceMuted,
            )
            else -> list.forEach { log ->
                val title = "${log.method ?: log.direction} ${log.sniHostname ?: log.destinationIp}${log.urlPath?.let { " $it" } ?: ""}"
                val detail = listOfNotNull(
                    android.text.format.Formatter.formatShortFileSize(context, log.sizeBytes.toLong()),
                    log.protocol,
                    if (log.piiRedacted) "PII" else log.direction,
                ).joinToString(" · ") + (log.body?.takeIf { it.isNotBlank() }?.let { "\n" + it.take(200) } ?: "")
                TechLine(title, detail, onClick = null)
            }
        }
    }
}

private fun shareReport(context: Context, state: AppDetailState) {
    val report = buildAppReport(context, state)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.appd_report_subject, state.appName))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.appd_share_report)))
}

private fun buildAppReport(context: Context, state: AppDetailState): String = buildString {
    appendLine(context.getString(R.string.appd_r_title))
    appendLine("======================")
    appendLine(context.getString(R.string.appd_r_app, state.appName))
    appendLine(context.getString(R.string.appd_r_package, state.packageName))
    appendLine()
    appendLine(context.getString(R.string.appd_r_traffic))
    appendLine(context.getString(R.string.appd_r_domains, state.domains.size))
    appendLine(context.getString(R.string.appd_r_bg, state.domains.sumOf { it.backgroundCount }))
    appendLine(context.getString(R.string.appd_r_data, formatBytes(state.domains.sumOf { it.bytesSent + it.bytesReceived })))
    appendLine()
    if (state.detectedSdks.isNotEmpty()) {
        appendLine(context.getString(R.string.appd_r_sdks, state.detectedSdks.size))
        state.detectedSdks.take(5).forEach { sdk -> appendLine("  · ${sdk.name} (${sdk.company})") }
        appendLine()
    }
    if (state.mismatchFindings.isNotEmpty()) {
        appendLine(context.getString(R.string.appd_r_mismatches, state.mismatchFindings.size))
        state.mismatchFindings.take(3).forEach { f -> appendLine("  · ${f.title}") }
        appendLine()
    }
    appendLine(context.getString(R.string.appd_r_top))
    state.domains.take(8).forEach { d ->
        val tracker = if (d.trackerName != null) context.getString(R.string.appd_r_tracker_tag, d.trackerName) else ""
        appendLine("  ${d.count}x  ${d.domain}$tracker")
    }
    appendLine()
    appendLine(
        context.getString(
            R.string.appd_r_generated,
            DateUtils.formatDateTime(context, System.currentTimeMillis(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
        ),
    )
}

internal const val TAG_APP_DETAIL = "app_detail"
internal const val TAG_STATUS = "app_status"
internal const val TAG_RISK_WORD = "app_risk_word"
internal const val TAG_TECHNICAL = "app_technical"
internal const val TAG_USAGE_BANNER = "app_usage_banner"
internal const val TAG_NAME_ROW = "app_name_row:"
