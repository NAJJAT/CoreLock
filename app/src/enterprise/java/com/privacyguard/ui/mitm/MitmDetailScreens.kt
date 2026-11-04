package com.privacyguard.ui.mitm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.db.notDecryptedReasonRes
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.theme.CoreLockTheme

// Analytics and capture detail of Advanced › HTTPS inspection, restyled with tokens only.

internal fun parseHeadersMap(json: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    // Rows are written by our own engine; a row that isn't JSON simply has no headers.
    runCatching {
        val obj = org.json.JSONObject(json)
        for (k in obj.keys()) map[k] = obj.optString(k)
    }
    return map
}

// ── ANALYTICS SCREEN ─────────────────────────────────────────────────────────
@Composable
internal fun AnalyticsScreen(analytics: PayloadAnalytics, onBack: () -> Unit) {
    // Every stored entry, whatever the list filters say; the four buckets add up to the total.
    val context = LocalContext.current
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val a = analytics
    val appRisks = remember(a) { a.appRisks.map { (pkg, risk) -> shortAppName(context, pkg) to risk } }
    val total = a.total.coerceAtLeast(1)
    val outboundLabel = stringResource(R.string.mitm_outbound)
    val inboundLabel = stringResource(R.string.mitm_inbound)
    val protocolRows = remember(a, outboundLabel, inboundLabel) {
        listOf(
            Triple("HTTP/1.x", a.http1.toDouble() / total, "${a.http1}"),
            Triple("HTTP/2", a.http2.toDouble() / total, "${a.http2}"),
            Triple(outboundLabel, a.outbound.toDouble() / total, "${a.outbound}"),
            Triple(inboundLabel, a.inbound.toDouble() / total, "${a.inbound}"),
        ).filter { it.second > 0 }
    }

    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(title = stringResource(R.string.mitm_analytics), onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            CardSurface {
                Column(Modifier.fillMaxWidth().padding(spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.total}", style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                    Text(stringResource(R.string.mitm_total_requests), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.FLAGGED)}", stringResource(R.string.mitm_flagged), colors.danger)
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.WARNING)}", stringResource(R.string.mitm_warnings), colors.attention)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.CLEAN)}", stringResource(R.string.mitm_clean), colors.protected)
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.METADATA_ONLY)}", stringResource(R.string.mitm_metadata_only), colors.onSurface)
            }
            StatCard(Modifier.fillMaxWidth(), com.privacyguard.app.core.utils.ByteFormat.format(a.totalBytes), stringResource(R.string.mitm_total_size), colors.onSurface)

            if (appRisks.isNotEmpty()) {
                BarChartCard(stringResource(R.string.mitm_risk_by_app), appRisks.map { Triple(it.first, it.second / 100.0, "${it.second}") })
            }
            if (protocolRows.isNotEmpty()) {
                BarChartCard(stringResource(R.string.mitm_traffic_breakdown), protocolRows)
            }
            Spacer(Modifier.height(spacing.xxxl))
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String, valueColor: Color) {
    CardSurface(modifier) {
        Column(Modifier.padding(CoreLockTheme.spacing.l)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
            Text(label, style = MaterialTheme.typography.bodySmall, color = CoreLockTheme.colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun BarChartCard(title: String, rows: List<Triple<String, Double, String>>) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    CardSurface {
        Column(Modifier.fillMaxWidth().padding(spacing.l), verticalArrangement = Arrangement.spacedBy(spacing.m)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            rows.forEach { (label, fraction, valStr) ->
                val barColor = when { fraction > 0.7 -> colors.danger; fraction > 0.4 -> colors.attention; else -> colors.protected }
                // Label above the bar, so long app names and 200 % font are not cut to a few letters.
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(valStr, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    }
                    Box(Modifier.fillMaxWidth().height(spacing.s).clip(CircleShape).background(colors.surfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f)).clip(CircleShape).background(barColor))
                    }
                }
            }
        }
    }
}

// ── DETAIL SCREEN ─────────────────────────────────────────────────────────────
@Composable
internal fun DetailScreen(log: PayloadLogEntity, onBack: () -> Unit) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val riskScore = remember(log.id) { computeRiskScore(log) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.mitm_tab_payload), stringResource(R.string.mitm_tab_headers),
        stringResource(R.string.mitm_tab_risk), stringResource(R.string.mitm_tab_fields),
    )

    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(
            title = log.sniHostname ?: log.destinationIp,
            subtitle = log.urlPath ?: "/",
            onBack = onBack,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            contentPadding = PaddingValues(horizontal = spacing.screen),
            modifier = Modifier.padding(bottom = spacing.s),
        ) {
            item { MethodBadge(log.method) }
            val rc = when { riskScore >= 60 -> colors.danger; riskScore >= 40 -> colors.attention; else -> colors.protected }
            item { MetaCell(stringResource(R.string.mitm_tab_risk), "$riskScore", rc) }
            item { MetaCell(stringResource(R.string.mitm_dir), log.direction.take(3), colors.onSurface) }
            item { MetaCell(stringResource(R.string.mitm_proto), log.protocol.take(5), colors.onSurface) }
            item { MetaCell(stringResource(R.string.mitm_size), fmtBytes(log.sizeBytes), colors.onSurface) }
        }

        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = colors.background,
            contentColor = colors.primary,
            edgePadding = spacing.s,
            divider = { HorizontalDivider(color = colors.outline.copy(alpha = 0.3f)) },
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    selectedContentColor = colors.primary,
                    unselectedContentColor = colors.onSurfaceMuted,
                    text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }

        when (selectedTab) {
            0 -> PayloadTab(log)
            1 -> HeadersTab(log)
            2 -> RiskTab(log, riskScore)
            3 -> FieldsTab(log)
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String, valueColor: Color) {
    Surface(color = CoreLockTheme.colors.surfaceVariant, shape = CoreLockTheme.shapes.small) {
        Column(Modifier.padding(horizontal = CoreLockTheme.spacing.m, vertical = CoreLockTheme.spacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = CoreLockTheme.colors.onSurfaceMuted)
            Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = CoreLockTheme.colors.onSurfaceMuted,
        modifier = Modifier.padding(bottom = CoreLockTheme.spacing.s),
    )
}

/** A monospace block for bodies and headers; monospace is for technical values only (§2.2). */
@Composable
private fun CodeBlock(text: AnnotatedString, color: Color) {
    Surface(
        Modifier.fillMaxWidth(),
        color = CoreLockTheme.colors.background,
        shape = CoreLockTheme.shapes.small,
        border = BorderStroke(1.dp, CoreLockTheme.colors.outline.copy(alpha = 0.5f)),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(CoreLockTheme.spacing.m),
            color = color,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// ── PAYLOAD TAB ───────────────────────────────────────────────────────────────
@Composable
private fun PayloadTab(log: PayloadLogEntity) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    var fmt by rememberSaveable { mutableStateOf("json") }
    val hasBody = !log.body.isNullOrBlank()
    val notDecryptedReason = log.notDecryptedReasonRes()?.let { stringResource(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screen)) {
        SectionLabel(stringResource(if (log.direction == "OUTBOUND") R.string.mitm_request_body else R.string.mitm_response_body))

        // HTTPS that was NOT decrypted → explain why
        if (notDecryptedReason != null) {
            Surface(
                Modifier.fillMaxWidth().padding(bottom = spacing.m),
                color = colors.surfaceVariant,
                shape = CoreLockTheme.shapes.card,
            ) {
                Row(Modifier.padding(spacing.m), horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.onSurfaceMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(stringResource(R.string.mitm_not_decrypted), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(
                            stringResource(R.string.mitm_not_decrypted_detail, notDecryptedReason, shortAppName(LocalContext.current, log.ownerPackage), log.sniHostname ?: log.destinationIp, fmtBytes(log.sizeBytes)),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }

        // Format selector (only when there is a body)
        if (hasBody) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s), modifier = Modifier.padding(bottom = spacing.s)) {
                listOf("JSON", "RAW", "HEX").forEach { f ->
                    TokenChip(f, fmt.uppercase() == f) { fmt = f.lowercase() }
                }
            }
        }

        val body = log.body
        val noBodyNotDecrypted = stringResource(R.string.mitm_no_body_not_decrypted)
        val noBodyMethod = stringResource(R.string.mitm_no_body_method, log.method.orEmpty())
        val noBody = stringResource(R.string.mitm_no_body)
        val displayText = remember(body, fmt, notDecryptedReason) {
            when {
                body.isNullOrBlank() -> when {
                    notDecryptedReason != null -> noBodyNotDecrypted
                    log.method != null -> noBodyMethod
                    else -> noBody
                }
                fmt == "hex" -> toHexDump(body)
                fmt == "raw" -> body
                else -> tryPrettyJson(body)
            }
        }
        val palette = JsonPalette(key = colors.primary, string = colors.onSurface, number = colors.attention, literal = colors.onSurfaceMuted)
        val annotated = remember(displayText, fmt, palette) {
            if (fmt == "json" && hasBody) syntaxHighlight(displayText, palette)
            else AnnotatedString(displayText)
        }
        CodeBlock(annotated, if (hasBody) colors.onSurface else colors.onSurfaceMuted)

        Spacer(Modifier.height(spacing.l))
        SectionLabel(stringResource(R.string.mitm_tab_headers))
        val prettyHeaders = remember(log.headers) { tryPrettyJson(log.headers) }
        CodeBlock(AnnotatedString(prettyHeaders.ifBlank { stringResource(R.string.mitm_none) }), colors.onSurfaceMuted)
        Spacer(Modifier.height(spacing.xxxl))
    }
}

internal fun tryPrettyJson(s: String): String =
    runCatching { org.json.JSONObject(s).toString(2) }
        .recoverCatching { org.json.JSONArray(s).toString(2) }
        .getOrDefault(s)

internal data class JsonPalette(val key: Color, val string: Color, val number: Color, val literal: Color)

/**
 * Colours a JSON text. Linear: it compares in place (startsWith at an index) instead of
 * copying the rest of the text at every character, which on a 32 KB body was ~500 M
 * character copies and froze the detail screen.
 */
internal fun syntaxHighlight(json: String, p: JsonPalette): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < json.length) {
        val c = json[i]
        when {
            c == '"' -> {
                val start = i++
                while (i < json.length && !(json[i] == '"' && json[i - 1] != '\\')) i++
                i = minOf(i + 1, json.length) // past the closing quote
                var j = i
                while (j < json.length && json[j].isWhitespace()) j++
                val isKey = j < json.length && json[j] == ':'
                withStyle(SpanStyle(color = if (isKey) p.key else p.string)) { append(json, start, i) }
            }
            json.startsWith("true", i) -> { withStyle(SpanStyle(color = p.literal)) { append("true") }; i += 4 }
            json.startsWith("false", i) -> { withStyle(SpanStyle(color = p.literal)) { append("false") }; i += 5 }
            json.startsWith("null", i) -> { withStyle(SpanStyle(color = p.literal)) { append("null") }; i += 4 }
            c.isDigit() || (c == '-' && i + 1 < json.length && json[i + 1].isDigit()) -> {
                val start = i; if (c == '-') i++
                while (i < json.length && (json[i].isDigit() || json[i] in ".eE+-")) i++
                withStyle(SpanStyle(color = p.number)) { append(json, start, i) }
            }
            else -> { append(c); i++ }
        }
    }
}

internal fun toHexDump(s: String): String {
    val sb = StringBuilder()
    val bytes = s.toByteArray()
    for (i in bytes.indices step 16) {
        val chunk = bytes.slice(i until minOf(i + 16, bytes.size))
        val hex = chunk.joinToString(" ") { "%02x".format(it) }
        val ascii = chunk.joinToString("") { if (it.toInt() in 32..126) it.toInt().toChar().toString() else "." }
        sb.appendLine("${"%04x".format(i)}  $hex  $ascii")
    }
    return sb.toString().trimEnd()
}

// ── HEADERS TAB ───────────────────────────────────────────────────────────────
private enum class HeaderSeverity { OK, WARN, BAD }

@Composable
private fun HeadersTab(log: PayloadLogEntity) {
    val headers = remember(log.id) { parseHeadersMap(log.headers) }
    val sensitiveKeys = remember { setOf("authorization", "cookie", "x-auth", "x-api-key", "x-device", "x-forwarded-for") }
    val warnKeys = remember { setOf("user-agent", "x-session", "x-request-id", "x-client", "x-app") }
    val spacing = CoreLockTheme.spacing

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        item { SectionLabel(stringResource(R.string.mitm_headers_count, headers.size)) }
        if (headers.isEmpty()) {
            item { Text(stringResource(R.string.mitm_no_headers), style = MaterialTheme.typography.bodyMedium, color = CoreLockTheme.colors.onSurfaceMuted) }
        }
        items(headers.entries.toList(), key = { "header:${it.key}" }) { (key, value) ->
            val kl = key.lowercase()
            val severity = when {
                sensitiveKeys.any { kl.contains(it) } -> HeaderSeverity.BAD
                warnKeys.any { kl.contains(it) } -> HeaderSeverity.WARN
                else -> HeaderSeverity.OK
            }
            HeaderItem(key, value, severity)
        }
        item { Spacer(Modifier.height(spacing.xl)) }
    }
}

@Composable
private fun HeaderItem(name: String, value: String, severity: HeaderSeverity) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    CardSurface {
        Column(Modifier.padding(horizontal = spacing.m, vertical = spacing.s), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.weight(1f, fill = false))
                when (severity) {
                    HeaderSeverity.BAD -> Badge(stringResource(R.string.mitm_sev_bad), colors.dangerContainer, colors.onDangerContainer)
                    HeaderSeverity.WARN -> Badge(stringResource(R.string.mitm_sev_warn), colors.attentionContainer, colors.onAttentionContainer)
                    HeaderSeverity.OK -> Unit
                }
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
    }
}

// ── RISK TAB ──────────────────────────────────────────────────────────────────
private enum class RiskLevel { CRIT, WARN, INFO }

@Composable
private fun RiskTab(log: PayloadLogEntity, riskScore: Int) {
    val context = LocalContext.current
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val risks = remember(log.id) { buildRisks(context, log) }
    val riskColor = when { riskScore >= 80 -> colors.danger; riskScore >= 50 -> colors.attention; else -> colors.protected }
    val track = colors.surfaceVariant
    val levelText = stringResource(when { riskScore >= 80 -> R.string.mitm_risk_high; riskScore >= 50 -> R.string.mitm_risk_medium; else -> R.string.mitm_risk_low })
    val crit = risks.count { it.first == RiskLevel.CRIT }
    val warn = risks.count { it.first == RiskLevel.WARN }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.screen), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        item {
            CardSurface {
                Row(Modifier.padding(spacing.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.l)) {
                    // Risk ring; TalkBack reads the score and the level, not the drawing.
                    Box(
                        Modifier.size(72.dp).semantics { contentDescription = "$riskScore, $levelText" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                            val inset = Offset(3.dp.toPx(), 3.dp.toPx())
                            val sz = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx())
                            drawArc(track, -90f, 360f, false, style = stroke, topLeft = inset, size = sz)
                            drawArc(riskColor, -90f, 360f * (riskScore / 100f), false, style = stroke, topLeft = inset, size = sz)
                        }
                        Text("$riskScore", style = MaterialTheme.typography.titleLarge, color = riskColor)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(levelText, style = MaterialTheme.typography.titleMedium, color = riskColor)
                        Text(
                            stringResource(
                                R.string.mitm_risk_counts,
                                pluralStringResource(R.plurals.mitm_critical_count, crit, crit),
                                pluralStringResource(R.plurals.mitm_warning_count, warn, warn),
                            ),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }
        items(risks) { (level, title, body) -> RiskAnomalyItem(level, title, body) }
        item { Spacer(Modifier.height(spacing.xl)) }
    }
}

private fun buildRisks(context: android.content.Context, log: PayloadLogEntity): List<Triple<RiskLevel, String, String>> {
    fun s(id: Int, vararg a: Any) = context.getString(id, *a)
    val risks = mutableListOf<Triple<RiskLevel, String, String>>()
    for (leak in leaksOf(log))
        risks += Triple(RiskLevel.CRIT, s(R.string.mitm_r_leak, leakLabel(context, leak)), leakDescription(context, leak))
    val hKeys = parseHeadersMap(log.headers).keys.map { it.lowercase() }
    if (hKeys.any { it.contains("authorization") })
        risks += Triple(RiskLevel.WARN, s(R.string.mitm_r_auth), s(R.string.mitm_r_auth_desc))
    if (hKeys.any { it.contains("x-device") || it.contains("device-id") })
        risks += Triple(RiskLevel.CRIT, s(R.string.mitm_r_device), s(R.string.mitm_r_device_desc))
    if (hKeys.any { it.contains("cookie") })
        risks += Triple(RiskLevel.WARN, s(R.string.mitm_r_cookie), s(R.string.mitm_r_cookie_desc))
    if (!log.body.isNullOrBlank() && log.body.length > 500)
        risks += Triple(RiskLevel.INFO, s(R.string.mitm_r_large, fmtBytes(log.body.length)), s(R.string.mitm_r_large_desc, log.body.length))
    if (log.method == "POST" || log.method == "PUT")
        risks += Triple(RiskLevel.INFO, s(R.string.mitm_r_submit, log.method.orEmpty()), s(R.string.mitm_r_submit_desc, log.sniHostname ?: log.destinationIp))
    if (log.protocol == "HTTP1" && log.destinationPort != 443)
        risks += Triple(RiskLevel.CRIT, s(R.string.mitm_r_http), s(R.string.mitm_r_http_desc))
    log.notDecryptedReasonRes()?.let { context.getString(it) }?.let {
        risks += Triple(RiskLevel.INFO, s(R.string.mitm_r_not_decrypted), it)
    }
    if (risks.isEmpty())
        risks += Triple(RiskLevel.INFO, s(R.string.mitm_r_clean), s(R.string.mitm_r_clean_desc))
    return risks
}

@Composable
private fun RiskAnomalyItem(level: RiskLevel, title: String, body: String) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val (icon, tint, badge) = when (level) {
        RiskLevel.CRIT -> Triple(Icons.Rounded.Warning, colors.danger, R.string.mitm_level_crit)
        RiskLevel.WARN -> Triple(Icons.Rounded.Info, colors.attention, R.string.mitm_sev_warn)
        RiskLevel.INFO -> Triple(Icons.Rounded.CheckCircle, colors.onSurfaceMuted, R.string.mitm_level_info)
    }
    val (badgeBg, badgeFg) = when (level) {
        RiskLevel.CRIT -> colors.dangerContainer to colors.onDangerContainer
        RiskLevel.WARN -> colors.attentionContainer to colors.onAttentionContainer
        RiskLevel.INFO -> colors.surfaceVariant to colors.onSurface
    }
    CardSurface {
        Row(Modifier.padding(spacing.m), horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
                    Badge(stringResource(badge), badgeBg, badgeFg)
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
            }
        }
    }
}

// ── FIELDS TAB ────────────────────────────────────────────────────────────────
@Composable
private fun FieldsTab(log: PayloadLogEntity) {
    val context = LocalContext.current
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val unknown = stringResource(R.string.mitm_unknown_app)
    val none = stringResource(R.string.mitm_none)
    val noneFound = stringResource(R.string.mitm_none_found)
    val fields = remember(log.id, colors) {
        buildList {
            add(Triple("package", log.ownerPackage ?: unknown, colors.protected))
            add(Triple("sni_hostname", log.sniHostname ?: none, colors.primary))
            add(Triple("destination_ip", log.destinationIp, colors.onSurface))
            add(Triple("destination_port", "${log.destinationPort}", colors.onSurface))
            add(Triple("protocol", log.protocol, colors.onSurface))
            if (!log.method.isNullOrBlank()) add(Triple("method", log.method, colors.attention))
            if (!log.urlPath.isNullOrBlank()) add(Triple("url_path", log.urlPath, colors.primary))
            add(Triple("direction", log.direction, if (log.direction == "OUTBOUND") colors.attention else colors.protected))
            add(Triple("size_bytes", "${log.sizeBytes} (${fmtBytes(log.sizeBytes)})", colors.onSurface))
            add(Triple("body_encoding", log.bodyEncoding, colors.onSurfaceMuted))
            add(Triple("personal_data", leaksOf(log).joinToString { leakLabel(context, it) }.ifEmpty { noneFound },
                if (log.piiRedacted) colors.danger else colors.protected))
            add(Triple("mitm_success", "${log.isMitmSuccess}", if (log.isMitmSuccess) colors.protected else colors.danger))
            add(Triple("session_id", log.sessionId.take(36), colors.onSurfaceMuted))
            add(Triple("timestamp", android.text.format.DateUtils.formatDateTime(context, log.timestamp, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR), colors.onSurfaceMuted))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.screen)) {
        item { SectionLabel(stringResource(R.string.mitm_fields_count, fields.size)) }
        items(fields) { (key, value, color) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget).padding(vertical = spacing.s),
                horizontalArrangement = Arrangement.spacedBy(spacing.m),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = spacing.s).size(6.dp).clip(CircleShape).background(color))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(key, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
                    Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface)
                }
            }
            HorizontalDivider(color = colors.outline.copy(alpha = 0.3f))
        }
        item { Spacer(Modifier.height(spacing.xl)) }
    }
}
