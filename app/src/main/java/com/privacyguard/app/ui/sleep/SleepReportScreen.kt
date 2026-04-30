package com.privacyguard.app.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAppSummary
import com.privacyguard.app.data.db.DnsDomainSummary
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.ScreenScaffold
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatTile
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgAccentDim
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SleepReportState(
    val idleDnsQueries: Int = 0,
    val idleApps: Int = 0,
    val trackingDomains: Int = 0,
    val suspiciousApps: Int = 0,
    val topApps: List<DnsAppSummary> = emptyList(),
    val topDomains: List<DnsDomainSummary> = emptyList(),
)

@Composable
fun SleepReportScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selectedDays by remember { mutableIntStateOf(7) }
    var state by remember { mutableStateOf(SleepReportState()) }

    LaunchedEffect(selectedDays) {
        state = withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val since = System.currentTimeMillis() - selectedDays.toLong() * 24L * 60L * 60L * 1000L
            val apps = db.dnsQueryDao().idleAppSummariesSince(since, 20)
            SleepReportState(
                idleDnsQueries = db.dnsQueryDao().countIdleSince(since),
                idleApps = db.dnsQueryDao().countIdleAppsSince(since),
                trackingDomains = db.dnsQueryDao().countIdleBlockedDomainsSince(since),
                suspiciousApps = apps.count { it.idleQueries >= 5 && it.totalQueries == it.idleQueries },
                topApps = apps,
                topDomains = db.dnsQueryDao().topIdleDomainsSince(since, 20),
            )
        }
    }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenScaffold(
                title = "While You Were Sleeping",
                subtitle = "Screen-off DNS and background activity",
                badge = "${selectedDays}D",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PgTextMuted)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip("Today", selectedDays == 1) { selectedDays = 1 }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip("7 days", selectedDays == 7) { selectedDays = 7 }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip("30 days", selectedDays == 30) { selectedDays = 30 }
                }
                Spacer(modifier = Modifier.height(14.dp))
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(PgInfoDim, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = null, tint = PgInfo, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Screen-off activity", style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${state.idleApps} apps made DNS queries while the phone was idle",
                                style = MaterialTheme.typography.bodySmall,
                                color = PgTextMuted,
                            )
                        }
                        StatusPill(
                            if (state.suspiciousApps > 0) "${state.suspiciousApps} review" else "quiet",
                            if (state.suspiciousApps > 0) PgWarningDim else PgAccentDim,
                            if (state.suspiciousApps > 0) PgWarning else PgAccent,
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Idle DNS", state.idleDnsQueries.toString(), PgInfo, Modifier.weight(1f))
                        StatTile("Apps", state.idleApps.toString(), PgAccent, Modifier.weight(1f))
                        StatTile("Tracking", state.trackingDomains.toString(), PgDanger, Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Top apps active while idle")
            }
        }
        if (state.topApps.isEmpty()) {
            item { EmptyPanel("No screen-off DNS activity recorded yet.") }
        } else {
            items(state.topApps, key = { it.appPackage }) { app ->
                AppSleepRow(app)
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("Top domains contacted while idle")
            }
        }
        if (state.topDomains.isEmpty()) {
            item { EmptyPanel("No domains were contacted while the screen was off.") }
        } else {
            items(state.topDomains, key = { it.domain }) { domain ->
                DomainSleepRow(domain)
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) PgAccentDim else PgBackgroundAlt, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) PgAccent else PgTextMuted)
    }
}

@Composable
private fun AppSleepRow(row: DnsAppSummary) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (row.blockedQueries > 0) PgDangerDim else PgInfoDim, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = if (row.blockedQueries > 0) PgDanger else PgInfo, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.appName, style = MaterialTheme.typography.titleMedium, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(3.dp))
                Text(row.appPackage, style = MaterialTheme.typography.bodySmall, color = PgTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${row.idleQueries} idle", style = MaterialTheme.typography.titleSmall, color = PgWarning, fontWeight = FontWeight.SemiBold)
                Text("${row.totalQueries} total", style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
            }
        }
    }
}

@Composable
private fun DomainSleepRow(row: DnsDomainSummary) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (row.blockedQueries > 0) PgDangerDim else PgAccentDim, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.TrackChanges, contentDescription = null, tint = if (row.blockedQueries > 0) PgDanger else PgAccent, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.domain, style = MaterialTheme.typography.titleMedium, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(3.dp))
                Text("${row.idleQueries} idle DNS queries", style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            if (row.blockedQueries > 0) {
                StatusPill("${row.blockedQueries} blocked", PgDangerDim, PgDanger)
            }
        }
    }
}

@Composable
private fun EmptyPanel(text: String) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
    }
}
