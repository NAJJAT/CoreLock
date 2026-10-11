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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
import androidx.compose.ui.platform.LocalContext
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAppSummary
import com.privacyguard.app.data.db.DnsDomainSummary
import com.privacyguard.app.ui.components.AppIconImage
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
import com.privacyguard.app.ui.traffic.AppNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SleepReportState(
    val idleDnsQueries: Int = 0,
    val idleApps: Int = 0,
    val trackingDomains: Int = 0,
    val suspiciousApps: Int = 0,
    /** Every app with idle lookups: its size is the "Apps" number. */
    val apps: List<DnsAppSummary> = emptyList(),
    /** Every blocked domain looked up while idle: its size is the "Tracking" number. */
    val blockedDomains: List<DnsDomainSummary> = emptyList(),
    val topDomains: List<DnsDomainSummary> = emptyList(),
    val from: Long = 0,
    val to: Long = 0,
)

/**
 * "While you slept": DNS lookups made while the screen was off.
 * [at] is when Home computed its card; the 7-day view uses the same window so the
 * card and this report show the same numbers.
 */
@Composable
fun SleepReportScreen(
    at: Long? = null,
    onBack: () -> Unit,
    onOpenApp: (packageName: String, from: Long, to: Long) -> Unit = { _, _, _ -> },
    onOpenDomain: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var selectedDays by remember { mutableIntStateOf(7) }
    var state by remember { mutableStateOf(SleepReportState()) }
    val anchor = remember { at ?: System.currentTimeMillis() }

    LaunchedEffect(selectedDays) {
        state = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).dnsQueryDao()
            val to = anchor
            val from = to - selectedDays.toLong() * 24L * 60L * 60L * 1000L
            val apps = dao.idleAppsBetween(from, to)
            val blocked = dao.idleBlockedDomainsBetween(from, to)
            SleepReportState(
                idleDnsQueries = dao.countIdleBetween(from, to),
                idleApps = dao.countIdleAppsBetween(from, to),
                trackingDomains = dao.countIdleBlockedDomainsBetween(from, to),
                suspiciousApps = apps.count { it.idleQueries >= 5 },
                apps = apps,
                blockedDomains = blocked,
                topDomains = dao.topIdleDomainsBetween(from, to, 20),
                from = from,
                to = to,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenScaffold(
                title = stringResource(R.string.dash_sleep_title),
                subtitle = stringResource(R.string.sleep_subtitle),
                badge = pluralStringResource(R.plurals.sleep_badge_days, selectedDays, selectedDays).uppercase(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.appd_back), tint = PgTextMuted)
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip(stringResource(R.string.stats_today), selectedDays == 1) { selectedDays = 1 }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip(pluralStringResource(R.plurals.sleep_badge_days, 7, 7), selectedDays == 7) { selectedDays = 7 }
                    Spacer(modifier = Modifier.size(8.dp))
                    RangeChip(pluralStringResource(R.plurals.sleep_badge_days, 30, 30), selectedDays == 30) { selectedDays = 30 }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(stringResource(R.string.sleep_definition), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.trf_dns_attribution), style = MaterialTheme.typography.bodySmall, color = PgTextFaint)
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
                            Text(stringResource(R.string.sleep_activity), style = MaterialTheme.typography.titleMedium, color = PgText)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                pluralStringResource(R.plurals.sleep_idle_apps, state.idleApps, state.idleApps),
                                style = MaterialTheme.typography.bodySmall,
                                color = PgTextMuted,
                            )
                        }
                        StatusPill(
                            if (state.suspiciousApps > 0) pluralStringResource(R.plurals.sleep_review, state.suspiciousApps, state.suspiciousApps) else stringResource(R.string.dash_status_quiet),
                            if (state.suspiciousApps > 0) PgWarningDim else PgAccentDim,
                            if (state.suspiciousApps > 0) PgWarning else PgAccent,
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(stringResource(R.string.sleep_idle_dns), java.text.NumberFormat.getIntegerInstance().format(state.idleDnsQueries), PgInfo, Modifier.weight(1f))
                        StatTile(stringResource(R.string.sleep_apps), java.text.NumberFormat.getIntegerInstance().format(state.idleApps), PgAccent, Modifier.weight(1f))
                        StatTile(stringResource(R.string.sleep_tracking), java.text.NumberFormat.getIntegerInstance().format(state.trackingDomains), PgDanger, Modifier.weight(1f))
                    }
                }
            }
        }

        // Apps: one row per app counted in "Apps"; the idle counts add up to "Idle lookups".
        item { Header("${stringResource(R.string.sleep_top_apps)} (${java.text.NumberFormat.getIntegerInstance().format(state.apps.size)})") }
        if (state.apps.isEmpty()) {
            item { EmptyPanel(stringResource(R.string.sleep_no_apps)) }
        } else {
            items(state.apps, key = { it.appPackage }) { app ->
                AppSleepRow(app) { onOpenApp(app.appPackage, state.from, state.to) }
            }
        }

        // Exactly the domains counted in "Tracking".
        item { Header("${stringResource(R.string.sleep_tracking)} (${java.text.NumberFormat.getIntegerInstance().format(state.blockedDomains.size)})") }
        if (state.blockedDomains.isEmpty()) {
            item { EmptyPanel(stringResource(R.string.sleep_no_domains)) }
        } else {
            items(state.blockedDomains, key = { "b_" + it.domain }) { DomainSleepRow(it) { onOpenDomain(it.domain) } }
        }

        item { Header(stringResource(R.string.sleep_top_domains)) }
        if (state.topDomains.isEmpty()) {
            item { EmptyPanel(stringResource(R.string.sleep_no_domains)) }
        } else {
            items(state.topDomains, key = { "t_" + it.domain }) { DomainSleepRow(it) { onOpenDomain(it.domain) } }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun Header(text: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) { SectionLabel(text) }
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
private fun AppSleepRow(row: DnsAppSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(row.appPackage, size = 38.dp)
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(AppNames.label(context, row.appPackage, row.appName), style = MaterialTheme.typography.titleMedium, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.blockedQueries > 0) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(pluralStringResource(R.plurals.dash_blocked_count, row.blockedQueries, row.blockedQueries), style = MaterialTheme.typography.bodySmall, color = PgDanger)
                }
            }
            Text(pluralStringResource(R.plurals.sleep_idle_n, row.idleQueries, row.idleQueries), style = MaterialTheme.typography.titleSmall, color = PgWarning, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DomainSleepRow(row: DnsDomainSummary, onClick: () -> Unit) {
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick)) {
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
                Text(pluralStringResource(R.plurals.sleep_idle_queries, row.idleQueries, row.idleQueries), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
            }
            if (row.blockedQueries > 0) {
                StatusPill(pluralStringResource(R.plurals.dash_blocked_count, row.blockedQueries, row.blockedQueries), PgDangerDim, PgDanger)
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
