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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.testTag
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
    /** Days of the 7-day window that history actually covers (at least one). */
    val daysCovered: Double = 7.0,
)

class DomainDetailViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val host: String = saved["host"] ?: ""
    /** The app this screen was opened for (App traffic); null from anywhere else. */
    private val forApp: String? = saved.get<String>("app")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(DomainDetailState(host = host))
    val state: StateFlow<DomainDetailState> = _state

    private val activity = com.privacyguard.app.ui.activity.ActivityRepository(app)

    /** Allow and Block for this name: the same helper as Activity (D8), malware warning and Undo included. */
    val ruleActions = com.privacyguard.app.ui.activity.RuleActionController(app, viewModelScope, activity)

    private val _name = MutableStateFlow<com.privacyguard.app.ui.activity.ActivityItem?>(null)
    /** What CoreLock does with the name now; read again after every rule change. */
    val name: StateFlow<com.privacyguard.app.ui.activity.ActivityItem?> = _name

    fun perform(action: com.privacyguard.app.ui.activity.ActivityAction, item: com.privacyguard.app.ui.activity.ActivityItem, confirmedMalware: Boolean = false) =
        ruleActions.perform(action, item, confirmedMalware)

    init {
        viewModelScope.launch {
            // RuleSyncBus: every rule write publishes; its current value loads the first time.
            com.privacyguard.app.data.repository.RuleSyncBus.version.collect {
                try {
                    _name.value = activity.nameNow(host, forApp)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DomainDetail", "Name state failed: ${e.javaClass.simpleName}")
                }
            }
        }

        viewModelScope.launch {
            val ctx: Context = getApplication()
            _state.value = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(ctx)
                val since = System.currentTimeMillis() - HomeEvidence.WEEK_MS
                val dns = db.dnsQueryDao().forDomainSince(host, since)
                // Blocked attempts sent nothing; they show up as blocked lookups-and-attempts elsewhere.
                val conns = db.connectionDao().forHostSince(host, since).filter { !it.wasBlocked }
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
                    // First time in all the history we keep, not just this week.
                    firstSeen = listOfNotNull(db.dnsQueryDao().firstSeen(host), db.connectionDao().firstSeen(host)).minOrNull(),
                    lastSeen = times.maxOrNull(),
                    historyStart = db.dnsQueryDao().oldestTimestamp(),
                    daysCovered = db.dnsQueryDao().oldestTimestamp()?.let { oldest ->
                        ((System.currentTimeMillis() - maxOf(oldest, since)).toDouble() / HomeEvidence.DAY_MS).coerceIn(1.0, 7.0)
                    } ?: 7.0,
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
    val name by viewModel.name.collectAsStateWithLifecycle()
    val warning by viewModel.ruleActions.malwareWarning.collectAsStateWithLifecycle()
    val message by viewModel.ruleActions.message.collectAsStateWithLifecycle()
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val undoLabel = stringResource(R.string.ui_undo)
    val nf = NumberFormat.getIntegerInstance()
    val cls = s.classification

    androidx.compose.runtime.LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(
            m.text,
            actionLabel = m.undo?.let { undoLabel },
            duration = if (m.undo != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
        )
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) m.undo?.invoke()
        viewModel.ruleActions.messageShown(m.id)
    }
    warning?.let { pending ->
        com.privacyguard.app.ui.rules.SecurityAllowWarningDialog(
            domain = pending.item.host.orEmpty(),
            onConfirm = { viewModel.perform(pending.action, pending.item, confirmedMalware = true) },
            onDismiss = viewModel.ruleActions::dismissMalwareWarning,
        )
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
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
        name?.let { now ->
            item(key = "name_now") { NameNow(now, onAction = { viewModel.perform(it, now) }) }
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
                // No app context here: a service domain can be first-party for one app and third-party for another.
                Text(
                    if (cls.purpose == com.privacyguard.app.core.tracker.DomainPurpose.SERVICE && cls.company != null)
                        stringResource(R.string.trf_pd_service_domain, cls.company)
                    else purposeExplanation(context, cls.purpose),
                    style = MaterialTheme.typography.bodySmall, color = PgTextMuted,
                )
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
                        // One unit: a lookup usually precedes a connection, so adding both double counts.
                        // Under one a day the integer format would read "about 0 per day".
                        ((if (s.lookups > 0) s.lookups else s.connections) / s.daysCovered).let { rate ->
                            if (rate < 1.0) stringResource(R.string.trf_per_day_under_one)
                            else stringResource(R.string.trf_per_day, nf.format(rate))
                        },
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
    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/**
 * What CoreLock does with the name now, and the choices for it (the same as Activity's
 * sheet; "Keep blocked" is left out: on this screen it would do nothing).
 */
@Composable
internal fun NameNow(item: com.privacyguard.app.ui.activity.ActivityItem, onAction: (com.privacyguard.app.ui.activity.ActivityAction) -> Unit) {
    val colors = com.privacyguard.app.ui.theme.CoreLockTheme.colors
    val spacing = com.privacyguard.app.ui.theme.CoreLockTheme.spacing
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s),
        modifier = Modifier
            .padding(horizontal = spacing.screen)
            .fillMaxWidth()
            .background(colors.surface, com.privacyguard.app.ui.theme.CoreLockTheme.shapes.card)
            .padding(spacing.l)
            .testTag(TAG_NAME_NOW),
    ) {
        Text(
            stringResource(com.privacyguard.app.ui.activity.reasonLabel(item.reason)),
            style = MaterialTheme.typography.titleMedium,
            color = if (item.reason == com.privacyguard.app.ui.activity.ActivityReason.MALWARE_LIST) colors.danger else colors.onSurface,
        )
        if ((item.reason == com.privacyguard.app.ui.activity.ActivityReason.ADS_LIST || item.reason == com.privacyguard.app.ui.activity.ActivityReason.AD_SDK) && com.privacyguard.app.ui.activity.ActivityLogic.everywhereOnly(item)) {
            Text(stringResource(R.string.ui_dns_allow_everywhere_only), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        }
        val actions = com.privacyguard.app.ui.activity.ActivityLogic.actionsFor(item)
            .filterNot { it == com.privacyguard.app.ui.activity.ActivityAction.KEEP_BLOCKED }
        com.privacyguard.app.ui.activity.ActionButtons(actions, onAction)
    }
}

internal const val TAG_NAME_NOW = "name_now"
