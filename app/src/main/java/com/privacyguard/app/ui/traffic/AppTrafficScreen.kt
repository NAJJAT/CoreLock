package com.privacyguard.app.ui.traffic

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.platform.android.ForegroundAppDetector
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a list is empty, so an empty screen never looks like "all clear" by mistake. */
enum class EmptyCause { VPN_OFF, UNATTRIBUTED, NOT_OPENED, IDLE_NOTHING, NO_TRAFFIC }

data class AppTrafficState(
    val loading: Boolean = true,
    val appName: String = "",
    val from: Long = 0,
    val to: Long = 0,
    val idleOnly: Boolean = false,
    val connections: List<TrafficRow> = emptyList(),
    val lookups: List<TrafficRow> = emptyList(),
    val groups: List<PurposeGroup> = emptyList(),
    val emptyCause: EmptyCause? = null,
    /** Set when the period starts before the oldest record we still keep. */
    val historyStart: Long? = null,
    val hasUsageAccess: Boolean = true,
)

class AppTrafficViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val packageName: String = saved["packageName"] ?: ""
    private val from: Long = saved.get<String>("from")?.toLongOrNull() ?: (System.currentTimeMillis() - HomeEvidence.DAY_MS)
    private val to: Long = saved.get<String>("to")?.toLongOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
    private val idleOnly: Boolean = saved.get<String>("idle") == "1"

    private val _state = MutableStateFlow(AppTrafficState())
    val state: StateFlow<AppTrafficState> = _state

    init { load() }

    private fun load() = viewModelScope.launch {
        val ctx: Context = getApplication()
        _state.value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(ctx)
            // Unattributed traffic: connections are stored with a blank package, lookups as "Unknown".
            val unattributed = packageName.isBlank() || packageName == TrafficRows.UNKNOWN_PACKAGE
            val rawConn = db.connectionDao().forPackageBetween(if (unattributed) "" else packageName, from, to)
            val rawDns = db.dnsQueryDao().forPackageBetween(if (unattributed) TrafficRows.UNKNOWN_PACKAGE else packageName, from, to)
            val hosts = (rawConn.mapNotNull { it.sniHostname ?: it.domain } + rawDns.map { it.domain }).toSet()
            val anomalies = db.dnsAnomalyDao().since(from - HomeEvidence.WEEK_MS).filter { it.domain in hosts }.groupBy { it.domain }

            // Idle = screen off. Connections from before screen state was recorded are excluded
            // from the idle view rather than guessed.
            val connections = rawConn.filter { !idleOnly || it.screenOn == false }.map { TrafficRows.fromConnection(it, anomalies) }
            val lookups = rawDns.filter { !idleOnly || it.phoneWasIdle }.map { TrafficRows.fromLookup(it, anomalies) }
            // A blocked lookup never turns into a connection; list it with the connections.
            val connectionList = (connections + lookups.filter { it.blocked }).sortedByDescending { it.timestamp }
            val storedName = rawConn.firstOrNull()?.appName ?: rawDns.firstOrNull()?.appName
            val oldest = db.dnsQueryDao().oldestTimestamp()
            val detector = ForegroundAppDetector(ctx)

            val empty = connectionList.isEmpty() && lookups.isEmpty()
            AppTrafficState(
                loading = false,
                appName = AppNames.label(ctx, packageName, storedName),
                from = from,
                to = to,
                idleOnly = idleOnly,
                connections = connectionList,
                lookups = lookups,
                groups = PurposeGroups.build(connections + lookups),
                emptyCause = if (!empty) null else when {
                    unattributed -> EmptyCause.UNATTRIBUTED
                    !PrivacyVpnService.isRunning -> EmptyCause.VPN_OFF
                    idleOnly -> EmptyCause.IDLE_NOTHING
                    wasNotOpened(ctx, detector) -> EmptyCause.NOT_OPENED
                    else -> EmptyCause.NO_TRAFFIC
                },
                historyStart = oldest?.takeIf { it > from },
                hasUsageAccess = detector.hasUsageAccess(),
            )
        }
    }

    /** True only when Usage Access lets us confirm the app had no screen time in the period. */
    private fun wasNotOpened(ctx: Context, detector: ForegroundAppDetector): Boolean {
        if (!detector.hasUsageAccess()) return false
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val stats = runCatching { usm.queryAndAggregateUsageStats(from, to)[packageName] }.getOrNull()
        return (stats?.totalTimeInForeground ?: 0L) == 0L
    }
}

@Composable
fun AppTrafficScreen(
    onBack: () -> Unit,
    onOpenDomain: (String) -> Unit,
    viewModel: AppTrafficViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.appd_back), tint = PgTextMuted)
                    }
                    AppIconImage(viewModel.packageName, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.appName, style = MaterialTheme.typography.titleLarge, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.trf_title), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.trf_window, formatWhen(context, state.from), formatWhen(context, state.to)),
                        style = MaterialTheme.typography.labelMedium, color = PgTextMuted, modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        stringResource(if (state.idleOnly) R.string.trf_idle_only else R.string.trf_all_activity),
                        MaterialTheme.colorScheme.surfaceVariant, PgAccent,
                    )
                }
            }
        }
        if (state.loading) return@LazyColumn
        if (!state.hasUsageAccess) item { UsageAccessHint() }
        state.historyStart?.let { start ->
            item { EmptyReason(stringResource(R.string.trf_empty_history, formatWhen(context, start))) }
        }
        state.emptyCause?.let { cause ->
            item {
                EmptyReason(
                    when (cause) {
                        EmptyCause.VPN_OFF -> stringResource(R.string.trf_empty_vpn_off)
                        EmptyCause.UNATTRIBUTED -> stringResource(R.string.trf_empty_unattributed)
                        EmptyCause.NOT_OPENED -> stringResource(R.string.trf_empty_not_opened, state.appName)
                        EmptyCause.IDLE_NOTHING -> stringResource(R.string.trf_empty_idle, state.appName)
                        EmptyCause.NO_TRAFFIC -> stringResource(R.string.trf_empty_no_traffic, state.appName)
                    }
                )
            }
            return@LazyColumn
        }

        item {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.trf_tab_purpose), maxLines = 1) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                    Text(pluralStringResource(R.plurals.trf_tab_conn, state.connections.size, state.connections.size), maxLines = 1)
                })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = {
                    Text(pluralStringResource(R.plurals.trf_tab_dns, state.lookups.size, state.lookups.size), maxLines = 1)
                })
            }
        }

        when (tab) {
            0 -> {
                item { CountHeader(pluralStringResource(R.plurals.trf_n_domains, state.groups.sumOf { it.domains.size }, state.groups.sumOf { it.domains.size }), stringResource(R.string.trf_purpose_note)) }
                state.groups.forEach { group ->
                    item(key = "g_${group.purpose}_${group.company}") { PurposeGroupCard(group, state.appName, onOpenDomain) }
                }
            }
            1 -> {
                item { CountHeader(pluralStringResource(R.plurals.trf_tab_conn, state.connections.size, state.connections.size), stringResource(R.string.trf_conn_note)) }
                items(state.connections, key = { it.id }) { row ->
                    TrafficRowItem(row, showApp = false, onOpenDomain = onOpenDomain)
                }
            }
            else -> {
                item { CountHeader(pluralStringResource(R.plurals.trf_tab_dns, state.lookups.size, state.lookups.size), stringResource(R.string.trf_dns_note) + "\n" + stringResource(R.string.trf_dns_attribution)) }
                items(state.lookups, key = { it.id }) { row ->
                    RawLookupLine(row, onOpenDomain)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PurposeGroupCard(group: PurposeGroup, appName: String, onOpenDomain: (String) -> Unit) {
    val context = LocalContext.current
    val (bg, fg) = purposeColors(group.purpose)
    PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(groupTitle(context, group, appName), style = MaterialTheme.typography.titleMedium, color = PgText, modifier = Modifier.weight(1f))
            StatusPill(purposeChip(context, group.purpose), bg, fg)
        }
        Spacer(Modifier.height(2.dp))
        Text(purposeExplanation(context, group.purpose), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.trf_group_counts,
                pluralStringResource(R.plurals.trf_tab_conn, group.connections, group.connections),
                pluralStringResource(R.plurals.trf_tab_dns, group.lookups, group.lookups),
                formatBytes(group.bytes),
            ),
            style = MaterialTheme.typography.labelSmall, color = PgTextFaint,
        )
        Spacer(Modifier.height(6.dp))
        group.domains.forEach { d ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenDomain(d.host) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.host, style = MaterialTheme.typography.bodyMedium, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            d.classification.company,
                            // "Facebook SDK" describes the SDK inside other apps, not Facebook itself.
                            d.classification.trackerName.takeIf { d.classification.purpose != DomainPurpose.FIRST_PARTY },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.trf_company_unknown) },
                        style = MaterialTheme.typography.labelSmall, color = PgTextFaint, maxLines = 1,
                    )
                }
                Text(
                    java.text.NumberFormat.getIntegerInstance().format(d.connections + d.lookups),
                    style = MaterialTheme.typography.titleSmall, color = PgAccent, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun RawLookupLine(row: TrafficRow, onOpenDomain: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { row.host?.let(onOpenDomain) }
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatWhen(context, row.timestamp), style = MaterialTheme.typography.labelSmall, color = PgTextFaint, modifier = Modifier.width(110.dp))
        Text(row.host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = PgText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Text(
            listOf(
                stringResource(if (row.blocked) R.string.trf_blocked else R.string.trf_allowed),
                stringResource(if (row.screenOn == false) R.string.trf_screen_off else R.string.trf_screen_on),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (row.blocked) com.privacyguard.app.ui.theme.PgDanger else PgTextMuted,
        )
    }
}
