package com.privacyguard.app.debug

import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Color.TRANSPARENT as Transparent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.ActivityKind
import com.privacyguard.app.ui.components.ActivityRow
import com.privacyguard.app.ui.components.AppRow
import com.privacyguard.app.ui.components.AttentionBanner
import com.privacyguard.app.ui.components.BannerTone
import com.privacyguard.app.ui.components.ChipRow
import com.privacyguard.app.ui.components.DestructiveButton
import com.privacyguard.app.ui.components.EmptyState
import com.privacyguard.app.ui.components.ErrorState
import com.privacyguard.app.ui.components.InfoSheetContent
import com.privacyguard.app.ui.components.LoadingSkeleton
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.PrimaryToggleRow
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SegmentedControl
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.components.SheetBody
import com.privacyguard.app.ui.components.StatusHeroCard
import com.privacyguard.app.ui.components.TechnicalDetails
import com.privacyguard.app.ui.components.TechnicalValue
import com.privacyguard.app.ui.components.WordTone
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import java.util.Locale

/**
 * Debug-only catalog of the 1.1 components (DESIGN_SPEC_1.1.md §3), for screenshots and
 * visual review. Not in the release manifest (src/debug). Started from adb only (DUMP
 * permission, like [DebugHooksReceiver]):
 *
 *   adb shell am start -n com.privacyguard.app.enterprise/com.privacyguard.app.debug.ComponentCatalogActivity \
 *       --es theme dark --es locale ar --ef font 2.0 --es section hero
 *
 * theme: light | dark. locale: en | ar | fr | nl. font: scale (1.0 = normal).
 * section: hero | rows | lists | controls | states | sheets | all (default).
 *
 * Language, direction and font size are applied to this screen only, through a local
 * configuration: the user's app language and the phone's settings are not changed.
 */
class ComponentCatalogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appearance = if (intent.getStringExtra("theme") == "light") Appearance.LIGHT else Appearance.DARK
        val locale = Locale.forLanguageTag(intent.getStringExtra("locale") ?: "en")
        val fontScale = intent.getFloatExtra("font", 1f).coerceIn(0.5f, 3f)
        val section = intent.getStringExtra("section") ?: "all"
        // System bar icons follow the catalog theme, not the phone's, or they vanish on dark.
        enableEdgeToEdge(
            statusBarStyle = if (appearance == Appearance.LIGHT) SystemBarStyle.light(Transparent, Transparent) else SystemBarStyle.dark(Transparent),
            navigationBarStyle = if (appearance == Appearance.LIGHT) SystemBarStyle.light(Transparent, Transparent) else SystemBarStyle.dark(Transparent),
        )

        val config = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
            this.fontScale = fontScale
        }
        val localized = createConfigurationContext(config)
        val rtl = config.layoutDirection == android.util.LayoutDirection.RTL

        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                CoreLockTheme(appearance) {
                    Catalog(section)
                }
            }
        }
    }
}

@Composable
private fun Catalog(section: String) {
    val show = { name: String -> section == "all" || section == name }
    Column(
        verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m),
        modifier = Modifier
            .fillMaxSize()
            .background(CoreLockTheme.colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = CoreLockTheme.spacing.l),
    ) {
        if (show("hero")) HeroSection()
        if (show("rows")) RowsSection()
        if (show("lists")) ListsSection()
        if (show("controls")) ControlsSection()
        if (show("states")) StatesSection()
        if (show("sheets")) SheetsSection()
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = CoreLockTheme.colors.onSurface,
        modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l),
    )
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = CoreLockTheme.spacing.l)) { content() }
}

@Composable
private fun HeroSection() {
    Title("Status hero")
    Padded {
        Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
            var on by remember { mutableStateOf(true) }
            StatusHeroCard(
                state = if (on) ProtectionState.ON else ProtectionState.OFF,
                title = stringResource(if (on) R.string.ui_hero_on else R.string.ui_hero_off),
                subtitle = stringResource(if (on) R.string.ui_hero_sub_standard else R.string.ui_hero_off_sub),
                onToggle = { on = it },
            )
            StatusHeroCard(ProtectionState.STARTING, stringResource(R.string.ui_hero_starting), stringResource(R.string.ui_hero_starting_sub), {})
            StatusHeroCard(ProtectionState.STOPPED_UNEXPECTEDLY, stringResource(R.string.ui_hero_stopped), stringResource(R.string.ui_hero_stopped_sub), {})
            AttentionBanner(
                message = stringResource(R.string.ui_attention_private_dns),
                actionLabel = stringResource(R.string.ui_open_settings),
                onAction = {},
            )
        }
    }
}

@Composable
private fun RowsSection() {
    Title("Rows")
    Surface(color = CoreLockTheme.colors.surface, shape = CoreLockTheme.shapes.card, modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l)) {
        Column {
            var restart by remember { mutableStateOf(true) }
            SectionHeader(stringResource(R.string.ui_protection))
            SettingRow(
                title = stringResource(R.string.ui_protection_level),
                subtitle = stringResource(R.string.ui_level_standard_desc),
                leadingIcon = Icons.Rounded.Tune,
                onClick = {},
            )
            PrimaryToggleRow(
                title = stringResource(R.string.ui_restart_after_crash),
                subtitle = stringResource(R.string.ui_restart_after_crash_sub),
                checked = restart,
                onCheckedChange = { restart = it },
            )
            SettingRow(title = stringResource(R.string.ui_advanced), subtitle = stringResource(R.string.ui_advanced_sub), onClick = {})
        }
    }
}

@Composable
private fun ListsSection() {
    Title("App and activity rows")
    Surface(color = CoreLockTheme.colors.surface, shape = CoreLockTheme.shapes.card, modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l)) {
        Column {
            AppRow("Chrome", stringResource(R.string.ui_app_allowed), {}, riskWord = stringResource(R.string.ui_risk_trackers), blockedToday = 12)
            AppRow("Weather", stringResource(R.string.ui_app_allowed), {}, riskWord = stringResource(R.string.ui_risk_unprotected), riskTone = WordTone.ATTENTION, blockedToday = 3)
            AppRow("Flashlight", stringResource(R.string.ui_app_blocked_background), {}, riskWord = stringResource(R.string.ui_risk_watch), riskTone = WordTone.DANGER)
            ActivityRow("Google ads", stringResource(R.string.ui_reason_ads), ActivityKind.BLOCKED, {}, time = "14:02")
            ActivityRow("login-secure.example", stringResource(R.string.ui_reason_malware), ActivityKind.BLOCKED_DANGER, {}, time = "13:40")
            ActivityRow("example.com", stringResource(R.string.ui_reason_allowed), ActivityKind.ALLOWED, {}, time = "13:12")
        }
    }
}

@Composable
private fun ControlsSection() {
    Title("Buttons, segments, chips")
    Padded {
        Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
            PrimaryButton(stringResource(R.string.ui_allow_everywhere), {}, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.ui_something_not_working), {}, Modifier.fillMaxWidth(), leadingIcon = Icons.Rounded.Build)
            DestructiveButton(stringResource(R.string.ui_clear_all_data), {}, Modifier.fillMaxWidth())
            var filter by remember { mutableIntStateOf(0) }
            val filters = listOf(R.string.ui_filter_blocked, R.string.ui_filter_all)
            SegmentedControl(listOf(0, 1), filter, { stringResource(filters[it]) }, { filter = it })
            var app by remember { mutableIntStateOf(1) }
            val apps = listOf(R.string.ui_app_allowed, R.string.ui_app_blocked_background, R.string.ui_app_blocked)
            SegmentedControl(listOf(0, 1, 2), app, { stringResource(apps[it]) }, { app = it })
            Text(stringResource(R.string.ui_app_blocked_background_desc), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
        }
    }
    var chip by remember { mutableIntStateOf(0) }
    val chips = listOf(R.string.ui_filter_all, R.string.ui_risk_trackers, R.string.ui_risk_background, R.string.ui_risk_unprotected, R.string.ui_risk_high)
    ChipRow(chips.indices.toList(), chip, { stringResource(chips[it]) }, { chip = it })
}

@Composable
private fun StatesSection() {
    Title("Empty, error, loading")
    Surface(color = CoreLockTheme.colors.surface, shape = CoreLockTheme.shapes.card, modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l)) {
        Column {
            EmptyState(stringResource(R.string.ui_empty_activity))
            ErrorState(stringResource(R.string.ui_error_activity), onRetry = {})
            LoadingSkeleton(rows = 2)
        }
    }
    Padded {
        AttentionBanner(
            message = stringResource(R.string.ui_hero_stopped_sub),
            actionLabel = stringResource(R.string.ui_turn_on),
            onAction = {},
            tone = BannerTone.DANGER,
            onDismiss = {},
        )
    }
}

@Composable
private fun SheetsSection() {
    Title("Sheets and technical details")
    Surface(color = CoreLockTheme.colors.surface, shape = CoreLockTheme.shapes.sheet, modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l)) {
        Column(Modifier.padding(top = CoreLockTheme.spacing.xl)) {
            SheetBody("Google ads") {
                Text(stringResource(R.string.ui_reason_ads), style = MaterialTheme.typography.bodyLarge, color = CoreLockTheme.colors.onSurface)
                Text(stringResource(R.string.ui_dns_allow_everywhere_only), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
                PrimaryButton(stringResource(R.string.ui_allow_everywhere), {}, Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.ui_keep_blocked), {}, Modifier.fillMaxWidth())
                TechnicalDetails(initiallyExpanded = true) {
                    TechnicalValue("Stopped by", "DNS")
                    TechnicalValue("Host", "googleads.g.doubleclick.net")
                    TechnicalValue("IP", "142.250.185.66")
                }
            }
        }
    }
    Surface(color = CoreLockTheme.colors.surface, shape = CoreLockTheme.shapes.sheet, modifier = Modifier.padding(horizontal = CoreLockTheme.spacing.l)) {
        Column(Modifier.padding(top = CoreLockTheme.spacing.xl)) {
            SheetBody(stringResource(R.string.ui_what_corelock_cant)) {
                InfoSheetContent(stringResource(R.string.ui_what_corelock_cant_body), onDismiss = {})
            }
        }
    }
}
