package com.privacyguard.app.ui.traffic

import android.app.Application
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.privacyguard.app.core.tracker.DomainClass
import com.privacyguard.app.core.tracker.DomainClassifier
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.ui.components.AppIconImage
import com.privacyguard.app.ui.components.PanelCard
import com.privacyguard.app.ui.components.SectionLabel
import com.privacyguard.app.ui.components.StatusPill
import com.privacyguard.app.ui.components.formatBytes
import com.privacyguard.app.ui.text.FindingText
import com.privacyguard.app.ui.theme.PgAccent
import com.privacyguard.app.ui.theme.PgInfo
import com.privacyguard.app.ui.theme.PgText
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

data class DomainAppUse(val packageName: String, val storedName: String?, val lookups: Int, val connections: Int, val bytes: Long)

data class DomainDetailState(
    val loading: Boolean = true,
    val host: String = "",
    val classification: DomainClass? = null,
    val lookups: Int = 0,
    val blockedLookups: Int = 0,
    val connections: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val apps: List<DomainAppUse> = emptyList(),
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    val historyStart: Long? = null,
    val anomalies: List<DnsAnomalyEntity> = emptyList(),
)

class DomainDetailViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val host: String = saved["host"] ?: ""
    private val _state = MutableStateFlow(DomainDetailState(host = host))
    val state: StateFlow<DomainDetailState> = _state

    init {
        viewModelScope.launch {
            val ctx: Context = getApplication()
            _state.value = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(ctx)
                val since = System.currentTimeMillis() - HomeEvidence.WEEK_MS
                val dns = db.dnsQueryDao().forDomainSince(host, since)
                val conns = db.connectionDao().forHostSince(host, since)
                val byApp = (dns.map { it.appPackage } + conns.map { it.packageName.ifBlank { TrafficRows.UNKNOWN_PACKAGE } }).distinct()
                    .map { pkg ->
                        val d = dns.filter { it.appPackage == pkg }
                        val c = conns.filter { it.packageName.ifBlank { TrafficRows.UNKNOWN_PACKAGE } == pkg }
                        DomainAppUse(
                            packageName = pkg,
                            storedName = c.firstOrNull()?.appName ?: d.firstOrNull()?.appName,
                            lookups = d.size,
                            connections = c.size,
                            bytes = c.sumOf { it.bytesSent + it.bytesReceived },
                        )
                    }
                    .sortedByDescending { it.lookups + it.connections }
                val times = dns.map { it.timestamp } + conns.map { it.timestamp }
                DomainDetailState(
                    loading = false,
                    host = host,
                    // Owner and purpose without an app's point of view: this screen is about the domain.
                    classification = DomainClassifier.classify(host),
                    lookups = dns.size,
                    blockedLookups = dns.count { it.wasBlocked },
                    connections = conns.size,
                    bytesSent = conns.sumOf { it.bytesSent },
                    bytesReceived = conns.sumOf { it.bytesReceived },
                    apps = byApp,
                    firstSeen = times.minOrNull(),
                    lastSeen = times.maxOrNull(),
                    historyStart = db.dnsQueryDao().oldestTimestamp(),
                    anomalies = db.dnsAnomalyDao().forDomain(host),
                )
            }
        }
    }
}

@Composable
fun DomainDetailScreen(
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    viewModel: DomainDetailViewModel = viewModel(),
) {
    val context = LocalContext.current
    val s by viewModel.state.collectAsStateWithLifecycle()
    val nf = NumberFormat.getIntegerInstance()
    val cls = s.classification

    LazyColumn(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.appd_back), tint = PgTextMuted)
                }
                Text(s.host, style = MaterialTheme.typography.titleLarge, color = PgText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s.loading || cls == null) return@LazyColumn

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                SectionLabel(stringResource(R.string.trf_owner))
                Text(cls.company ?: stringResource(R.string.trf_owner_unknown), style = MaterialTheme.typography.titleMedium, color = PgText)
                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.trf_purpose))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (bg, fg) = purposeColors(cls.purpose)
                    StatusPill(purposeChip(context, cls.purpose), bg, fg)
                }
                Spacer(Modifier.height(4.dp))
                Text(purposeExplanation(context, cls.purpose), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                Spacer(Modifier.height(6.dp))
                if (cls.trackerName != null && cls.trackerCategory != null) {
                    Text(
                        stringResource(R.string.trf_tracker_db, cls.trackerName, FindingText.trackerCategory(context, cls.trackerCategory)),
                        style = MaterialTheme.typography.bodyMedium, color = PgText,
                    )
                    Text(FindingText.trackerCategoryExplanation(context, cls.trackerCategory), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                } else {
                    Text(stringResource(R.string.trf_not_tracker), style = MaterialTheme.typography.bodySmall, color = PgTextMuted)
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                SectionLabel(stringResource(R.string.trf_frequency))
                if (s.lookups == 0 && s.connections == 0) {
                    Text(stringResource(R.string.trf_no_domain_data), style = MaterialTheme.typography.bodyMedium, color = PgTextMuted)
                } else {
                    Text(
                        listOf(
                            pluralStringResource(R.plurals.trf_tab_dns, s.lookups, s.lookups),
                            pluralStringResource(R.plurals.trf_tab_conn, s.connections, s.connections),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium, color = PgText,
                    )
                    Text(
                        stringResource(R.string.trf_per_day, nf.format((s.lookups + s.connections) / 7.0)),
                        style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                    )
                    if (s.blockedLookups > 0) {
                        Text(pluralStringResource(R.plurals.dash_blocked_count, s.blockedLookups, s.blockedLookups), style = MaterialTheme.typography.bodySmall, color = com.privacyguard.app.ui.theme.PgDanger)
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel(stringResource(R.string.trf_data))
                    Text(stringResource(R.string.trf_bytes, formatBytes(s.bytesSent), formatBytes(s.bytesReceived)), style = MaterialTheme.typography.bodyMedium, color = PgText)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            SectionLabel(stringResource(R.string.trf_first_seen))
                            Text(s.firstSeen?.let { formatWhen(context, it) } ?: "–", style = MaterialTheme.typography.bodyMedium, color = PgText)
                        }
                        Column(Modifier.weight(1f)) {
                            SectionLabel(stringResource(R.string.trf_last_seen))
                            Text(s.lastSeen?.let { formatWhen(context, it) } ?: "–", style = MaterialTheme.typography.bodyMedium, color = PgText)
                        }
                    }
                    s.historyStart?.let {
                        Text(stringResource(R.string.trf_seen_note, formatWhen(context, it)), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                    }
                }
            }
        }

        if (s.apps.isNotEmpty()) {
            item { CountHeader(stringResource(R.string.trf_apps_used)) }
            items(s.apps, key = { it.packageName }) { use ->
                PanelCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clickable { onOpenApp(use.packageName) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconImage(use.packageName, size = 32.dp, cornerRadius = 8.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(AppNames.label(context, use.packageName, use.storedName), style = MaterialTheme.typography.titleSmall, color = PgText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    pluralStringResource(R.plurals.trf_tab_dns, use.lookups, use.lookups),
                                    pluralStringResource(R.plurals.trf_tab_conn, use.connections, use.connections),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = PgTextMuted,
                            )
                        }
                        Text(formatBytes(use.bytes), style = MaterialTheme.typography.labelMedium, color = PgAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (s.anomalies.isNotEmpty()) {
            item { CountHeader(stringResource(R.string.trf_anomalies)) }
            items(s.anomalies, key = { it.id }) { a ->
                PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Text(FindingText.dnsAnomalyTitle(context, a.anomalyType), style = MaterialTheme.typography.titleSmall, color = PgText)
                    Text(formatWhen(context, a.timestamp), style = MaterialTheme.typography.labelSmall, color = PgTextFaint)
                }
            }
        }

        item {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Text(stringResource(R.string.trf_honesty), style = MaterialTheme.typography.bodySmall, color = PgInfo)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
