package com.privacyguard.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.adprivacy.AdPrivacyLinks
import com.privacyguard.app.core.network.PrivateDnsCheck
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.ui.components.AttentionBanner
import com.privacyguard.app.ui.components.BannerTone
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.components.StatusHeroCard
import com.privacyguard.app.ui.dashboard.PrivateDnsStartDialog
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.vpn.interception.InspectionStatus
import com.privacyguard.vpn.interception.TlsInterceptionFactory

/**
 * Home: "am I protected?" (DESIGN_SPEC_1.1.md §6.1). The status hero, the level, today's
 * count and at most one attention card. Everything else of the 1.0 Home is in
 * Settings › Advanced › Analysis (parity table §5.2).
 */
@Composable
fun HomeScreen(
    onRequestVpn: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenStatistics: () -> Unit,
    /** Null in builds without HTTPS inspection. */
    onOpenPayloads: (() -> Unit)?,
    /** "Something not working?" (§6.2). */
    onOpenFix: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    // HTTPS inspection is never silent: shown while it decrypts, updated the moment it
    // is switched in the app or by an MDM policy (same as the 1.0 Home).
    val inspection by produceState(TlsInterceptionFactory.status(context)) {
        val watch = TlsInterceptionFactory.observeStatus(context) { value = TlsInterceptionFactory.status(context) }
        awaitDispose { watch.close() }
    }
    var confirmStartHost by remember { mutableStateOf<String?>(null) }
    confirmStartHost?.let { host ->
        PrivateDnsStartDialog(
            host = host,
            onOpenSettings = { confirmStartHost = null; PrivateDnsCheck.openSettings(context) },
            onStartAnyway = { confirmStartHost = null; onRequestVpn() },
            onDismiss = { confirmStartHost = null },
        )
    }

    HomeContent(
        state = state,
        inspection = inspection,
        actions = HomeActions(
            onToggle = { on ->
                if (!on) {
                    VpnManager.stopVpn(context)
                } else {
                    // Checked now, not at the last resume: the setting may have changed.
                    val host = PrivateDnsCheck.strictHostname(context)
                    // MainActivity asks for notifications, then VPN consent.
                    if (host != null) confirmStartHost = host else onRequestVpn()
                }
            },
            onSelectLevel = viewModel::setProtectionLevel,
            onOpenBlocked = onOpenBlocked,
            onRetryBlocked = viewModel::retryBlockedToday,
            onOpenPayloads = onOpenPayloads,
            onOpenFix = onOpenFix,
            onAttentionAction = { attention ->
                when (attention) {
                    is HomeLogic.Attention.PrivateDns -> PrivateDnsCheck.openSettings(context)
                    HomeLogic.Attention.UsageAccess -> AdPrivacyLinks.open(context, AdPrivacyLinks.usageAccess(context.packageName))
                    is HomeLogic.Attention.Battery -> BatteryCheck.openSettings(context)
                    HomeLogic.Attention.InspectionCertificate -> {
                        TlsInterceptionFactory.requestInspectionCertificateInstall()
                        onOpenPayloads?.invoke()
                    }
                    is HomeLogic.Attention.NewAlert -> { viewModel.markAlertsSeen(attention.alert.timestamp); onOpenAlerts() }
                    HomeLogic.Attention.WhatsNew -> { viewModel.finishWhatsNew(); onOpenStatistics() }
                    is HomeLogic.Attention.Strict -> viewModel.acceptStrict()
                }
            },
            onAttentionSecondary = { attention ->
                if (attention == HomeLogic.Attention.WhatsNew) { viewModel.finishWhatsNew(); onOpenPayloads?.invoke() }
            },
            onAttentionDismiss = { attention ->
                when (attention) {
                    is HomeLogic.Attention.Battery -> viewModel.dismissBattery()
                    HomeLogic.Attention.InspectionCertificate -> viewModel.dismissInspectionCertificate()
                    is HomeLogic.Attention.NewAlert -> viewModel.markAlertsSeen(attention.alert.timestamp)
                    HomeLogic.Attention.WhatsNew -> viewModel.finishWhatsNew()
                    is HomeLogic.Attention.Strict -> viewModel.dismissStrict(attention.suggestion.networkKey)
                    // Real problems can't be dismissed (no close button is shown for them).
                    is HomeLogic.Attention.PrivateDns, HomeLogic.Attention.UsageAccess -> {}
                }
            },
        ),
    )
}

/** Everything Home can do, so [HomeContent] is a plain function of its state (tested on the JVM). */
class HomeActions(
    val onToggle: (Boolean) -> Unit = {},
    val onSelectLevel: (String) -> Unit = {},
    val onOpenBlocked: () -> Unit = {},
    val onRetryBlocked: () -> Unit = {},
    val onOpenPayloads: (() -> Unit)? = null,
    val onOpenFix: () -> Unit = {},
    val onAttentionAction: (HomeLogic.Attention) -> Unit = {},
    val onAttentionSecondary: (HomeLogic.Attention) -> Unit = {},
    val onAttentionDismiss: (HomeLogic.Attention) -> Unit = {},
)

@Composable
fun HomeContent(state: HomeUiState, inspection: InspectionStatus, actions: HomeActions) {
    var showLevels by rememberSaveable { mutableStateOf(false) }
    val spacing = CoreLockTheme.spacing

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.m),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen, vertical = spacing.l),
    ) {
        StatusHeroCard(
            state = state.protection,
            title = stringResource(HomeLogic.heroTitle(state.protection)),
            subtitle = state.pausedUntil?.let { until ->
                stringResource(R.string.ui_hero_paused_sub, android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(until)))
            } ?: stringResource(HomeLogic.heroSubtitle(state.protection, state.level, state.stopReason)),
            onToggle = actions.onToggle,
            modifier = Modifier.testTag(TAG_HERO),
        )

        if (inspection != InspectionStatus.OFF) {
            InspectionNotice(byOrganisation = inspection == InspectionStatus.ON_BY_ORGANISATION, onOpen = actions.onOpenPayloads)
        }

        Surface(shape = CoreLockTheme.shapes.card, color = CoreLockTheme.colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column {
                val levelName = stringResource(HomeLogic.levelName(state.level))
                SettingRow(
                    title = stringResource(R.string.ui_protection_level),
                    subtitle = "$levelName · ${stringResource(HomeLogic.levelDescription(state.level))}",
                    onClick = { showLevels = true },
                    modifier = Modifier.testTag(TAG_LEVEL_ROW),
                )
                HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)
                BlockedTodayRow(
                    blocked = state.blockedToday,
                    // Nothing new can be blocked while protection is off; an empty list
                    // behind a 0 would be a dead end.
                    canOpen = state.protection == ProtectionState.ON ||
                        (state.blockedToday as? BlockedToday.Count)?.n?.let { it > 0 } == true,
                    onOpen = actions.onOpenBlocked,
                    onRetry = actions.onRetryBlocked,
                )
            }
        }

        // §6.1: right under the numbers, so a broken app is two taps from a fix.
        SecondaryButton(
            stringResource(R.string.ui_something_not_working),
            onClick = actions.onOpenFix,
            leadingIcon = Icons.Rounded.Build,
            modifier = Modifier.fillMaxWidth().testTag(TAG_HOME_FIX),
        )

        state.attention?.let { HomeAttention(it, hasPayloads = actions.onOpenPayloads != null, actions = actions) }
    }

    if (showLevels) {
        CoreLockBottomSheet(title = stringResource(R.string.ui_protection_level), onDismiss = { showLevels = false }) {
            LevelOptions(
                current = state.level,
                onSelect = { level ->
                    // Applies at once and closes: two taps from Home.
                    actions.onSelectLevel(level)
                    showLevels = false
                },
            )
        }
    }
}

/** Three radio rows, the trade-off first; a whole row is the target. */
@Composable
fun LevelOptions(current: String, onSelect: (String) -> Unit) {
    val colors = CoreLockTheme.colors
    Column(Modifier.selectableGroup()) {
        HomeLogic.LEVELS.forEach { level ->
            val selected = level == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
                    .clip(CoreLockTheme.shapes.small)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(level) })
                    .padding(vertical = CoreLockTheme.spacing.m)
                    .testTag("$TAG_LEVEL_PREFIX$level"),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline),
                )
                Spacer(Modifier.width(CoreLockTheme.spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(HomeLogic.levelName(level)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(HomeLogic.levelDescription(level)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}

/**
 * "Blocked today N ›". While loading, the number is a placeholder block (never a made-up
 * 0); if the count fails, "Couldn't load" and a labelled retry button. No chevron and no
 * click when there is nothing to open.
 */
@Composable
private fun BlockedTodayRow(blocked: BlockedToday, canOpen: Boolean, onOpen: () -> Unit, onRetry: () -> Unit) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val clickable = canOpen && blocked is BlockedToday.Count
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minTouchTarget)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .padding(horizontal = spacing.l, vertical = spacing.m)
            .testTag(TAG_BLOCKED_ROW),
    ) {
        Text(stringResource(R.string.ui_blocked_today), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        when (blocked) {
            BlockedToday.Loading -> {
                val loading = stringResource(R.string.ui_loading)
                Surface(
                    color = colors.surfaceVariant,
                    shape = CoreLockTheme.shapes.small,
                    modifier = Modifier.size(width = 48.dp, height = 20.dp).semantics { contentDescription = loading },
                ) {}
            }
            is BlockedToday.Count -> Text(
                java.text.NumberFormat.getIntegerInstance().format(blocked.n),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TAG_BLOCKED_COUNT),
            )
            BlockedToday.Error -> {
                Text(stringResource(R.string.ui_couldnt_load), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
                IconButton(onClick = onRetry, modifier = Modifier.size(spacing.minTouchTarget)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.ui_try_again))
                }
            }
        }
        if (clickable) {
            Spacer(Modifier.width(spacing.s))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceMuted)
        }
    }
}

/** HTTPS inspection decrypting: a status, not a problem, so it is not the attention card and can't be dismissed. */
@Composable
private fun InspectionNotice(byOrganisation: Boolean, onOpen: (() -> Unit)?) {
    val colors = CoreLockTheme.colors
    Surface(shape = CoreLockTheme.shapes.card, color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
                .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
                .padding(CoreLockTheme.spacing.l),
        ) {
            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = colors.attention)
            Spacer(Modifier.width(CoreLockTheme.spacing.l))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_inspection_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(CoreLockTheme.spacing.xs))
                Text(
                    stringResource(if (byOrganisation) R.string.dash_inspection_org_body else R.string.dash_inspection_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                )
            }
            if (onOpen != null) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceMuted)
            }
        }
    }
}

@Composable
private fun HomeAttention(attention: HomeLogic.Attention, hasPayloads: Boolean, actions: HomeActions) {
    val openSettings = stringResource(R.string.ui_open_settings)
    val dismiss = { actions.onAttentionDismiss(attention) }
    val action = { actions.onAttentionAction(attention) }
    val modifier = Modifier.testTag(TAG_ATTENTION)
    when (attention) {
        is HomeLogic.Attention.PrivateDns ->
            AttentionBanner(stringResource(R.string.ui_attention_private_dns), openSettings, action, modifier)
        HomeLogic.Attention.UsageAccess ->
            AttentionBanner(stringResource(R.string.ui_attention_usage_access), openSettings, action, modifier)
        is HomeLogic.Attention.Battery -> AttentionBanner(
            stringResource(if (attention.canDismiss) R.string.ui_attention_battery_optimized else R.string.ui_attention_battery_restricted),
            openSettings, action, modifier,
            onDismiss = if (attention.canDismiss) dismiss else null,
        )
        HomeLogic.Attention.InspectionCertificate -> AttentionBanner(
            stringResource(R.string.ui_attention_inspection_cert),
            stringResource(R.string.ui_install_certificate), action, modifier,
            onDismiss = dismiss,
        )
        is HomeLogic.Attention.NewAlert -> AttentionBanner(
            stringResource(R.string.ui_attention_alert, attention.alert.title),
            stringResource(R.string.ui_open_alerts), action, modifier,
            tone = BannerTone.DANGER,
            onDismiss = dismiss,
        )
        HomeLogic.Attention.WhatsNew -> AttentionBanner(
            stringResource(R.string.ui_whats_new),
            stringResource(R.string.ui_open_statistics), action, modifier,
            onDismiss = dismiss,
            secondaryAction = if (hasPayloads) {
                stringResource(R.string.ui_open_https_inspection) to { actions.onAttentionSecondary(attention) }
            } else null,
        )
        is HomeLogic.Attention.Strict -> {
            val s = attention.suggestion
            // Without location access every Wi-Fi shares one key (NetworkIdentity), so a
            // dismissal covers all Wi-Fi networks: say so instead of implying "this one".
            val shared = s.networkKey == com.privacyguard.app.core.network.NetworkIdentity.SHARED_WIFI_KEY
            val message = buildString {
                append(stringResource(R.string.dash_strict_title, s.networkLabel)).append(' ')
                append(stringResource(R.string.dash_strict_body, s.score))
                if (shared) append(' ').append(stringResource(R.string.dash_strict_shared_note))
            }
            AttentionBanner(message, stringResource(R.string.dash_strict_accept), action, modifier, onDismiss = dismiss)
        }
    }
}

const val TAG_HERO = "home_hero"
const val TAG_HOME_FIX = "home_fix"
const val TAG_LEVEL_ROW = "home_level_row"
const val TAG_LEVEL_PREFIX = "home_level_"
const val TAG_BLOCKED_ROW = "home_blocked_row"
const val TAG_BLOCKED_COUNT = "home_blocked_count"
const val TAG_ATTENTION = "home_attention"
