package com.privacyguard.app.ui.traffic

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.text.FindingText
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.app.ui.theme.PgWarning
import com.privacyguard.platform.android.ForegroundAppDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EvidenceState(
    val loading: Boolean = true,
    val kind: EvidenceKind = EvidenceKind.BLOCKED_24H,
    val at: Long = 0,
    val evidence: Evidence? = null,
    val hasUsageAccess: Boolean = true,
)

class EvidenceViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val kind = runCatching { EvidenceKind.valueOf(saved.get<String>("kind") ?: "") }.getOrDefault(EvidenceKind.BLOCKED_24H)
    // The moment Home computed its number; the list is built for the same window.
    private val at = saved.get<String>("at")?.toLongOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
    private val _state = MutableStateFlow(EvidenceState(kind = kind, at = at))
    val state: StateFlow<EvidenceState> = _state

    init {
        viewModelScope.launch {
            val ev = withContext(Dispatchers.IO) { HomeEvidence(AppDatabase.getInstance(getApplication())).load(kind, at) }
            _state.value = EvidenceState(false, kind, at, ev, ForegroundAppDetector(getApplication()).hasUsageAccess())
        }
    }
}

@Composable
fun EvidenceScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenDomain: (String) -> Unit,
    viewModel: EvidenceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val s by viewModel.state.collectAsStateWithLifecycle()
    val (title, definition) = evidenceTitle(s.kind, s.evidence)

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.appd_back), tint = PgTextMuted)
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = PgText)
            }
        }
        val ev = s.evidence ?: return@LazyColumn
        item {
            val isBlockedList = s.kind == EvidenceKind.BLOCKED_24H || s.kind == EvidenceKind.BLOCKED_7D
            val domains = (ev as? Evidence.Rows)?.rows?.mapNotNull { (it.host ?: it.ip)?.lowercase() }?.toSet()?.size ?: 0
            CountHeader(
                // Lookups for A and AAAA, and retries, are collapsed when logged; the domain
                // count says how many distinct destinations those entries are.
                if (isBlockedList) pluralStringResource(R.plurals.dash_blocked_count, ev.size, ev.size) + " · " +
                    pluralStringResource(R.plurals.trf_n_domains, domains, domains)
                else pluralStringResource(R.plurals.trf_n_entries, ev.size, ev.size),
                listOfNotNull(definition, stringResource(R.string.ev_at, formatWhen(context, s.at))).joinToString("\n"),
            )
        }
        if (s.kind == EvidenceKind.BACKGROUND_24H && !s.hasUsageAccess) item { UsageAccessHint() }
        if (ev.size == 0) {
            item { EmptyReason(stringResource(R.string.trf_empty_list, definition ?: title)) }
        }
        when (ev) {
            is Evidence.Rows -> items(ev.rows, key = { it.id }) { row ->
                TrafficRowItem(row, showApp = true, onOpenApp = onOpenApp, onOpenDomain = onOpenDomain)
            }
            is Evidence.Anomalies -> items(ev.items, key = { it.id }) { a ->
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { onOpenDomain(a.domain) }) {
                    AppLine(a.packageName, onOpenApp)
                    Text(a.domain, style = MaterialTheme.typography.titleSmall, color = PgText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${FindingText.dnsAnomalyTitle(context, a.anomalyType)} · ${formatWhen(context, a.timestamp)}",
                        style = MaterialTheme.typography.bodySmall, color = PgWarning,
                    )
                    Text(FindingText.dnsAnomalyExplanation(context, a.anomalyType), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
            is Evidence.Beacons -> items(ev.items, key = { it.packageName + "|" + it.host }) { b ->
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { onOpenDomain(b.host) }) {
                    AppLine(b.packageName, onOpenApp)
                    Text(b.host, style = MaterialTheme.typography.titleSmall, color = PgText)
                    Text(
                        stringResource(R.string.ev_beacon_row, java.text.NumberFormat.getIntegerInstance().format(b.count), android.text.format.DateUtils.formatElapsedTime(b.meanIntervalMs / 1000)),
                        style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                    )
                }
            }
            is Evidence.Findings -> items(ev.items) { f ->
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { onOpenDomain(f.hostname) }) {
                    AppLine(f.packageName, onOpenApp)
                    Text(FindingText.title(context, f), style = MaterialTheme.typography.titleSmall, color = PgText)
                    Text(FindingText.summary(context, f), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun AppLine(packageName: String, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = packageName.isNotBlank()) { onOpenApp(packageName) }.padding(bottom = 4.dp),
    ) {
        AppIconImage(packageName, size = 22.dp, cornerRadius = 6.dp)
        Spacer(Modifier.width(8.dp))
        Text(AppNames.label(context, packageName), style = MaterialTheme.typography.labelLarge, color = PgTextFaint, maxLines = 1)
    }
}

@Composable
private fun evidenceTitle(kind: EvidenceKind, ev: Evidence?): Pair<String, String?> = when (kind) {
    EvidenceKind.BLOCKED_24H -> stringResource(R.string.ev_blocked_24h) to stringResource(R.string.ev_blocked_24h_def)
    EvidenceKind.BLOCKED_7D -> stringResource(R.string.ev_blocked_7d) to stringResource(R.string.ev_blocked_24h_def)
    EvidenceKind.CLEARTEXT_24H -> stringResource(R.string.ev_cleartext_24h) to stringResource(R.string.ev_cleartext_24h_def)
    EvidenceKind.ENCRYPTION_24H -> {
        val rows = (ev as? Evidence.Rows)?.rows.orEmpty()
        // Same EncryptionShare as the Home percentage: encrypted of identified, unknown apart.
        // Local-network rows are not internet traffic and are left out, as on Home.
        val internet = rows.filterNot { it.local }
        val encrypted = internet.count { it.encrypted }
        val identified = encrypted + internet.count { it.encryption == "CLEARTEXT" }
        val nf = java.text.NumberFormat.getIntegerInstance()
        stringResource(R.string.ev_encryption_24h) to stringResource(R.string.ev_encryption_24h_def, nf.format(encrypted), nf.format(identified), nf.format(internet.size - identified))
    }
    EvidenceKind.NIGHT_24H -> stringResource(R.string.ev_night_24h) to stringResource(R.string.ev_night_24h_def)
    EvidenceKind.BACKGROUND_24H -> stringResource(R.string.ev_background_24h) to stringResource(R.string.ev_background_24h_def)
    EvidenceKind.IPV6_24H -> stringResource(R.string.ev_ipv6_24h) to stringResource(R.string.ev_ipv6_24h_def)
    EvidenceKind.UPLOADS_24H -> stringResource(R.string.ev_uploads_24h) to stringResource(R.string.ev_uploads_24h_def)
    EvidenceKind.BEACONS_24H -> stringResource(R.string.ev_beacons_24h) to stringResource(R.string.ev_beacons_24h_def)
    EvidenceKind.ENTROPY_7D -> stringResource(R.string.ev_entropy_7d) to stringResource(R.string.ev_entropy_7d_def)
    EvidenceKind.BEHAVIOR_7D -> stringResource(R.string.ev_behavior) to stringResource(R.string.ev_behavior_def)
    EvidenceKind.CONNECTIONS_7D -> stringResource(R.string.ev_connections_7d) to stringResource(R.string.ev_connections_7d_def)
    EvidenceKind.CONNECTIONS_24H -> stringResource(R.string.ev_connections_24h) to stringResource(R.string.ev_connections_7d_def)
    EvidenceKind.ANOMALIES_7D -> stringResource(R.string.ev_anomalies_7d) to stringResource(R.string.ev_anomalies_7d_def)
    EvidenceKind.DATA_7D, EvidenceKind.DATA_24H -> {
        val rows = (ev as? Evidence.Rows)?.rows.orEmpty()
        stringResource(if (kind == EvidenceKind.DATA_24H) R.string.ev_data_24h else R.string.ev_data_7d) to stringResource(R.string.ev_data_7d_def, formatBytes(rows.sumOf { it.bytesSent + it.bytesReceived }))
    }
}
