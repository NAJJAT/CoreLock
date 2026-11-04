package com.privacyguard.app.ui.activity

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.ActivityKind
import com.privacyguard.app.ui.components.ActivityRow
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.DestructiveButton
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.PlaceholderAppIcon
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SegmentedControl
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.components.TechnicalDetails
import com.privacyguard.app.ui.components.TechnicalValue
import com.privacyguard.app.ui.rules.SecurityAllowWarningDialog
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.core.session.BlockSource

internal const val TAG_ACTIVITY_LIST = "activity_list"
internal const val TAG_FIX_BUTTON = "activity_fix"

/** Everything Activity can do, so [ActivityContent] is a plain function of its state (tested on the JVM). */
class ActivityActions(
    val onFilter: (ActivityFilter) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSelect: (ActivityItem) -> Unit = {},
    val onOpenFix: () -> Unit = {},
    val onTurnOn: () -> Unit = {},
    /** Null when there is nowhere to go (the old live list moves to Statistics in Phase 6). */
    val onOpenLive: (() -> Unit)? = null,
)

/**
 * Activity (DESIGN_SPEC_1.1.md §6.2), the tab that replaces Traffic: what was stopped,
 * why, in plain words, and the way to allow it from the row itself.
 */
@Composable
fun ActivityScreen(
    onOpenFix: () -> Unit,
    onRequestVpn: () -> Unit,
    onOpenDomain: (String) -> Unit,
    onOpenApp: (packageName: String, appName: String) -> Unit,
    onOpenLive: (() -> Unit)?,
    viewModel: ActivityViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val warning by viewModel.ruleActions.malwareWarning.collectAsStateWithLifecycle()
    val message by viewModel.ruleActions.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.ui_undo)

    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            m.text,
            actionLabel = if (m.undo != null) undoLabel else null,
            duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) m.undo?.invoke()
        viewModel.ruleActions.messageShown(m.id)
    }

    Box(Modifier.fillMaxSize()) {
        ActivityContent(
            state = state,
            actions = ActivityActions(
                onFilter = viewModel::setFilter,
                onRetry = viewModel::retry,
                onSelect = { viewModel.select(it) },
                onOpenFix = onOpenFix,
                onTurnOn = onRequestVpn,
                onOpenLive = onOpenLive,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(CoreLockTheme.spacing.l))
    }

    selected?.let { item ->
        CoreLockBottomSheet(title = item.title, onDismiss = { viewModel.select(null) }) {
            ActivityDetail(
                item = item,
                onAction = { action -> viewModel.perform(action, item) },
                onOpenDomain = { host -> viewModel.select(null); onOpenDomain(host) },
                onOpenApp = { viewModel.select(null); onOpenApp(item.packageName, item.appName) },
            )
        }
    }

    warning?.let { pending ->
        SecurityAllowWarningDialog(
            domain = pending.item.host.orEmpty(),
            onConfirm = { viewModel.perform(pending.action, pending.item, confirmedMalware = true) },
            onDismiss = viewModel.ruleActions::dismissMalwareWarning,
        )
    }
}

@Composable
fun ActivityContent(state: ActivityUiState, actions: ActivityActions) {
    val spacing = CoreLockTheme.spacing
    val listState = rememberLazyListState()
    // Sorting and merging up to 500 rows: once per loaded list and minute, not on every
    // recomposition (the sheet, the snackbar).
    val loaded = state.list as? ActivityList.Loaded
    val groups = remember(loaded?.items, loaded?.now) { loaded?.let { ActivityLogic.grouped(it.items, it.now) }.orEmpty() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(TAG_ACTIVITY_LIST),
    ) {
        item(key = "header") { ActivityHeader(state.filter, actions) }

        when (val list = state.list) {
            ActivityList.Loading -> item(key = "loading") { LoadingSkeleton(rows = 6) }
            ActivityList.Error -> item(key = "error") {
                ErrorState(stringResource(R.string.ui_error_activity), onRetry = actions.onRetry)
            }
            is ActivityList.Loaded -> if (list.items.isEmpty()) {
                item(key = "empty") { ActivityEmpty(state, actions) }
            } else {
                rows(groups, actions)
                if (list.items.size >= ActivityLogic.LIST_LIMIT) {
                    item(key = "limit") {
                        Text(
                            stringResource(R.string.ui_activity_limit, ActivityLogic.LIST_LIMIT),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CoreLockTheme.colors.onSurfaceMuted,
                            modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.l),
                        )
                    }
                }
            }
        }
        item(key = "bottom") { Box(Modifier.padding(bottom = spacing.xxxl)) }
    }
}

@Composable
private fun ActivityHeader(filter: ActivityFilter, actions: ActivityActions) {
    val spacing = CoreLockTheme.spacing
    var menu by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.m),
        modifier = Modifier.padding(start = spacing.screen, end = spacing.screen, top = spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.nav_traffic),
                style = MaterialTheme.typography.titleLarge,
                color = CoreLockTheme.colors.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (actions.onOpenLive != null) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.ui_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_live_connections)) },
                            onClick = { menu = false; actions.onOpenLive.invoke() },
                        )
                    }
                }
            }
        }
        SegmentedControl(
            options = ActivityFilter.entries,
            selected = filter,
            label = { stringResource(if (it == ActivityFilter.BLOCKED) R.string.ui_filter_blocked else R.string.ui_filter_all) },
            onSelect = actions.onFilter,
        )
        SecondaryButton(
            text = stringResource(R.string.ui_something_not_working),
            onClick = actions.onOpenFix,
            leadingIcon = Icons.Rounded.Build,
            modifier = Modifier.fillMaxWidth().testTag(TAG_FIX_BUTTON),
        )
    }
}

private fun LazyListScope.rows(groups: List<Pair<ActivityGroup, List<ActivityItem>>>, actions: ActivityActions) {
    groups.forEach { (group, rows) ->
        item(key = "g_${group.name}") { SectionHeader(stringResource(groupLabel(group))) }
        items(rows, key = { it.id }) { item ->
            val first = item === rows.first()
            val last = item === rows.last()
            ActivityListRow(item, group, first, last, onClick = { actions.onSelect(item) })
        }
    }
}

@Composable
private fun ActivityListRow(item: ActivityItem, group: ActivityGroup, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = rowShape(first, last)
    Column(
        Modifier
            .padding(horizontal = CoreLockTheme.spacing.screen)
            .clip(shape)
            .background(CoreLockTheme.colors.surface),
    ) {
        ActivityRow(
            destination = item.title,
            subtitle = if (item.count > 1) "${item.appName} · ${pluralStringResource(R.plurals.ui_times, item.count, item.count)}" else item.appName,
            reason = stringResource(reasonLabel(item.reason)),
            kind = kindOf(item),
            onClick = onClick,
            time = rowTime(context, item.timestamp, group),
            appIcon = { ItemIcon(item) },
        )
        if (!last) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
    }
}

@Composable
private fun ItemIcon(item: ActivityItem) {
    if (item.appKnown) AppIconImage(item.packageName) else PlaceholderAppIcon(Icons.Rounded.Public)
}

@Composable
private fun ActivityEmpty(state: ActivityUiState, actions: ActivityActions) {
    when {
        !state.protectionOn -> EmptyState(
            stringResource(R.string.ui_empty_activity_off),
            actionLabel = stringResource(R.string.ui_turn_on),
            onAction = actions.onTurnOn,
        )
        state.filter == ActivityFilter.BLOCKED -> EmptyState(stringResource(R.string.ui_empty_activity))
        else -> EmptyState(stringResource(R.string.ui_empty_activity_all))
    }
}

/**
 * The detail sheet's content (§6.2): what, why, the actions for this row, then the
 * technical details, collapsed. The host is shown here because the user needs it to decide.
 */
@Composable
fun ColumnScope.ActivityDetail(
    item: ActivityItem,
    onAction: (ActivityAction) -> Unit,
    onOpenDomain: (String) -> Unit,
    /** null where the sheet is opened from the app's own screen. */
    onOpenApp: (() -> Unit)?,
) {
    val colors = CoreLockTheme.colors
    val context = LocalContext.current
    Text(whatText(item), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    Text(whyText(item), style = MaterialTheme.typography.bodyLarge, color = if (item.reason == ActivityReason.MALWARE_LIST) colors.danger else colors.onSurface)
    if (item.blocked) {
        Text(stringResource(R.string.ui_reason_now_note), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    }
    if (item.reason == ActivityReason.ADS_LIST && item.host != null && ActivityLogic.everywhereOnly(item)) {
        Text(stringResource(R.string.ui_dns_allow_everywhere_only), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    }

    ActionButtons(ActivityLogic.actionsFor(item), onAction)

    if (item.appKnown && onOpenApp != null) {
        SettingRow(title = stringResource(R.string.ui_about_this_app, item.appName), onClick = onOpenApp, modifier = Modifier.padding(top = CoreLockTheme.spacing.s))
    }

    TechnicalDetails {
        item.blockSource?.let { TechnicalValue(stringResource(R.string.conn_blocked_by), stringResource(stoppedByLabel(it))) }
        item.host?.let { TechnicalValue(stringResource(R.string.ui_tech_name), it) }
        item.ip?.let { TechnicalValue(stringResource(R.string.ui_tech_ip), it) }
        item.port?.let { TechnicalValue(stringResource(R.string.ui_tech_port), it.toString()) }
        item.protocol?.let { TechnicalValue(stringResource(R.string.ui_tech_protocol), it) }
        item.encryption?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let { TechnicalValue(stringResource(R.string.conn_security), it) }
        if (!item.isLookup && item.bytesSent + item.bytesReceived > 0) {
            TechnicalValue(
                stringResource(R.string.conn_transferred),
                stringResource(
                    R.string.conn_up_down,
                    com.privacyguard.app.core.utils.ByteFormat.format(item.bytesSent),
                    com.privacyguard.app.core.utils.ByteFormat.format(item.bytesReceived),
                ),
            )
        }
        if (item.appKnown) TechnicalValue(stringResource(R.string.conn_package), item.packageName)
        item.ruleLabel?.let { TechnicalValue(stringResource(R.string.ui_tech_rule), it) }
        TechnicalValue(stringResource(R.string.ui_tech_time), fullTime(context, item.timestamp))
        item.host?.let { host ->
            SettingRow(title = stringResource(R.string.ui_more_about_name), onClick = { onOpenDomain(host) })
        }
    }
}

@Composable
internal fun ActionButtons(actions: List<ActivityAction>, onAction: (ActivityAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s), modifier = Modifier.fillMaxWidth()) {
        actions.forEachIndexed { i, action ->
            val label = stringResource(actionLabel(action))
            val modifier = Modifier.fillMaxWidth().testTag("action_${action.name}")
            when {
                action == ActivityAction.ALLOW_ANYWAY -> DestructiveButton(label, onClick = { onAction(action) }, modifier = modifier)
                i == 0 -> PrimaryButton(label, onClick = { onAction(action) }, modifier = modifier)
                else -> SecondaryButton(label, onClick = { onAction(action) }, modifier = modifier)
            }
        }
    }
}

// ── Words ───────────────────────────────────────────────────────────────────────

/** Rows of one group form a card: rounded corners only at its top and bottom. */
internal fun rowShape(first: Boolean, last: Boolean): androidx.compose.ui.graphics.Shape {
    val corner = 16.dp
    return RoundedCornerShape(
        topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp,
    )
}

internal fun groupLabel(group: ActivityGroup): Int = when (group) {
    ActivityGroup.JUST_NOW -> R.string.ui_group_just_now
    ActivityGroup.TODAY -> R.string.ui_group_today
    ActivityGroup.YESTERDAY -> R.string.ui_group_yesterday
    ActivityGroup.EARLIER -> R.string.ui_group_earlier
}

internal fun reasonLabel(reason: ActivityReason): Int = when (reason) {
    ActivityReason.ADS_LIST -> R.string.ui_reason_ads
    ActivityReason.MALWARE_LIST -> R.string.ui_reason_malware
    ActivityReason.USER_RULE -> R.string.ui_reason_rule
    ActivityReason.LOOKUP_BYPASS -> R.string.ui_reason_lookup_bypass
    // Its own label: port 853 is never let through, so it must not look like the row above.
    ActivityReason.LOOKUP_BYPASS_PORT -> R.string.ui_reason_lookup_bypass_port
    ActivityReason.SETTING -> R.string.ui_reason_setting
    ActivityReason.ALLOWED -> R.string.ui_reason_allowed
}

internal fun actionLabel(action: ActivityAction): Int = when (action) {
    ActivityAction.ALLOW_FOR_APP -> R.string.ui_allow_for_app
    ActivityAction.ALLOW_EVERYWHERE -> R.string.ui_allow_everywhere
    ActivityAction.KEEP_BLOCKED -> R.string.ui_keep_blocked
    ActivityAction.ALLOW_ANYWAY -> R.string.ui_allow_anyway
    ActivityAction.REMOVE_MY_RULE -> R.string.ui_remove_my_rule
    ActivityAction.BLOCK_FOR_APP -> R.string.ui_block_for_app
    ActivityAction.BLOCK_EVERYWHERE -> R.string.ui_block_everywhere
}

internal fun kindOf(item: ActivityItem): ActivityKind = when {
    !item.blocked -> ActivityKind.ALLOWED
    item.reason == ActivityReason.MALWARE_LIST -> ActivityKind.BLOCKED_DANGER
    else -> ActivityKind.BLOCKED
}

private fun stoppedByLabel(source: BlockSource): Int = when (source) {
    BlockSource.DNS -> R.string.conn_blocked_by_dns
    BlockSource.SNI -> R.string.conn_blocked_by_sni
    BlockSource.QUIC -> R.string.conn_blocked_by_quic
    BlockSource.DOH -> R.string.conn_blocked_by_doh
    BlockSource.DOT -> R.string.conn_blocked_by_dot
}

@Composable
internal fun whatText(item: ActivityItem): String {
    val target = item.host ?: item.ip.orEmpty()
    return if (item.title != target) stringResource(R.string.ui_what_named, item.title, target, item.appName)
    else stringResource(R.string.ui_what_plain, target, item.appName)
}

@Composable
internal fun whyText(item: ActivityItem): String = when (item.reason) {
    ActivityReason.ADS_LIST -> stringResource(R.string.ui_why_ads)
    ActivityReason.MALWARE_LIST -> stringResource(R.string.ui_why_malware)
    ActivityReason.USER_RULE -> item.ruleLabel?.let { stringResource(R.string.ui_why_rule_named, it) } ?: stringResource(R.string.ui_why_rule)
    ActivityReason.LOOKUP_BYPASS -> stringResource(R.string.ui_why_lookup_bypass)
    ActivityReason.LOOKUP_BYPASS_PORT -> stringResource(R.string.ui_why_lookup_bypass_port)
    ActivityReason.SETTING -> stringResource(R.string.ui_why_setting)
    ActivityReason.ALLOWED -> stringResource(R.string.ui_why_allowed)
}

private fun rowTime(context: Context, time: Long, group: ActivityGroup): String =
    if (group == ActivityGroup.EARLIER) {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    } else {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)
    }

private fun fullTime(context: Context, time: Long): String =
    DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
