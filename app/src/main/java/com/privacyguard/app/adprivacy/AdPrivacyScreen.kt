package com.privacyguard.app.adprivacy

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim
import java.util.Calendar

@Composable
fun AdPrivacyScreen(viewModel: AdPrivacyViewModel = viewModel()) {
    val context = LocalContext.current
    val groups by viewModel.sensorGroups.collectAsStateWithLifecycle()
    val usageAccess by viewModel.usageAccess.collectAsStateWithLifecycle()
    val locationApps by viewModel.locationApps.collectAsStateWithLifecycle()
    val micApps by viewModel.micApps.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val done by viewModel.checklistDone.collectAsStateWithLifecycle()
    val sdkBlocking by viewModel.adSdkBlocking.collectAsStateWithLifecycle()
    var showSystem by rememberSaveable { mutableStateOf(false) }

    // Grants change in system settings; re-read them when the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val open: (List<android.content.Intent>) -> Unit = { intents ->
        if (!AdPrivacyLinks.open(context, intents)) {
            Toast.makeText(context, R.string.adp_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(title = stringResource(R.string.adp_title)) {
                Text(stringResource(R.string.adp_honest_title), style = MaterialTheme.typography.titleSmall, color = PgText)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.adp_honest_body), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
        }

        item {
            Panel(stringResource(R.string.adp_explainer_title)) {
                listOf(
                    R.string.adp_explainer_location, R.string.adp_explainer_wifi,
                    R.string.adp_explainer_pixels, R.string.adp_explainer_searches,
                ).forEach { Bullet(stringResource(it)) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.adp_explainer_mic), style = MaterialTheme.typography.bodySmall, color = PgText)
            }
        }

        // ── Mic & camera timeline ───────────────────────────────────────────
        item {
            Panel(stringResource(R.string.adp_sensors_title)) {
                Body(stringResource(R.string.adp_sensors_body))
                if (!usageAccess) {
                    Spacer(Modifier.height(8.dp))
                    Banner(stringResource(R.string.adp_usage_access_needed), warn = true)
                    TextButton(onClick = { open(AdPrivacyLinks.usageAccess()) }) {
                        Text(stringResource(R.string.adp_usage_access_button))
                    }
                }
                Spacer(Modifier.height(10.dp))
                MicSearch(search, onSearch = viewModel::searchMic)
                Spacer(Modifier.height(12.dp))
                if (groups.isEmpty()) Banner(stringResource(R.string.adp_sensors_empty), warn = false)
            }
        }
        items(groups, key = { it.packageName ?: "unknown" }) { group -> SensorGroupCard(group) }

        // ── Location audit ─────────────────────────────────────────────────
        item {
            val shown = locationApps.filter { showSystem || !it.isSystem }
            Panel(stringResource(R.string.adp_location_title)) {
                Body(stringResource(R.string.adp_location_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
                    Text(stringResource(R.string.adp_show_system_apps), style = MaterialTheme.typography.bodySmall, color = PgText)
                }
                if (shown.isEmpty()) Banner(stringResource(R.string.adp_location_empty), warn = false)
                shown.forEach { app ->
                    AppRow(app, onClick = { open(AdPrivacyLinks.appDetails(app.packageName)) }) {
                        val level = when (app.locationLevel) {
                            LocationLevel.ALWAYS -> stringResource(R.string.adp_location_always)
                            LocationLevel.WHILE_IN_USE -> stringResource(R.string.adp_location_while_in_use)
                            else -> stringResource(R.string.adp_location_legacy)
                        }
                        val always = app.locationLevel != LocationLevel.WHILE_IN_USE
                        StatusPill(level, if (always) PgWarningDim else PgInfoDim, if (always) PgWarning else PgInfo)
                        Text(
                            stringResource(if (app.precise) R.string.adp_location_precise else R.string.adp_location_approximate),
                            style = MaterialTheme.typography.labelSmall, color = PgTextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Body(stringResource(R.string.adp_location_last_use))
                TextButton(onClick = { open(AdPrivacyLinks.permissionHistory("android.permission-group.LOCATION")) }) {
                    Text(stringResource(R.string.adp_open_privacy_dashboard))
                }
            }
        }

        // ── Checklist ──────────────────────────────────────────────────────
        item {
            val steps = ChecklistStep.entries
            Panel(stringResource(R.string.adp_checklist_title)) {
                Body(stringResource(R.string.adp_checklist_body))
                val count = steps.count { it.id in done }
                Text(
                    pluralStringResource(R.plurals.adp_checklist_progress, count, count, steps.size),
                    style = MaterialTheme.typography.labelMedium, color = PgAccent,
                )
                steps.forEach { step ->
                    ChecklistRow(
                        step = step,
                        done = step.id in done,
                        micCount = micApps.size,
                        onToggle = { viewModel.setStepDone(step, it) },
                        onOpen = { open(AdPrivacyLinks.intentsFor(step)) },
                    )
                    if (step == ChecklistStep.REVIEW_MIC) {
                        micApps.filter { showSystem || !it.isSystem }.forEach { app ->
                            AppRow(app, onClick = { open(AdPrivacyLinks.appDetails(app.packageName)) }) {}
                        }
                    }
                }
            }
        }

        // ── Ad SDK blocking ────────────────────────────────────────────────
        item {
            Panel(stringResource(R.string.adp_sdk_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.adp_sdk_toggle), style = MaterialTheme.typography.titleSmall,
                        color = PgText, modifier = Modifier.weight(1f))
                    Switch(checked = sdkBlocking, onCheckedChange = viewModel::setAdSdkBlocking)
                }
                Body(stringResource(R.string.adp_sdk_body))
                Spacer(Modifier.height(6.dp))
                Text(
                    AdSdkCatalog.sdks.map { it.company }.distinct().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = PgText,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.adp_sdk_exceptions_title), style = MaterialTheme.typography.titleSmall, color = PgText)
                AdSdkCatalog.neverBlock.forEach { rule ->
                    val reason = when (rule.reason) {
                        NeverBlockReason.LOGIN -> R.string.adp_reason_login
                        NeverBlockReason.PUSH -> R.string.adp_reason_push
                        NeverBlockReason.DEEP_LINKS -> R.string.adp_reason_deep_links
                    }
                    Bullet("${rule.domain}: ${stringResource(reason)}")
                }
                Spacer(Modifier.height(6.dp))
                Banner(stringResource(R.string.adp_sdk_meta_limit), warn = true)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MicSearch(result: MicSearchResult?, onSearch: (Long, Long) -> Unit) {
    val context = LocalContext.current
    var yesterday by rememberSaveable { mutableStateOf(false) }
    // Minutes since midnight; defaults to the last two hours of the chosen day.
    val nowMinutes = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
    var fromMin by rememberSaveable { mutableLongStateOf((nowMinutes - 120L).coerceAtLeast(0)) }
    var toMin by rememberSaveable { mutableLongStateOf(nowMinutes.toLong()) }

    fun millis(minutes: Long): Long = Calendar.getInstance().apply {
        if (yesterday) add(Calendar.DAY_OF_YEAR, -1)
        set(Calendar.HOUR_OF_DAY, (minutes / 60).toInt()); set(Calendar.MINUTE, (minutes % 60).toInt())
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    Column(
        Modifier.fillMaxWidth().background(PgPanelStrong, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(stringResource(R.string.adp_search_title), style = MaterialTheme.typography.titleSmall, color = PgText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DayChip(stringResource(R.string.adp_search_today), !yesterday) { yesterday = false }
            DayChip(stringResource(R.string.adp_search_yesterday), yesterday) { yesterday = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { pickTime(context, fromMin) { fromMin = it } }) {
                Text(stringResource(R.string.adp_search_from, formatTime(context, millis(fromMin))))
            }
            TextButton(onClick = { pickTime(context, toMin) { toMin = it } }) {
                Text(stringResource(R.string.adp_search_to, formatTime(context, millis(toMin))))
            }
        }
        // "To 17:57" means up to the end of that minute: a use at 17:57:30 counts.
        OutlinedButton(onClick = { onSearch(millis(fromMin), millis(toMin) + 59_999) }) {
            Text(stringResource(R.string.adp_search_button))
        }
        if (result != null) {
            Spacer(Modifier.height(8.dp))
            val from = formatDateTime(context, result.from)
            val to = formatDateTime(context, result.to)
            if (result.uses.isEmpty()) {
                Banner(stringResource(R.string.adp_search_none, from, to), warn = false)
            } else {
                Text(
                    pluralStringResource(R.plurals.adp_search_found, result.uses.size, result.uses.size, from, to),
                    style = MaterialTheme.typography.bodySmall, color = PgWarning,
                )
                result.uses.forEach { SensorUseLine(it, showApp = true) }
            }
        }
    }
}

@Composable
private fun SensorGroupCard(group: SensorAppGroup) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (group.packageName != null) AppIconImage(group.packageName, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                group.appLabel ?: stringResource(R.string.adp_unknown_app),
                style = MaterialTheme.typography.titleSmall, color = PgText, modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(R.plurals.adp_uses_count, group.uses.size, group.uses.size),
                style = MaterialTheme.typography.labelSmall, color = PgTextMuted,
            )
        }
        Spacer(Modifier.height(6.dp))
        group.uses.forEach { SensorUseLine(it, showApp = false) }
    }
}

@Composable
private fun SensorUseLine(row: SensorUseRow, showApp: Boolean) {
    val context = LocalContext.current
    val e = row.event
    val sensor = stringResource(if (e.sensor == SensorEventEntity.SENSOR_MIC) R.string.adp_sensor_mic else R.string.adp_sensor_camera)
    // Watching stops with protection and closes open rows, and closes rows a dead
    // process left open when it starts; an open row while protection is off
    // therefore lost its end too, it is not still running.
    val end = when {
        e.endedAt == SensorEventEntity.END_NOT_RECORDED -> stringResource(R.string.adp_end_unknown)
        e.endedAt != null -> formatTime(context, e.endedAt)
        com.privacyguard.platform.android.PrivacyVpnService.isRunning -> stringResource(R.string.adp_ongoing)
        else -> stringResource(R.string.adp_end_unknown)
    }
    val parts = buildList {
        if (showApp) add(row.appLabel ?: stringResource(if (e.screenOnAtStart) R.string.adp_unknown_app else R.string.adp_unknown_app_screen_off))
        add(sensor)
        add(stringResource(R.string.adp_time_range, formatDateTime(context, e.startedAt), end))
        add(stringResource(if (e.screenOnAtStart) R.string.adp_screen_on else R.string.adp_screen_off))
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = if (e.screenOnAtStart) PgText else PgWarning,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun ChecklistRow(
    step: ChecklistStep,
    done: Boolean,
    micCount: Int,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val (title, desc) = when (step) {
        ChecklistStep.DELETE_AD_ID -> stringResource(R.string.adp_step_ad_id) to stringResource(R.string.adp_step_ad_id_desc)
        ChecklistStep.META_OFF_ACTIVITY -> stringResource(R.string.adp_step_meta) to stringResource(R.string.adp_step_meta_desc)
        ChecklistStep.GOOGLE_AD_CENTER -> stringResource(R.string.adp_step_ad_center) to stringResource(R.string.adp_step_ad_center_desc)
        ChecklistStep.GOOGLE_TIMELINE -> stringResource(R.string.adp_step_timeline) to stringResource(R.string.adp_step_timeline_desc)
        ChecklistStep.REVIEW_MIC -> stringResource(R.string.adp_step_mic) to
            pluralStringResource(R.plurals.adp_step_mic_desc, micCount, micCount)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (done) PgTextMuted else PgText)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        }
        if (step != ChecklistStep.REVIEW_MIC) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.adp_open)) }
        }
    }
}

@Composable
private fun AppRow(app: PermissionApp, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(app.packageName, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, color = PgText)
            if (app.isSystem) Text(stringResource(R.string.adp_system), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        }
        Column(horizontalAlignment = Alignment.End) { trailing() }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionLabel(title)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)

@Composable
private fun Bullet(text: String) = Text("• $text", style = MaterialTheme.typography.bodySmall, color = PgText,
    modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Banner(text: String, warn: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) PgWarning else PgAccent,
        modifier = Modifier.fillMaxWidth()
            .background(if (warn) PgWarningDim else PgAccentDim, RoundedCornerShape(10.dp))
            .padding(10.dp),
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    StatusPill(label, if (selected) PgAccentDim else PgPanelStrong, if (selected) PgAccent else PgTextMuted,
        Modifier.clickable(onClick = onClick))
}

private fun pickTime(context: Context, minutes: Long, onPicked: (Long) -> Unit) {
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(h * 60L + m) },
        (minutes / 60).toInt(), (minutes % 60).toInt(),
        DateFormat.is24HourFormat(context),
    ).show()
}

// Locale- and 12/24h-aware, so translations get native formats for free.
private fun formatTime(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)

private fun formatDateTime(context: Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
