package com.privacyguard.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.R
import com.privacyguard.app.ui.components.CoreLockBottomSheet
import com.privacyguard.app.ui.components.DestructiveButton
import com.privacyguard.app.ui.components.InfoSheet
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.PrimaryToggleRow
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.components.SectionHeader
import com.privacyguard.app.ui.components.SettingRow
import com.privacyguard.app.ui.home.HomeLogic
import com.privacyguard.app.ui.home.LevelOptions
import com.privacyguard.app.ui.security.rememberProtectedActionRunner
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import java.util.Locale

/** Where Settings rows lead; the nav graph fills these in. */
class SettingsNav(
    val onOpenRules: () -> Unit = {},
    val onOpenBlocklists: () -> Unit = {},
    val onOpenKillSwitch: () -> Unit = {},
    val onOpenLicenses: () -> Unit = {},
    val onOpenAdvanced: () -> Unit = {},
)

/** What the content can ask for; a plain class so tests drive [SettingsHomeContent] directly. */
class SettingsActions(
    val nav: SettingsNav = SettingsNav(),
    val onLevel: (String) -> Unit = {},
    val onAutoRestart: (Boolean) -> Unit = {},
    val onNotifications: (Boolean) -> Unit = {},
    val onNotificationDetails: (Boolean) -> Unit = {},
    val onWeekly: (Boolean) -> Unit = {},
    val onLanguage: () -> Unit = {},
    val onAppearance: (Appearance) -> Unit = {},
    val onRetention: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onScreenshots: (Boolean) -> Unit = {},
    val onClear: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    /** After the "keep it private" sheet: opens Android's picker to choose where to save. */
    val onExportConfig: () -> Unit = {},
    /** Opens Android's picker to choose a file; the confirmation follows. */
    val onImportConfig: () -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onCancelImport: () -> Unit = {},
)

/**
 * Settings (DESIGN_SPEC_1.1.md §6.5): short rows in six groups, then Advanced. Each
 * setting writes what it wrote in 1.0; the expert settings are behind Advanced.
 */
@Composable
fun SettingsHomeScreen(nav: SettingsNav, viewModel: SettingsHomeViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var askAlarms by rememberSaveable { mutableStateOf(false) }
    val runProtected = rememberProtectedActionRunner()
    val importUi by viewModel.import.collectAsStateWithLifecycle()
    // Android's own file picker: the user chooses the place; the app gets access to that one file only.
    val saveConfig = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportConfig(uri) }
    val openConfig = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.previewImport(uri) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(event) {
        when (val e = event) {
            is SettingsEvent.Share -> runCatching {
                context.startActivity(com.privacyguard.app.ui.statistics.HistoryExport.shareIntent(context, e.file, resources.getString(R.string.ui_set_export)))
            }.onFailure { Toast.makeText(context, resources.getString(R.string.ui_set_no_browser), Toast.LENGTH_SHORT).show() }
            SettingsEvent.ExportFailed -> Toast.makeText(context, resources.getString(R.string.ui_set_export_failed), Toast.LENGTH_SHORT).show()
            SettingsEvent.Cleared -> Toast.makeText(context, resources.getString(R.string.ui_set_cleared), Toast.LENGTH_SHORT).show()
            SettingsEvent.NeedsAlarmAccess -> askAlarms = true
            SettingsEvent.ConfigSaved -> Toast.makeText(context, resources.getString(R.string.ui_cfg_saved), Toast.LENGTH_SHORT).show()
            SettingsEvent.ConfigSaveFailed -> Toast.makeText(context, resources.getString(R.string.ui_cfg_save_failed), Toast.LENGTH_SHORT).show()
            SettingsEvent.ConfigImported -> Toast.makeText(context, resources.getString(R.string.ui_cfg_imported), Toast.LENGTH_SHORT).show()
            null -> Unit
        }
        if (event != null) viewModel.eventHandled()
    }

    if (showLanguage) LanguagePickerDialog(onDismiss = { showLanguage = false })
    if (askAlarms) AutoRestartAccessSheet(
        onAllow = {
            askAlarms = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        },
        // Declined: the switch goes back off rather than on but not working (as in 1.0).
        onDismiss = { askAlarms = false; viewModel.declineAlarmAccess() },
    )

    SettingsHomeContent(
        state = state,
        language = languageDisplayName(currentAppLanguageTag()),
        importUi = importUi,
        actions = SettingsActions(
            nav = nav,
            onLevel = viewModel::setLevel,
            onAutoRestart = viewModel::setAutoRestart,
            onNotifications = viewModel::setNotifications,
            onNotificationDetails = viewModel::setNotificationDetails,
            onWeekly = viewModel::setWeeklyReport,
            onLanguage = { showLanguage = true },
            onAppearance = viewModel::setAppearance,
            onRetention = viewModel::setRetentionDays,
            // The history is a full browsing record: device lock first, like every export (owner, 2026-11-04).
            onExport = {
                runProtected(resources.getString(R.string.ui_set_export), resources.getString(R.string.ui_set_export_sub)) {
                    viewModel.exportHistory()
                }
            },
            onScreenshots = viewModel::setAllowScreenshots,
            // After the confirmation sheet: the device lock too, as in 1.0.
            onClear = {
                runProtected(resources.getString(R.string.set_clear_confirm_title), resources.getString(R.string.set_clear_confirm_body)) {
                    viewModel.clearAllData()
                }
            },
            onOpenUrl = { openInBrowser(context, it) },
            // Both ask for the device lock first, as other exports do (README "Sensitive actions"):
            // the file holds the user's rules, and an import changes what is blocked.
            onExportConfig = {
                runProtected(resources.getString(R.string.ui_cfg_export), resources.getString(R.string.ui_cfg_export_sub)) {
                    runCatching { saveConfig.launch(CONFIG_FILE_NAME) }
                        .onFailure { Toast.makeText(context, resources.getString(R.string.ui_cfg_save_failed), Toast.LENGTH_SHORT).show() }
                }
            },
            onImportConfig = {
                runProtected(resources.getString(R.string.ui_cfg_import), resources.getString(R.string.ui_cfg_import_sub)) {
                    // Some file managers report a .json file as plain text or as unknown binary.
                    runCatching { openConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                        .onFailure { Toast.makeText(context, resources.getString(R.string.ui_cfg_err_read), Toast.LENGTH_SHORT).show() }
                }
            },
            onConfirmImport = viewModel::confirmImport,
            onCancelImport = viewModel::cancelImport,
        ),
    )
}

private const val CONFIG_FILE_NAME = "corelock-rules-and-settings.json"

/** The links are pages the user opens; CoreLock itself makes no request (README network table). */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.ui_set_no_browser), Toast.LENGTH_SHORT).show()
    }
}

private enum class SettingsSheet { LEVEL, THEME, RETENTION, EXPORT, CONFIG_EXPORT, CLEAR, LIMITS }

@Composable
fun SettingsHomeContent(
    state: SettingsHomeState,
    language: String,
    actions: SettingsActions,
    now: Long = System.currentTimeMillis(),
    importUi: ImportUi? = null,
) {
    val spacing = CoreLockTheme.spacing
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    val close = { sheet = null }

    LazyColumn(Modifier.fillMaxSize().testTag(TAG_SETTINGS_LIST)) {
        item(key = "title") {
            Text(
                stringResource(R.string.ui_settings),
                style = MaterialTheme.typography.titleLarge,
                color = CoreLockTheme.colors.onSurface,
                modifier = Modifier.padding(start = spacing.screen, end = spacing.screen, top = spacing.l).semantics { heading() },
            )
        }

        group("protection", R.string.ui_protection) {
            SettingRow(
                title = stringResource(R.string.ui_protection_level),
                subtitle = "${stringResource(HomeLogic.levelName(state.level))} · ${stringResource(HomeLogic.levelDescription(state.level))}",
                onClick = { sheet = SettingsSheet.LEVEL },
            )
            SettingsDivider()
            SettingRow(stringResource(R.string.ui_set_rules), actions.nav.onOpenRules, subtitle = stringResource(R.string.ui_set_rules_sub))
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_set_blocklists), actions.nav.onOpenBlocklists,
                subtitle = listsSubtitle(state, now), modifier = Modifier.testTag(TAG_LISTS_ROW),
            )
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_set_kill_switch), actions.nav.onOpenKillSwitch,
                subtitle = stringResource(SettingsLogic.killSwitchSubtitle(state.lockdown)),
            )
            SettingsDivider()
            PrimaryToggleRow(
                stringResource(R.string.ui_restart_after_crash), state.autoRestart, actions.onAutoRestart,
                subtitle = stringResource(R.string.ui_restart_after_crash_sub),
            )
        }

        group("notifications", R.string.ui_set_notifications) {
            PrimaryToggleRow(stringResource(R.string.ui_set_notifications), state.notifications, actions.onNotifications, subtitle = stringResource(R.string.ui_set_notifications_sub))
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_set_show_details), state.notificationDetails, actions.onNotificationDetails, subtitle = stringResource(R.string.ui_set_show_details_sub))
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_set_weekly), state.weeklyReport, actions.onWeekly, subtitle = stringResource(R.string.ui_set_weekly_sub))
        }

        group("language", R.string.ui_set_language) {
            SettingRow(stringResource(R.string.ui_set_language), actions.onLanguage, subtitle = language)
        }

        group("appearance", R.string.ui_set_appearance) {
            SettingRow(
                stringResource(R.string.ui_set_theme), { sheet = SettingsSheet.THEME },
                subtitle = stringResource(SettingsLogic.appearanceLabel(state.appearance)), modifier = Modifier.testTag(TAG_THEME_ROW),
            )
        }

        group("data", R.string.ui_set_data) {
            SettingRow(
                stringResource(R.string.ui_set_keep_history), { sheet = SettingsSheet.RETENTION },
                subtitle = pluralStringResource(R.plurals.set_days_short, state.retentionDays, state.retentionDays),
            )
            SettingsDivider()
            SettingRow(stringResource(R.string.ui_set_export), { sheet = SettingsSheet.EXPORT }, subtitle = stringResource(R.string.ui_set_export_sub))
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_cfg_export), { sheet = SettingsSheet.CONFIG_EXPORT },
                subtitle = stringResource(R.string.ui_cfg_export_sub), modifier = Modifier.testTag(TAG_CFG_EXPORT_ROW),
            )
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_cfg_import), actions.onImportConfig,
                subtitle = stringResource(R.string.ui_cfg_import_sub), modifier = Modifier.testTag(TAG_CFG_IMPORT_ROW),
            )
            SettingsDivider()
            PrimaryToggleRow(stringResource(R.string.ui_set_screenshots), state.allowScreenshots, actions.onScreenshots, subtitle = stringResource(R.string.ui_set_screenshots_sub))
            SettingsDivider()
            SettingRow(
                stringResource(R.string.ui_clear_all_data), { sheet = SettingsSheet.CLEAR },
                subtitle = stringResource(R.string.ui_set_clear_sub), danger = true, modifier = Modifier.testTag(TAG_CLEAR_ROW),
            )
        }

        group("about", R.string.ui_set_about) {
            SettingRow(stringResource(R.string.ui_set_version), onClick = null, value = BuildConfig.VERSION_NAME)
            SettingsDivider()
            SettingRow(stringResource(R.string.ui_what_corelock_cant), { sheet = SettingsSheet.LIMITS })
            SettingsDivider()
            val policy = stringResource(R.string.url_blocking_policy)
            SettingRow(stringResource(R.string.ui_set_policy), { actions.onOpenUrl(policy) }, subtitle = stringResource(R.string.ui_set_policy_sub))
            SettingsDivider()
            SettingRow(stringResource(R.string.ui_set_licenses), actions.nav.onOpenLicenses)
            SettingsDivider()
            SettingRow(stringResource(R.string.ui_set_new_versions), { actions.onOpenUrl(BuildConfig.RELEASES_URL) }, subtitle = stringResource(R.string.ui_set_new_versions_sub))
            SettingsDivider()
            val report = stringResource(R.string.url_report_problem)
            SettingRow(stringResource(R.string.ui_set_report), { actions.onOpenUrl(report) }, subtitle = stringResource(R.string.ui_set_report_sub))
        }

        item(key = "advanced") {
            SettingsCard(Modifier.padding(top = spacing.xl)) {
                SettingRow(
                    stringResource(R.string.ui_advanced), actions.nav.onOpenAdvanced,
                    subtitle = stringResource(R.string.ui_advanced_sub), modifier = Modifier.testTag(TAG_ADVANCED_ROW),
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.padding(bottom = spacing.xxxl)) }
    }

    when (sheet) {
        SettingsSheet.LEVEL -> CoreLockBottomSheet(stringResource(R.string.ui_protection_level), close) {
            LevelOptions(current = state.level, onSelect = { actions.onLevel(it); close() })
        }
        SettingsSheet.THEME -> CoreLockBottomSheet(stringResource(R.string.ui_set_theme), close) {
            Choices(Appearance.entries, state.appearance, { stringResource(SettingsLogic.appearanceLabel(it)) }) { actions.onAppearance(it); close() }
        }
        SettingsSheet.RETENTION -> CoreLockBottomSheet(stringResource(R.string.ui_set_keep_history), close) {
            Text(stringResource(R.string.ui_set_keep_history_sub), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted)
            Choices(SettingsLogic.RETENTION_DAYS, state.retentionDays, { pluralStringResource(R.plurals.set_days_short, it, it) }) { actions.onRetention(it); close() }
        }
        SettingsSheet.EXPORT -> CoreLockBottomSheet(stringResource(R.string.ui_set_export_title), close) {
            Text(stringResource(R.string.ui_set_export_body), style = MaterialTheme.typography.bodyLarge)
            PrimaryButton(stringResource(R.string.ui_set_export_action), { close(); actions.onExport() }, Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.cancel), close, Modifier.fillMaxWidth())
        }
        SettingsSheet.CLEAR -> CoreLockBottomSheet(stringResource(R.string.set_clear_confirm_title), close) {
            Text(stringResource(R.string.set_clear_confirm_body), style = MaterialTheme.typography.bodyLarge)
            DestructiveButton(stringResource(R.string.ui_clear_all_data), { close(); actions.onClear() }, Modifier.fillMaxWidth().testTag(TAG_CLEAR_CONFIRM))
            SecondaryButton(stringResource(R.string.cancel), close, Modifier.fillMaxWidth())
        }
        SettingsSheet.CONFIG_EXPORT -> CoreLockBottomSheet(stringResource(R.string.ui_cfg_export), close) {
            // ADR 0013: the file is private, said before it is written.
            Text(stringResource(R.string.ui_cfg_export_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TAG_CFG_PRIVATE))
            PrimaryButton(stringResource(R.string.ui_cfg_export_action), { close(); actions.onExportConfig() }, Modifier.fillMaxWidth().testTag(TAG_CFG_SAVE))
            SecondaryButton(stringResource(R.string.cancel), close, Modifier.fillMaxWidth())
        }
        SettingsSheet.LIMITS -> InfoSheet(stringResource(R.string.ui_what_corelock_cant), stringResource(R.string.ui_what_corelock_cant_body), close)
        null -> Unit
    }

    when (importUi) {
        is ImportUi.Confirm -> ImportConfirmSheet(importUi, actions.onConfirmImport, actions.onCancelImport)
        is ImportUi.Problem -> InfoSheet(stringResource(R.string.ui_cfg_problem_title), stringResource(importUi.message), actions.onCancelImport)
        null -> Unit
    }
}

/** Nothing changes before Import: what is added and changed, and what was left out, in words. */
@Composable
private fun ImportConfirmSheet(confirm: ImportUi.Confirm, onImport: () -> Unit, onCancel: () -> Unit) {
    val plan = confirm.plan
    val muted = CoreLockTheme.colors.onSurfaceMuted
    CoreLockBottomSheet(stringResource(R.string.ui_cfg_import_title), onCancel) {
        Column(Modifier.testTag(TAG_CFG_CONFIRM), verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.s)) {
            if (plan.newRules.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.ui_cfg_new_rules, plan.newRules.size, plan.newRules.size), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.ui_cfg_rules_split, plan.blocks, plan.allows), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (confirm.settingsChanged > 0) {
                Text(pluralStringResource(R.plurals.ui_cfg_settings_changed, confirm.settingsChanged, confirm.settingsChanged), style = MaterialTheme.typography.bodyLarge)
            }
            if (plan.alreadyThere > 0) {
                Text(pluralStringResource(R.plurals.ui_cfg_already, plan.alreadyThere, plan.alreadyThere), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (confirm.skipped > 0) {
                Text(pluralStringResource(R.plurals.ui_cfg_skipped, confirm.skipped, confirm.skipped), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            Text(stringResource(R.string.ui_cfg_import_note), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        PrimaryButton(stringResource(R.string.ui_cfg_import_action), onImport, Modifier.fillMaxWidth().testTag(TAG_CFG_IMPORT))
        SecondaryButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth())
    }
}

@Composable
private fun listsSubtitle(state: SettingsHomeState, now: Long): String = when {
    state.listsUpdating -> stringResource(R.string.ui_lists_refreshing)
    else -> when (val lists = state.lists) {
        null -> " "
        SettingsLogic.ListsUpdated.Never -> stringResource(R.string.ui_set_lists_never)
        is SettingsLogic.ListsUpdated.At -> {
            val ago = relativeTime(lists.at, now)
            stringResource(if (lists.someMissing) R.string.ui_set_lists_some_missing else R.string.ui_set_lists_updated, ago)
        }
    }
}

/** "2 days ago", "yesterday": Android's words in the app's language, lower case mid-sentence. */
@Composable
internal fun relativeTime(at: Long, now: Long): String {
    val locale = LocalResources.current.configuration.locales[0] ?: Locale.getDefault()
    return DateUtils.getRelativeTimeSpanString(at.coerceAtMost(now), now, DateUtils.MINUTE_IN_MILLIS)
        .toString().replaceFirstChar { it.lowercase(locale) }
}

/** A heading and a card of rows; one lazy item per group. */
internal fun androidx.compose.foundation.lazy.LazyListScope.group(key: String, title: Int, rows: @Composable ColumnScope.() -> Unit) {
    item(key = key) {
        // SectionHeader pads by spacing.l, which is the screen margin: it lines up with the card.
        SectionHeader(stringResource(title))
        SettingsCard(content = rows)
    }
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = CoreLockTheme.spacing.screen)
            .fillMaxWidth()
            .clip(CoreLockTheme.shapes.card)
            .background(CoreLockTheme.colors.surface),
        content = content,
    )
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(color = CoreLockTheme.colors.surfaceVariant)

/** Radio rows; the whole row is the target and is read as a radio button. */
@Composable
internal fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val colors = CoreLockTheme.colors
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CoreLockTheme.spacing.minTouchTarget)
                    .clip(CoreLockTheme.shapes.small)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = CoreLockTheme.spacing.s),
            ) {
                RadioButton(option == selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline))
                Spacer(Modifier.width(CoreLockTheme.spacing.m))
                Text(label(option), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Asked only when "Restart after a crash" is turned on without exact-alarm access (1.0 texts). */
@Composable
private fun AutoRestartAccessSheet(onAllow: () -> Unit, onDismiss: () -> Unit) {
    CoreLockBottomSheet(stringResource(R.string.set_auto_restart_dialog_title), onDismiss) {
        Text(stringResource(R.string.set_auto_restart_dialog_body), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(stringResource(R.string.set_auto_restart_dialog_allow), onAllow, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.set_auto_restart_dialog_cancel), onDismiss, Modifier.fillMaxWidth())
    }
}

internal const val TAG_SETTINGS_LIST = "settings_list"
internal const val TAG_LISTS_ROW = "settings_lists"
internal const val TAG_THEME_ROW = "settings_theme"
internal const val TAG_CLEAR_ROW = "settings_clear"
internal const val TAG_CFG_EXPORT_ROW = "settings_cfg_export"
internal const val TAG_CFG_IMPORT_ROW = "settings_cfg_import"
internal const val TAG_CFG_PRIVATE = "settings_cfg_private"
internal const val TAG_CFG_SAVE = "settings_cfg_save"
internal const val TAG_CFG_CONFIRM = "settings_cfg_confirm"
internal const val TAG_CFG_IMPORT = "settings_cfg_do_import"
internal const val TAG_CLEAR_CONFIRM = "settings_clear_confirm"
internal const val TAG_ADVANCED_ROW = "settings_advanced"
