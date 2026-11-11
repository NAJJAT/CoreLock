package com.privacyguard.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.app.vpn.KillSwitch
import java.text.NumberFormat

/**
 * Settings › Kill switch: what Android reports, what it means, and the way to Android's
 * VPN settings. CoreLock can't turn it on itself; only Android blocks traffic once the
 * tunnel is gone (CLAUDE.md §5.7).
 */
@Composable
fun KillSwitchScreen(onBack: () -> Unit, viewModel: SettingsHomeViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    KillSwitchContent(state.lockdown, onBack, onOpen = { KillSwitch.openAlwaysOnSettings(context) })
}

@Composable
fun KillSwitchContent(status: KillSwitch.LockdownStatus, onBack: () -> Unit, onOpen: () -> Unit) {
    SubScreen(stringResource(R.string.ui_set_kill_switch), onBack) {
        StatusCard(
            title = stringResource(SettingsLogic.killSwitchSubtitle(status)),
            good = status == KillSwitch.LockdownStatus.ON,
            modifier = Modifier.testTag(TAG_KS_STATUS),
        )
        Body(stringResource(R.string.ui_ks_body))
        Body(stringResource(R.string.ui_ks_tradeoff), muted = true)
        PrimaryButton(stringResource(R.string.ui_ks_open), onOpen, Modifier.fillMaxWidth().testTag(TAG_KS_OPEN))
    }
}

/** Settings › Blocklists (simple): how many names, when, and Refresh now. Per-list detail is in Diagnostics. */
@Composable
fun BlocklistsScreen(onBack: () -> Unit, viewModel: SettingsHomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    BlocklistsContent(state, onBack, onRefresh = viewModel::refreshLists)
}

@Composable
fun BlocklistsContent(state: SettingsHomeState, onBack: () -> Unit, onRefresh: () -> Unit, now: Long = System.currentTimeMillis()) {
    SubScreen(stringResource(R.string.ui_set_blocklists), onBack) {
        val updated = when (val lists = state.lists) {
            null -> null
            SettingsLogic.ListsUpdated.Never -> stringResource(R.string.ui_set_lists_never)
            is SettingsLogic.ListsUpdated.DateUnknown -> stringResource(
                if (lists.someMissing) R.string.ui_set_lists_date_unknown_some_missing else R.string.ui_set_lists_date_unknown,
            )
            is SettingsLogic.ListsUpdated.At -> stringResource(
                if (lists.someMissing) R.string.ui_set_lists_some_missing else R.string.ui_set_lists_updated,
                relativeTime(lists.at, now),
            )
        }
        StatusCard(
            title = NumberFormat.getIntegerInstance().format(state.listNames),
            label = stringResource(R.string.ui_lists_names),
            subtitle = if (state.listsUpdating) stringResource(R.string.ui_lists_refreshing) else updated,
            // DateUnknown: the lists are loaded and filtering; only their age is unknown.
            good = state.lists is SettingsLogic.ListsUpdated.At || state.lists is SettingsLogic.ListsUpdated.DateUnknown,
            modifier = Modifier.testTag(TAG_LISTS_STATUS),
        )
        // Disabled while a download runs: a second tap would only restart it.
        PrimaryButton(stringResource(R.string.ui_lists_refresh), onRefresh, Modifier.fillMaxWidth().testTag(TAG_LISTS_REFRESH), enabled = !state.listsUpdating)
        Body(stringResource(R.string.ui_lists_auto), muted = true)
        Body(stringResource(R.string.ui_lists_details), muted = true)
    }
}

@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(title = title, onBack = onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.l),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.l),
            content = content,
        )
    }
}

@Composable
private fun StatusCard(title: String, good: Boolean, modifier: Modifier = Modifier, label: String? = null, subtitle: String? = null) {
    val colors = CoreLockTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs),
        modifier = modifier
            .fillMaxWidth()
            .clip(CoreLockTheme.shapes.card)
            .background(colors.surface)
            .padding(CoreLockTheme.spacing.l),
    ) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted)
        // Colour repeats the words; it is never the only signal.
        Text(title, style = MaterialTheme.typography.titleLarge, color = if (good) colors.protected else colors.onSurface)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    }
}

@Composable
private fun Body(text: String, muted: Boolean = false) = Text(
    text,
    style = MaterialTheme.typography.bodyLarge,
    color = if (muted) CoreLockTheme.colors.onSurfaceMuted else CoreLockTheme.colors.onSurface,
)

internal const val TAG_KS_STATUS = "ks_status"
internal const val TAG_KS_OPEN = "ks_open"
internal const val TAG_LISTS_STATUS = "lists_status"
internal const val TAG_LISTS_REFRESH = "lists_refresh"
