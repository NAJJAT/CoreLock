package com.privacyguard.app.ui.statistics

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.LabeledProgress
import com.privacyguard.app.ui.components.NetworkMapCanvas
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.SunburstChart
import com.privacyguard.app.ui.components.SunburstRing
import com.privacyguard.app.ui.components.SunburstSlice
import com.privacyguard.app.ui.components.TemporalHeatmap
import com.privacyguard.app.ui.security.rememberProtectedActionRunner
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val runProtectedAction = rememberProtectedActionRunner()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val topBlockedDomains by viewModel.topBlockedDomains.collectAsStateWithLifecycle()
    val highSeverityAnomalies by viewModel.highSeverityAnomalies.collectAsStateWithLifecycle()
    val topCountries by viewModel.topCountries.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val rememberedNetworks by viewModel.rememberedNetworks.collectAsStateWithLifecycle()
    val lastItReportPaths by viewModel.lastItReportPaths.collectAsStateWithLifecycle()
    val csvExportPath by viewModel.csvExportPath.collectAsStateWithLifecycle()
    val heatmap by viewModel.heatmap.collectAsStateWithLifecycle()
    val sunburstOrgs by viewModel.sunburstOrgs.collectAsStateWithLifecycle()
    val topAppsByData by viewModel.topAppsByData.collectAsStateWithLifecycle()
    val weeklyTrend by viewModel.weeklyTrend.collectAsStateWithLifecycle()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = stringResource(R.string.stats_title),
                subtitle = stringResource(R.string.stats_subtitle),
                badge = stringResource(R.string.stats_badge_today).uppercase()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.stats_total_connections), fmtCount(stats.totalConnections), PgText, Modifier.weight(1f))
                    StatTile(stringResource(R.string.dash_blocked), fmtCount(stats.blockedToday), PgDanger, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.stats_data_transferred), stats.dataTransferredToday, PgInfo, Modifier.weight(1f))
                    StatTile(stringResource(R.string.dash_privacy_score), fmtCount(stats.privacyScore), PgWarning, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(16.dp))
                LabeledProgress(stringResource(R.string.stats_block_rate), fmtPercent(stats.blockRate), stats.blockRate, PgAccent)
                Spacer(modifier = Modifier.height(14.dp))
                LabeledProgress(stringResource(R.string.dash_encryption_health), stringResource(R.string.dash_tls_percent, (stats.encryptionHealth * 100).toInt()), stats.encryptionHealth, PgAccent)
                Spacer(modifier = Modifier.height(14.dp))
                ScoreBreakdown(stats)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, resources.getString(R.string.stats_share_score_text, stats.privacyScore))
                        }
                        context.startActivity(Intent.createChooser(share, resources.getString(R.string.stats_share_score)))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stats_share_score))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        runProtectedAction(
                            resources.getString(R.string.stats_gen_it),
                            resources.getString(R.string.stats_gen_it_confirm),
                        ) {
                            viewModel.exportItReport()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PgInfoDim, contentColor = PgInfo),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stats_share_it))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.exportConnectionsCsv() },
                    colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (csvExportPath != null) stringResource(R.string.stats_csv_ready) else stringResource(R.string.stats_csv_export))
                }
                csvExportPath?.let { path ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", java.io.File(path))
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, resources.getString(R.string.stats_share_log)))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.stats_share_csv))
                    }
                }
            }
        }

        // Real-time Network Map
        if (topCountries.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_network_map))
                    Spacer(modifier = Modifier.height(10.dp))
                    NetworkMapCanvas(
                        countries = topCountries,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.stats_map_legend),
                        style = MaterialTheme.typography.labelSmall,
                        color = PgTextMuted,
                    )
                }
            }
        }

        // Traffic Sunburst
        if (sunburstOrgs.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_by_org))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val palette = listOf(
                            Color(0xFF00E5A0), Color(0xFF4D9CFF), Color(0xFFFFB23F),
                            Color(0xFFFF4D6A), Color(0xFF9C6DFF), Color(0xFF00C8E0),
                            Color(0xFFFF8C42), Color(0xFF6BFF6B), Color(0xFFFF6B9D),
                            Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFFEF476F),
                        )
                        SunburstChart(
                            rings = listOf(
                                SunburstRing(sunburstOrgs.take(12).mapIndexed { i, s ->
                                    SunburstSlice(s.org, s.count.toFloat(), palette[i % palette.size])
                                })
                            ),
                            modifier = Modifier.size(160.dp),
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            sunburstOrgs.take(6).forEachIndexed { i, s ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).background(
                                            palette[i % palette.size], RoundedCornerShape(2.dp)))
                                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                        Text(
                                            s.org.take(18),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PgText,
                                        )
                                    }
                                    Text(
                                        s.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PgTextMuted,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }

        // Temporal Heatmap
        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel(stringResource(R.string.stats_heatmap))
                Spacer(modifier = Modifier.height(10.dp))
                TemporalHeatmap(
                    grid = heatmap,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.stats_heatmap_legend),
                    style = MaterialTheme.typography.labelSmall,
                    color = PgTextMuted,
                )
            }
        }

        lastItReportPaths?.let { paths ->
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.stats_it_ready), style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stringResource(R.string.stats_it_ready_desc), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            runProtectedAction(
                                resources.getString(R.string.stats_share_it_report),
                                resources.getString(R.string.stats_share_it_confirm),
                            ) {
                                val jsonUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(paths.first))
                                val pdfUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(paths.second))
                                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "application/octet-stream"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(jsonUri, pdfUri))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, resources.getString(R.string.stats_share_it)))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.stats_share_json_pdf))
                    }
                }
            }
        }

        if (timeline.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_threat_timeline))
                    Spacer(modifier = Modifier.height(10.dp))
                    timeline.forEachIndexed { index, item ->
                        val tint = when (item.severity) {
                            "CRITICAL", "HIGH", "BLOCKED" -> PgDanger
                            "WARN", "MED" -> PgWarning
                            else -> PgInfo
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, color = PgText)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(item.severity, style = MaterialTheme.typography.labelSmall, color = tint)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(formatAgo(item.timestamp), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                            }
                        }
                        if (index != timeline.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PgDangerDim, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(pluralStringResource(R.plurals.stats_dns_anomalies, highSeverityAnomalies, highSeverityAnomalies), style = MaterialTheme.typography.titleMedium, color = PgDanger)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.stats_dns_anomalies_desc), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                }
            }
        }

        itemsIndexed(topBlockedDomains, key = { _, it -> "blocked:${it.domain}" }) { index, item ->
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        Text(fmtCount(index + 1), style = MaterialTheme.typography.labelMedium, color = PgTextMuted)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Column {
                            Text(item.domain, style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(pluralStringResource(R.plurals.stats_blocked_requests, item.count, item.count), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                        }
                    }
                    Text(fmtCount(item.count), style = MaterialTheme.typography.titleLarge, color = PgDanger)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(PgPanelStrong, RoundedCornerShape(999.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(
                                if (topBlockedDomains.isEmpty()) 0f
                                else item.count.toFloat() / topBlockedDomains.first().count.coerceAtLeast(1).toFloat()
                            )
                            .height(6.dp)
                            .background(PgDanger, RoundedCornerShape(999.dp))
                    )
                }
            }
        }
        if (topCountries.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.stats_top_countries),
                        style = MaterialTheme.typography.titleMedium,
                        color = PgText,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    topCountries.forEachIndexed { index, item ->
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row {
                                Text(countryFlag(item.countryCode), style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                Column {
                                    Text(item.countryName, style = MaterialTheme.typography.titleSmall, color = PgText)
                                    Text(item.org, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                                }
                            }
                            Text(
                                fmtCount(item.connectionCount),
                                style = MaterialTheme.typography.titleMedium,
                                color = PgInfo,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(PgPanelStrong, RoundedCornerShape(999.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        item.connectionCount.toFloat() /
                                            topCountries.first().connectionCount.coerceAtLeast(1).toFloat()
                                    )
                                    .height(4.dp)
                                    .background(PgInfo, RoundedCornerShape(999.dp))
                            )
                        }
                    }
                }
            }
        }

        if (rememberedNetworks.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_networks))
                    Spacer(modifier = Modifier.height(10.dp))
                    rememberedNetworks.forEachIndexed { index, network ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(network.networkLabel, style = MaterialTheme.typography.titleSmall, color = PgText)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.stats_network_counts, pluralStringResource(R.plurals.stats_cleartext_n, network.cleartextCount, network.cleartextCount), pluralStringResource(R.plurals.stats_dns_anomaly_n, network.dnsAnomalyCount, network.dnsAnomalyCount), pluralStringResource(R.plurals.stats_weak_tls_n, network.weakTlsCount, network.weakTlsCount)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PgTextMuted,
                                )
                            }
                            Text(fmtCount(network.trustScore), style = MaterialTheme.typography.titleMedium, color = if (network.trustLevel == "Reduced") PgDanger else if (network.trustLevel == "Watch") PgWarning else PgInfo)
                        }
                        if (index != rememberedNetworks.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        if (weeklyTrend.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_trend))
                    Spacer(modifier = Modifier.height(12.dp))
                    val peak = weeklyTrend.maxOf { it.total }.coerceAtLeast(1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        weeklyTrend.forEach { day ->
                            val frac = day.total.toFloat() / peak.toFloat()
                            val blockedFrac = if (day.total > 0) day.blocked.toFloat() / day.total.toFloat() else 0f
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((60f * frac.coerceAtLeast(if (day.total > 0) 0.06f else 0f)).dp),
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize()
                                            .background(PgAccentDim, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    )
                                    if (blockedFrac > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(blockedFrac)
                                                .align(Alignment.BottomStart)
                                                .background(PgDanger.copy(alpha = 0.6f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(day.label, style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                                Text(fmtCount(day.total), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(PgAccentDim, RoundedCornerShape(2.dp)))
                            Text(stringResource(R.string.stats_total), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(PgDanger.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                            Text(stringResource(R.string.dash_blocked), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
                        }
                    }
                }
            }
        }

        if (topAppsByData.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel(stringResource(R.string.stats_top_apps))
                    Spacer(modifier = Modifier.height(12.dp))
                    val maxBytes = topAppsByData.firstOrNull()?.totalBytes?.coerceAtLeast(1L) ?: 1L
                    topAppsByData.forEachIndexed { index, app ->
                        AppDataRow(app, app.totalBytes.toFloat() / maxBytes.toFloat())
                        if (index != topAppsByData.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun AppDataRow(app: AppDataStat, fraction: Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.privacyguard.app.ui.components.AppIconImage(
            packageName = app.packageName,
            size = 32.dp,
            cornerRadius = 8.dp,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    color = PgText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatBytes(app.totalBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = PgAccent,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(PgBackgroundAlt, RoundedCornerShape(999.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                        .height(3.dp)
                        .background(PgAccent, RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

@Composable
private fun ScoreBreakdown(stats: StatisticsData) {
    data class Factor(val label: String, val value: String, val fraction: Float, val color: androidx.compose.ui.graphics.Color, val good: Boolean)

    val total = stats.totalConnections.coerceAtLeast(1)
    val factors = listOf(
        Factor(stringResource(R.string.stats_f_encryption), stringResource(R.string.stats_f_encrypted, fmtPercent(stats.encryptionHealth)),
            stats.encryptionHealth, PgAccent, true),
        Factor(stringResource(R.string.stats_block_rate), stringResource(R.string.stats_f_blocked, fmtPercent(stats.blockRate)),
            stats.blockRate, PgInfo, true),
        Factor(stringResource(R.string.stats_f_cleartext), pluralStringResource(R.plurals.dash_connections_count, stats.cleartextToday, stats.cleartextToday),
            (stats.cleartextToday.toFloat() / total).coerceIn(0f, 1f), PgDanger, false),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stats_score_factors), style = MaterialTheme.typography.labelSmall, color = PgTextMuted)
        factors.forEach { f ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    f.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = PgTextMuted,
                    modifier = Modifier.width(90.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(PgBackgroundAlt, RoundedCornerShape(999.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (f.good) f.fraction else f.fraction.coerceAtLeast(0.02f))
                            .height(6.dp)
                            .background(f.color, RoundedCornerShape(999.dp))
                    )
                }
                Text(
                    f.value,
                    style = MaterialTheme.typography.labelSmall,
                    color = f.color,
                    modifier = Modifier.width(80.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val offset = 0x1F1E6 - 'A'.code
    return countryCode.uppercase().map { Character.toChars(it.code + offset).concatToString() }.joinToString("")
}

// Locale digits and grouping (Arabic-Indic digits in Arabic, 1.234 in Dutch).
private fun fmtCount(n: Number): String = java.text.NumberFormat.getIntegerInstance().format(n)

private fun fmtPercent(fraction: Float): String = java.text.NumberFormat.getPercentInstance().format(fraction.toDouble())
