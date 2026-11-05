package com.privacyguard.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.privacyguard.app.R
import com.privacyguard.app.core.network.PrivateDnsCheck
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.ui.components.AttentionBanner
import com.privacyguard.app.ui.components.InfoSheet
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.dashboard.PrivateDnsStartDialog
import com.privacyguard.app.ui.home.LevelOptions
import com.privacyguard.app.ui.theme.CoreLockTheme

/** The three steps (DESIGN_SPEC_1.1.md §6.7). Never more: a test counts them. */
enum class OnboardingStep { WHAT, VPN, LEVEL }

const val TAG_ONB_NEXT = "onb_next"
const val TAG_ONB_CONTINUE = "onb_continue"
const val TAG_ONB_DONE = "onb_done"
const val TAG_ONB_BATTERY = "onb_battery"
const val TAG_ONB_LEAVES = "onb_leaves"

/**
 * First start: what CoreLock does (and can't), why Android asks about a VPN, and the level.
 *
 * HTTPS inspection is not offered here. Its consent is asked on its own screen, with the
 * full explanation, when someone turns it on.
 *
 * @param onRequestVpn asks Android for the VPN (and notifications); the result comes back
 *   to MainActivity, so the steps go on whatever the answer.
 * @param onFinish the chosen level; the caller stores it and opens Home.
 */
@Composable
fun OnboardingScreen(
    initialLevel: String,
    onVpnConsent: () -> Unit,
    onRequestVpn: () -> Unit,
    onFinish: (level: String) -> Unit,
) {
    val context = LocalContext.current
    var confirmStartHost by remember { mutableStateOf<String?>(null) }
    // Read when the level step shows: the user may have just changed it in Android.
    val batteryRestricted = remember { BatteryCheck.state(context) == BatteryCheck.State.RESTRICTED }
    var step by rememberSaveable { mutableIntStateOf(0) }

    confirmStartHost?.let { host ->
        PrivateDnsStartDialog(
            host = host,
            onOpenSettings = { confirmStartHost = null; PrivateDnsCheck.openSettings(context) },
            onStartAnyway = { confirmStartHost = null; onRequestVpn(); step = OnboardingStep.LEVEL.ordinal },
            onDismiss = { confirmStartHost = null },
        )
    }

    OnboardingContent(
        step = OnboardingStep.entries[step],
        initialLevel = initialLevel,
        batteryRestricted = batteryRestricted,
        onStep = { step = it.ordinal },
        onContinueToVpn = {
            onVpnConsent()
            // As on Home: a Private DNS hostname bypasses the filter, so say so first.
            val host = PrivateDnsCheck.strictHostname(context)
            if (host != null) {
                confirmStartHost = host
            } else {
                onRequestVpn()
                step = OnboardingStep.LEVEL.ordinal
            }
        },
        onOpenBattery = { BatteryCheck.openSettings(context) },
        onFinish = onFinish,
    )
}

@Composable
fun OnboardingContent(
    step: OnboardingStep,
    initialLevel: String,
    batteryRestricted: Boolean,
    onStep: (OnboardingStep) -> Unit,
    onContinueToVpn: () -> Unit,
    onOpenBattery: () -> Unit,
    onFinish: (String) -> Unit,
) {
    var level by rememberSaveable { mutableStateOf(initialLevel) }
    var showLeaves by rememberSaveable { mutableStateOf(false) }
    if (showLeaves) {
        // The full list of what leaves the phone (OutboundHostsDisclosureTest), next to the consent.
        InfoSheet(
            title = stringResource(R.string.onb11_leaves),
            body = stringResource(R.string.onb_c_net_body) + "\n\n" + stringResource(R.string.onb_c_storage_body),
            onDismiss = { showLeaves = false },
        )
    }
    BackHandler(enabled = step != OnboardingStep.WHAT) { onStep(OnboardingStep.entries[step.ordinal - 1]) }

    Surface(color = CoreLockTheme.colors.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(CoreLockTheme.spacing.xl)) {
            Text(
                stringResource(R.string.onb11_step, step.ordinal + 1, OnboardingStep.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = CoreLockTheme.colors.onSurfaceMuted,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = CoreLockTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.l),
            ) {
                when (step) {
                    OnboardingStep.WHAT -> {
                        StepHeader(Icons.Rounded.Shield, R.string.onb11_what_title)
                        Body(R.string.onb11_what_body)
                        Body(R.string.onb11_what_private)
                        Body(R.string.onb11_what_limit)
                    }
                    OnboardingStep.VPN -> {
                        StepHeader(Icons.Rounded.VpnLock, R.string.onb11_vpn_title)
                        Body(R.string.onb11_vpn_body)
                        Body(R.string.onb11_vpn_ask)
                        SecondaryButton(
                            stringResource(R.string.onb11_leaves),
                            onClick = { showLeaves = true },
                            modifier = Modifier.fillMaxWidth().testTag(TAG_ONB_LEAVES),
                        )
                    }
                    OnboardingStep.LEVEL -> {
                        StepHeader(Icons.Rounded.Tune, R.string.onb11_level_title)
                        Body(R.string.onb11_level_body)
                        LevelOptions(current = level, onSelect = { level = it })
                        if (batteryRestricted) {
                            AttentionBanner(
                                message = stringResource(R.string.onb11_battery),
                                actionLabel = stringResource(R.string.onb11_battery_action),
                                onAction = onOpenBattery,
                                modifier = Modifier.testTag(TAG_ONB_BATTERY),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(CoreLockTheme.spacing.l))
            when (step) {
                OnboardingStep.WHAT -> PrimaryButton(
                    stringResource(R.string.onb11_next),
                    onClick = { onStep(OnboardingStep.VPN) },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ONB_NEXT),
                )
                OnboardingStep.VPN -> PrimaryButton(
                    stringResource(R.string.onb11_continue),
                    onClick = onContinueToVpn,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ONB_CONTINUE),
                )
                OnboardingStep.LEVEL -> PrimaryButton(
                    stringResource(R.string.onb11_done),
                    onClick = { onFinish(level) },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ONB_DONE),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.StepHeader(icon: ImageVector, title: Int) {
    Icon(icon, contentDescription = null, tint = CoreLockTheme.colors.primary, modifier = Modifier.size(CoreLockTheme.spacing.xxxl))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = CoreLockTheme.colors.onSurface)
}
