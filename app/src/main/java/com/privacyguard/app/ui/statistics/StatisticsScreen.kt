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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.ui.components.LabeledProgress
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.formatAgo
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
import com.privacyguard.app.ui.theme.PgDanger
import com.privacyguard.app.ui.theme.PgDangerDim
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgInfoDim
import com.privacyguard.app.ui.theme.PgPanelStrong
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel = viewModel()
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val topBlockedDomains by viewModel.topBlockedDomains.collectAsState()
    val highSeverityAnomalies by viewModel.highSeverityAnomalies.collectAsState()
    val topCountries by viewModel.topCountries.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val rememberedNetworks by viewModel.rememberedNetworks.collectAsState()
    val lastItReportPaths by viewModel.lastItReportPaths.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenScaffold(
                title = "Statistics",
                subtitle = "Traffic analytics and anomaly trends",
                badge = "TODAY"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Total connections", stats.totalConnections.toString(), PgText, Modifier.weight(1f))
                    StatTile("Blocked", stats.blockedToday.toString(), PgDanger, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Data transferred", stats.dataTransferredToday, PgInfo, Modifier.weight(1f))
                    StatTile("Privacy score", stats.privacyScore.toString(), PgWarning, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(16.dp))
                LabeledProgress("Block rate", "${(stats.blockRate * 100).toInt()}%", stats.blockRate, PgAccent)
                Spacer(modifier = Modifier.height(14.dp))
                LabeledProgress("Encryption health", "${(stats.encryptionHealth * 100).toInt()}% secure", stats.encryptionHealth, PgAccent)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "My PrivacyGuard score is ${stats.privacyScore}/100 today.")
                        }
                        context.startActivity(Intent.createChooser(share, "Share Privacy Score"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share Privacy Score")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.exportItReport() },
                    colors = ButtonDefaults.buttonColors(containerColor = PgInfoDim, contentColor = PgInfo),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share with IT")
                }
            }
        }

        lastItReportPaths?.let { paths ->
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("IT report ready", style = MaterialTheme.typography.titleMedium, color = PgText)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("JSON and PDF exports were generated for the last 24h.", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val jsonUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(paths.first))
                            val pdfUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(paths.second))
                            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "application/octet-stream"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(jsonUri, pdfUri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share with IT"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PgAccentDim, contentColor = PgAccent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share JSON + PDF")
                    }
                }
            }
        }

        if (timeline.isNotEmpty()) {
            item {
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Threat Timeline")
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
                        Text("$highSeverityAnomalies DNS anomalies detected", style = MaterialTheme.typography.titleMedium, color = PgDanger)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("High-severity anomalies persisted from the DNS shield", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                }
            }
        }

        itemsIndexed(topBlockedDomains) { index, item ->
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        Text("#${index + 1}", style = MaterialTheme.typography.labelMedium, color = PgTextMuted)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Column {
                            Text(item.domain, style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("${item.count} blocked requests", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                        }
                    }
                    Text("${item.count}", style = MaterialTheme.typography.titleLarge, color = PgDanger)
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
                                else item.count.toFloat() / topBlockedDomains.first().count.toFloat()
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
                        "Top Destination Countries",
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
                                "${item.connectionCount}",
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
                                            topCountries.first().connectionCount.toFloat()
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
                    SectionLabel("Remembered Networks")
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
                                    "${network.cleartextCount} cleartext · ${network.dnsAnomalyCount} DNS anomalies · ${network.weakTlsCount} weak TLS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PgTextMuted,
                                )
                            }
                            Text("${network.trustScore}", style = MaterialTheme.typography.titleMedium, color = if (network.trustLevel == "Reduced") PgDanger else if (network.trustLevel == "Watch") PgWarning else PgInfo)
                        }
                        if (index != rememberedNetworks.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val offset = 0x1F1E6 - 'A'.code
    return countryCode.uppercase().map { Character.toChars(it.code + offset).concatToString() }.joinToString("")
}
