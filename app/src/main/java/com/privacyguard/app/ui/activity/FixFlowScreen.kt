package com.privacyguard.app.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.vpn.ProtectionPause
import com.privacyguard.app.ui.components.ActivityRow
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.PlaceholderAppIcon
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.rules.SecurityAllowWarningDialog
import com.privacyguard.app.ui.theme.CoreLockTheme

internal const val TAG_FIX_NOT_SURE = "fix_not_sure"
private const val STEPS = 3

/** What the fix flow can do, so [FixFlowContent] is a plain function of its state. */
class FixFlowActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onChooseApp: (packageName: String?, appName: String?) -> Unit = { _, _ -> },
    val onChooseName: (ActivityLogic.FixName) -> Unit = {},
    val onAction: (ActivityAction, ActivityItem) -> Unit = { _, _ -> },
    val onOpenDomain: (String) -> Unit = {},
    val onOpenApp: (ActivityItem) -> Unit = {},
    /** "Pause protection for 5 minutes" (D1); null where the screen has no pause. */
    val onPause: (() -> Unit)? = null,
    /** Opens Android's "Alarms & reminders" for CoreLock, which a pause needs on Android 12+. */
    val onAllowAlarms: () -> Unit = {},
)

/**
 * "Something not working?" (DESIGN_SPEC_1.1.md §6.2): which app → what was blocked →
 * allow it. Three taps, no typing. Back goes one step back, then closes.
 */
@Composable
fun FixFlowScreen(
    onClose: () -> Unit,
    onOpenDomain: (String) -> Unit,
    onOpenApp: (packageName: String, appName: String) -> Unit,
    onPause: (() -> Unit)?,
    viewModel: FixFlowViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val warning by viewModel.ruleActions.malwareWarning.collectAsStateWithLifecycle()
    val message by viewModel.ruleActions.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.ui_undo)

    BackHandler(enabled = state.step != FixStep.App) { viewModel.back() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPause()
        onPauseOrDispose { }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

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
        FixFlowContent(
            state = state,
            actions = FixFlowActions(
                onBack = { if (!viewModel.back()) onClose() },
                onRetry = viewModel::load,
                onChooseApp = viewModel::chooseApp,
                onChooseName = viewModel::chooseName,
                onAction = { action, item -> viewModel.perform(action, item) },
                onOpenDomain = onOpenDomain,
                onOpenApp = { onOpenApp(it.packageName, it.appName) },
                onPause = onPause,
                onAllowAlarms = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) runCatching {
                        context.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ))
                    }.onFailure { android.util.Log.w("FixFlow", "alarm settings: ${it.javaClass.simpleName}") }
                },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(CoreLockTheme.spacing.l))
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
fun FixFlowContent(
    state: FixUiState,
    actions: FixFlowActions,
) {
    val step = state.step
    val stepNumber = when (step) { FixStep.App -> 1; is FixStep.Names -> 2; is FixStep.Decide -> 3 }
    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(
            title = stringResource(R.string.ui_something_not_working),
            subtitle = stringResource(R.string.ui_fix_step_n, stepNumber, STEPS),
            onBack = actions.onBack,
        )
        when (val data = state.data) {
            FixData.Loading -> LoadingSkeleton(rows = 5)
            FixData.Error -> ErrorState(stringResource(R.string.ui_error_activity), onRetry = actions.onRetry)
            is FixData.Loaded -> when (step) {
                FixStep.App -> AppStep(data.items, state.pause, actions)
                is FixStep.Names -> {
                    val lists = remember(step, data.items, state.handled) { fixStepNames(step, data.items, state.handled) }
                    NamesStep(step, lists, anyHandled = state.handled.isNotEmpty(), state.pause, actions)
                }
                is FixStep.Decide -> DecideStep(step, actions)
            }
        }
    }
}

@Composable
private fun AppStep(items: List<ActivityItem>, pause: ProtectionPause.Availability, actions: FixFlowActions) {
    val apps = ActivityLogic.fixApps(items)
    LazyColumn(Modifier.fillMaxSize()) {
        item { StepHeading(R.string.ui_fix_which_app, R.string.ui_fix_which_app_sub) }
        item { Box(Modifier.padding(horizontal = CoreLockTheme.spacing.screen)) { Card {
            apps.forEach { app ->
                ChoiceRow(
                    title = app.appName,
                    subtitle = pluralStringResource(R.plurals.ui_fix_attempts, app.blocked, app.blocked),
                    icon = { AppIconImage(app.packageName) },
                    onClick = { actions.onChooseApp(app.packageName, app.appName) },
                )
                HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
            }
            ChoiceRow(
                title = stringResource(R.string.ui_fix_not_sure),
                subtitle = stringResource(R.string.ui_fix_not_sure_sub),
                icon = { PlaceholderAppIcon(Icons.AutoMirrored.Rounded.HelpOutline) },
                onClick = { actions.onChooseApp(null, null) },
                modifier = Modifier.testTag(TAG_FIX_NOT_SURE),
            )
        } } }
        pauseFooter(pause, actions)
    }
}

@Composable
private fun NamesStep(
    step: FixStep.Names,
    lists: Pair<List<ActivityLogic.FixName>, List<ActivityLogic.FixName>>,
    anyHandled: Boolean,
    pause: ProtectionPause.Availability,
    actions: FixFlowActions,
) {
    val (own, unattributed) = lists
    LazyColumn(Modifier.fillMaxSize()) {
        item { StepHeading(R.string.ui_fix_blocked_hour, R.string.ui_fix_blocked_hour_sub, step.appName) }
        if (own.isEmpty() && unattributed.isEmpty()) {
            item {
                // Empty because the user just fixed everything: saying "CoreLock is probably not
                // the cause" here would contradict what they just did.
                EmptyState(
                    stringResource(
                        when {
                            anyHandled -> R.string.ui_fix_all_handled
                            step.packageName == null -> R.string.ui_fix_none_hour
                            else -> R.string.ui_fix_none_app
                        },
                    ),
                )
            }
        } else {
            nameRows("own", own, actions)
            if (unattributed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ui_fix_unknown_app_names)) }
                nameRows("unattributed", unattributed, actions)
            }
        }
        pauseFooter(pause, actions)
    }
}

private fun LazyListScope.nameRows(key: String, names: List<ActivityLogic.FixName>, actions: FixFlowActions) {
    // The id, not the host: a name-less row (IP only) would otherwise share the key "null".
    itemsIndexed(names, key = { _, n -> "$key:${n.item.id}" }) { index, name ->
        val first = index == 0
        val last = index == names.lastIndex
        val shape = rowShape(first, last)
        Box(Modifier.padding(horizontal = CoreLockTheme.spacing.screen)) {
            Column(
                Modifier
                    .clip(shape)
                    .background(CoreLockTheme.colors.surface),
            ) {
                ActivityRow(
                    destination = name.item.title,
                    subtitle = pluralStringResource(R.plurals.ui_fix_attempts, name.attempts, name.attempts),
                    reason = stringResource(reasonLabel(name.item.reason)),
                    kind = kindOf(name.item),
                    onClick = { actions.onChooseName(name) },
                    appIcon = { if (name.item.appKnown) AppIconImage(name.item.packageName) else PlaceholderAppIcon(Icons.Rounded.Public) },
                )
                if (!last) HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
            }
        }
    }
}

@Composable
private fun DecideStep(step: FixStep.Decide, actions: FixFlowActions) {
    val item = step.name.item
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CoreLockTheme.spacing.xl, vertical = CoreLockTheme.spacing.l),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, color = CoreLockTheme.colors.onSurface)
        ActivityDetail(
            item = item,
            onAction = { action -> actions.onAction(action, item) },
            onOpenDomain = actions.onOpenDomain,
            onOpenApp = { actions.onOpenApp(item) },
        )
    }
}

@Composable
private fun StepHeading(title: Int, subtitle: Int, appName: String? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs),
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.m),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = CoreLockTheme.colors.onSurface)
        if (appName != null) Text(appName, style = MaterialTheme.typography.bodyLarge, color = CoreLockTheme.colors.onSurface)
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
    }
}

/** D1: the last resort when the list doesn't show the cause. */
/**
 * D1: offered only where the pause is sure to end by itself (ProtectionPause). Where it
 * isn't, the reason is said instead of a button that would do nothing or worse.
 */
private fun LazyListScope.pauseFooter(pause: ProtectionPause.Availability, actions: FixFlowActions) {
    val onPause = actions.onPause ?: return
    if (pause == ProtectionPause.Availability.NOT_RUNNING) return
    item(key = "pause") {
        Column(
            verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s),
            modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.xl),
        ) {
            val text = when (pause) {
                ProtectionPause.Availability.NEEDS_EXACT_ALARMS -> R.string.ui_pause_needs_alarms
                ProtectionPause.Availability.ALWAYS_ON -> R.string.ui_pause_always_on
                else -> R.string.ui_fix_try_pause
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            when (pause) {
                ProtectionPause.Availability.AVAILABLE ->
                    SecondaryButton(stringResource(R.string.ui_pause_5), onClick = onPause, modifier = Modifier.fillMaxWidth().testTag(TAG_FIX_PAUSE))
                ProtectionPause.Availability.NEEDS_EXACT_ALARMS ->
                    SecondaryButton(stringResource(R.string.ui_open_settings), onClick = actions.onAllowAlarms, modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = CoreLockTheme.shapes.card, color = CoreLockTheme.colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CoreLockTheme.spacing.l, vertical = CoreLockTheme.spacing.m),
    ) {
        icon()
        Spacer(Modifier.width(CoreLockTheme.spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = CoreLockTheme.colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = CoreLockTheme.colors.onSurfaceMuted)
    }
}
internal const val TAG_FIX_PAUSE = "fix_pause"
