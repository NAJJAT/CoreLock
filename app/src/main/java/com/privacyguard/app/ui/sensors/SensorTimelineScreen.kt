package com.privacyguard.app.ui.sensors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgBackground
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.app.ui.theme.PgWarningDim

/** Every camera and microphone use, newest first, each explained in one sentence. */
@Composable
fun SensorTimelineScreen(
    onOpenUsageAccess: () -> Unit,
    viewModel: SensorTimelineViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshMode() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PgBackground),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("Camera & Mic", style = MaterialTheme.typography.headlineMedium, color = PgText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.total} uses in the last 30 days · ${state.alertCount} alerts",
                    style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                )
            }
        }
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("How this works")
                Spacer(Modifier.height(8.dp))
                Text(
                    "CoreLock detects camera and microphone use through Android's system signals, and alerts you " +
                        "when it happens with the screen off, at night, or by an app you did not open.",
                    style = MaterialTheme.typography.bodySmall, color = PgText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "It cannot see spyware that works around Android itself (rooted or kernel-level tools).",
                    style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                )
                Spacer(Modifier.height(10.dp))
                val (text, bg, fg) = when (state.mode) {
                    AttributionMode.ADVANCED -> Triple("App names: exact (Advanced mode)", PgAccentDim, PgAccent)
                    AttributionMode.USAGE_ACCESS -> Triple("App names: likely (Usage Access)", PgInfoDim, PgInfo)
                    AttributionMode.OFF -> Triple("App names: off", PgWarningDim, PgWarning)
                }
                StatusPill(text, bg, fg)
                if (state.mode != AttributionMode.ADVANCED) {
                    TextButton(onClick = onOpenUsageAccess) {
                        Text(
                            if (state.mode == AttributionMode.OFF) "Turn on Usage Access to see which app" else "About app identification",
                            color = PgAccent,
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimelineFilter.values().forEach { f ->
                    Box(
                        Modifier
                            .background(if (state.filter == f) PgAccentDim else PgBackgroundAlt, RoundedCornerShape(20.dp))
                            .clickable { viewModel.setFilter(f) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(f.label, style = MaterialTheme.typography.labelMedium, color = if (state.filter == f) PgAccent else PgTextMuted)
                    }
                }
            }
        }
        if (state.rows.isEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        if (state.total == 0) "No camera or microphone use recorded yet." else "Nothing matches this filter.",
                        style = MaterialTheme.typography.titleSmall, color = PgText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Uses are recorded while protection is on and kept for 30 days on this phone only.",
                        style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                    )
                }
            }
        }
        items(state.rows, key = { "sensor:${it.id}" }) { row ->
            TimelineCard(row, onItWasMe = { viewModel.markExpected(row.id) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TimelineCard(row: TimelineRow, onItWasMe: () -> Unit) {
    val (tintBg, tint) = when (row.severity) {
        "CRITICAL" -> PgDangerDim to PgDanger
        "HIGH" -> PgWarningDim to PgWarning
        "LOW" -> PgInfoDim to PgInfo
        else -> PgAccentDim to PgAccent
    }
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(tintBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (row.isCamera) Icons.Default.PhotoCamera else Icons.Default.Mic,
                    contentDescription = if (row.isCamera) "Camera" else "Microphone",
                    tint = tint, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.who, style = MaterialTheme.typography.titleMedium, color = PgText, fontWeight = FontWeight.SemiBold)
                Text(row.whoDetail, style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
            }
            StatusPill(
                when (row.severity) {
                    "CRITICAL" -> "Alert"
                    "HIGH" -> "Warning"
                    "LOW" -> "Note"
                    else -> if (row.inProgress) "Now" else "Normal"
                },
                tintBg, tint,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(row.reason, style = MaterialTheme.typography.bodyMedium, color = PgText)
        Spacer(Modifier.height(6.dp))
        Text("${row.whenLabel} · ${row.durationLabel}", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        if (row.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.tags.forEach { StatusPill(it, PgBackgroundAlt, PgTextMuted) }
            }
        }
        if (row.severity != "NONE" && !row.markedExpected) {
            TextButton(onClick = onItWasMe) { Text("It was me", color = PgAccent) }
        }
    }
}
