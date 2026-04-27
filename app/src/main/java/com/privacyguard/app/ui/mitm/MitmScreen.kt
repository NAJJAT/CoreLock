package com.privacyguard.ui.mitm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import com.privacyguard.vpn.mitm.CaManager
import java.text.SimpleDateFormat
import java.util.*

// ── COLORS ──────────────────────────────────────────────────────────────────
private val Bg       = Color(0xFF080B10)
private val Bg1      = Color(0xFF0D1117)
private val Bg2      = Color(0xFF111720)
private val Bg3      = Color(0xFF19212E)
private val LineCol  = Color(0xFF1A2030)
private val Ac       = Color(0xFF00F5C4)
private val AcDim    = Color(0x1500F5C4)
private val Red      = Color(0xFFFF4560)
private val RedDim   = Color(0x20FF4560)
private val Amber    = Color(0xFFFFB300)
private val AmberDim = Color(0x1DFFB300)
private val Blue     = Color(0xFF3D9EFF)
private val Purple   = Color(0xFF9D7AFF)
private val TxP      = Color(0xFFDDE3F0)
private val TxS      = Color(0xFF7A87A3)
private val TxM      = Color(0xFF48566A)

// ── HELPERS ─────────────────────────────────────────────────────────────────
internal fun computeRiskScore(log: PayloadLogEntity): Int {
    var score = 0
    if (log.piiRedacted) score += 50
    if (log.method == "POST" || log.method == "PUT") score += 10
    val h = log.headers.lowercase()
    if (h.contains("authorization")) score += 20
    if (h.contains("cookie")) score += 15
    if (h.contains("x-device") || h.contains("device-id")) score += 15
    if (!log.body.isNullOrBlank() && log.body.length > 500) score += 5
    return score.coerceIn(0, 99)
}

private fun isLogFlagged(log: PayloadLogEntity): Boolean =
    log.piiRedacted || computeRiskScore(log) >= 60

private fun parseHeadersMap(json: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    try {
        val obj = org.json.JSONObject(json)
        for (k in obj.keys()) map[k] = obj.optString(k)
    } catch (_: Exception) {}
    return map
}

private fun shortAppName(pkg: String?): String {
    if (pkg.isNullOrBlank()) return "Unknown"
    return when {
        pkg.contains("tiktok", ignoreCase = true)    -> "TikTok"
        pkg.contains("instagram", ignoreCase = true) -> "Instagram"
        pkg.contains("whatsapp", ignoreCase = true)  -> "WhatsApp"
        pkg.contains("snapchat", ignoreCase = true)  -> "Snapchat"
        pkg.contains("facebook", ignoreCase = true)  -> "Facebook"
        pkg.contains("spotify", ignoreCase = true)   -> "Spotify"
        pkg.contains("youtube", ignoreCase = true)   -> "YouTube"
        pkg.contains("twitter", ignoreCase = true) ||
                pkg.contains(".x.", ignoreCase = true) -> "Twitter/X"
        pkg.contains("chrome", ignoreCase = true) ||
                pkg.contains("browser", ignoreCase = true) -> "Browser"
        else -> pkg.split(".").filter { it.length > 2 }
            .lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
    }
}

private fun fmtBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
}

private fun fmtRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "${diff / 1_000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        else -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
    }
}

// ── NAV STATE ───────────────────────────────────────────────────────────────
private sealed class PScreen {
    object List : PScreen()
    object Analytics : PScreen()
    data class Detail(val log: PayloadLogEntity) : PScreen()
}

// ── MAIN ENTRY ───────────────────────────────────────────────────────────────
@Composable
fun MitmScreen() {
    val context = LocalContext.current
    val mitmConfig  = remember { MitmConfig(context) }
    val caManager   = remember { CaManager(context) }          // shared with CaptureListScreen
    val database    = remember { AppDatabase.getInstance(context) }
    val repository: PayloadLogRepository = remember { PayloadLogRepositoryImpl(database.payloadLogDao()) }
    val payloadShipper = remember { PayloadShipper(mitmConfig) }
    val vm: MitmViewModel = viewModel(factory = MitmViewModelFactory(mitmConfig, repository, payloadShipper))
    val uiState by vm.uiState.collectAsState()
    var screen by remember { mutableStateOf<PScreen>(PScreen.List) }
    var showConsentDialog by remember { mutableStateOf(false) }

    // Show consent dialog whenever MITM is toggled on without prior consent.
    LaunchedEffect(uiState.isEnabled, uiState.isConsentValid) {
        if (uiState.isEnabled && !uiState.isConsentValid) showConsentDialog = true
    }

    // After the user accepts consent the ViewModel emits one installCaEvent.
    // We initialize the CA (generates it if this is the first run) and immediately
    // launch the system certificate installer — the user just sees:
    //   "Name the certificate: PrivacyGuard CA  [OK]"
    // and taps OK. No manual navigation needed.
    LaunchedEffect(Unit) {
        vm.installCaEvent.collect {
            caManager.initialize()                                  // generates CA if absent
            val helper = CaInstallHelper(context, caManager)
            val intent = helper.getKeyChainInstallIntent()          // system dialog, no file write
                ?: helper.getFileInstallIntent()                    // fallback: Downloads + ACTION_VIEW
            intent?.let { context.startActivity(it) }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = screen) {
            is PScreen.List -> CaptureListScreen(
                uiState = uiState, vm = vm, caManager = caManager,
                onItemClick = { log -> screen = PScreen.Detail(log) },
                onStatsClick = { screen = PScreen.Analytics }
            )
            is PScreen.Analytics -> AnalyticsScreen(
                logs = uiState.recentLogs,
                onBack = { screen = PScreen.List }
            )
            is PScreen.Detail -> DetailScreen(
                log = s.log,
                onBack = { screen = PScreen.List }
            )
        }

        if (showConsentDialog) {
            AlertDialog(
                onDismissRequest = { showConsentDialog = false },
                containerColor = Bg2,
                title = { Text("Enable Traffic Inspection", color = Ac, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "PrivacyGuard will intercept and log your HTTPS traffic so you can " +
                        "inspect every request and response on this device.\n\n" +
                        "After you confirm, the app will ask you to install a CA certificate. " +
                        "This is required to decrypt HTTPS. The certificate stays on your device " +
                        "and can be removed at any time from Settings → Security → Certificates.",
                        color = TxS, fontSize = 13.sp, lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.recordConsent(); showConsentDialog = false }) {
                        Text("Confirm & Install Certificate", color = Ac, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConsentDialog = false }) {
                        Text("Cancel", color = TxS)
                    }
                }
            )
        }
    }
}

// ── CAPTURE LIST SCREEN ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureListScreen(
    uiState: MitmUiState,
    vm: MitmViewModel,
    caManager: CaManager,
    onItemClick: (PayloadLogEntity) -> Unit,
    onStatsClick: () -> Unit,
) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Bg1)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (uiState.isEnabled) Ac else TxM))
                Text("PRIVACYGUARD", color = Ac, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                Surface(color = AcDim, shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, Ac.copy(alpha = 0.3f))) {
                    Text("PAYLOAD", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Ac, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // CA certificate install button
                IconButton(onClick = {
                    val helper = CaInstallHelper(context, caManager)
                    // Prefer KeyChain (no file write) → file install → share
                    val intent = helper.getKeyChainInstallIntent()
                        ?: helper.getFileInstallIntent()
                        ?: helper.getShareIntent()
                    intent?.let { context.startActivity(it) }
                }) {
                    Icon(Icons.Default.Lock, null, tint = Ac, modifier = Modifier.size(20.dp))
                }

                Switch(
                    checked = uiState.isEnabled,
                    onCheckedChange = { on -> if (on) vm.enableMitm() else vm.disableMitm() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Bg, checkedTrackColor = Ac,
                        uncheckedThumbColor = TxM, uncheckedTrackColor = Bg3
                    )
                )
                IconButton(onClick = onStatsClick) {
                    Icon(Icons.Default.BarChart, null, tint = TxS, modifier = Modifier.size(20.dp))
                }
            }
        }

        HorizontalDivider(color = LineCol)

        // Filter chips
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allActive = uiState.methodFilter == null && !uiState.showFlaggedOnly
            FilterPill("All", allActive) { vm.setMethodFilter(null); vm.setShowFlaggedOnly(false) }
            FilterPill("POST", uiState.methodFilter == "POST") { vm.setMethodFilter(if (uiState.methodFilter == "POST") null else "POST") }
            FilterPill("GET", uiState.methodFilter == "GET") { vm.setMethodFilter(if (uiState.methodFilter == "GET") null else "GET") }
            FilterPill("⚑ Flagged", uiState.showFlaggedOnly, accent = Red) { vm.setShowFlaggedOnly(!uiState.showFlaggedOnly) }
            FilterPill("Has Body", uiState.filterHasBody, accent = Amber) { vm.setFilterHasBody(!uiState.filterHasBody) }
        }

        // Search
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Search app, host, domain…", color = TxM, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TxM, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ac.copy(alpha = 0.45f),
                unfocusedBorderColor = LineCol,
                focusedContainerColor = Bg2,
                unfocusedContainerColor = Bg2,
                focusedTextColor = TxP, unfocusedTextColor = TxP,
                cursorColor = Ac
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        )

        // MITM status banner
        if (uiState.isEnabled && uiState.mitmStatus !in listOf("IDLE", "ACTIVE")) {
            val bc = when (uiState.mitmStatus) { "ERROR", "HANDSHAKE_FAILED" -> Red; "PINNED_BYPASS" -> Amber; else -> Blue }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = bc.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, bc.copy(alpha = 0.3f))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(uiState.mitmStatus, color = bc, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(uiState.mitmStatusMessage, color = TxS, fontSize = 11.sp)
                    uiState.mitmStatusDomain?.let { Text("Domain: $it", color = TxM, fontSize = 10.sp) }
                }
            }
        }

        // CA card — shown only when CA is not yet installed (helper checks quickly).
        if (uiState.isEnabled) {
            CaInstallCard(context = context, caManager = caManager,
                onInstall = {
                    val helper = CaInstallHelper(context, caManager)
                    val intent = helper.getKeyChainInstallIntent()
                        ?: helper.getFileInstallIntent()
                    intent?.let { context.startActivity(it) }
                }
            )
        }

        // List or empty
        if (uiState.recentLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.NetworkCheck, null, tint = TxM, modifier = Modifier.size(56.dp))
                    Text(if (uiState.isEnabled) "No traffic captured yet" else "MITM interception disabled", color = TxS, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (uiState.isEnabled) "Browse HTTPS sites to see payloads" else "Toggle the switch above to enable", color = TxM, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.recentLogs, key = { it.id }) { log ->
                    PayloadListItem(log = log, onClick = { onItemClick(log) })
                    HorizontalDivider(color = LineCol, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ── CA INSTALL CARD ───────────────────────────────────────────────────────────
@Composable
private fun CaInstallCard(
    context: android.content.Context,
    caManager: CaManager,
    onInstall: () -> Unit,
) {
    val helper = remember(context, caManager) { CaInstallHelper(context, caManager) }
    val caReady = remember { helper.isCaGenerated() }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = if (caReady) Amber.copy(alpha = 0.08f) else Red.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (caReady) Amber.copy(alpha = 0.35f) else Red.copy(alpha = 0.35f)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = if (caReady) Amber else Red,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (caReady) "Install CA to decrypt HTTPS" else "CA not generated",
                    color = if (caReady) Amber else Red,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (caReady)
                        "Tap to install the PrivacyGuard CA on this device. " +
                        "After installing, HTTPS request/response bodies will be visible."
                    else
                        "Enable the VPN first — the CA is generated on first start.",
                    color = TxS, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
            if (caReady) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Primary: KeyChain install (best UX)
                    Surface(
                        onClick = onInstall,
                        color = Amber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "Install",
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    // Secondary: save file to Downloads
                    Surface(
                        onClick = {
                            val result = helper.exportCaToDownloads()
                            if (result != null) {
                                // Also try to open it
                                val intent = helper.getFileInstallIntent()
                                intent?.let { context.startActivity(it) }
                            }
                        },
                        color = Bg3,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, LineCol)
                    ) {
                        Text(
                            "Save .crt",
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = TxS, fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FilterPill(label: String, selected: Boolean, accent: Color = Ac, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else Bg3,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.4f) else LineCol)
    ) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = if (selected) accent else TxS, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PayloadListItem(log: PayloadLogEntity, onClick: () -> Unit) {
    val riskScore = remember(log.id) { computeRiskScore(log) }
    val flagged = remember(log.id) { isLogFlagged(log) }
    val dotColor = when { flagged || riskScore >= 80 -> Red; riskScore >= 50 -> Amber; else -> Ac }
    val appName = remember(log.ownerPackage) { shortAppName(log.ownerPackage) }

    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind { if (flagged) drawRect(Red, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = if (flagged) 19.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(appName, color = TxP, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (log.method != null) {
                    MethodBadge(log.method)
                } else {
                    val proto = if (log.destinationPort == 443) "HTTPS" else log.protocol.take(5)
                    Surface(color = Blue.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                        Text(proto, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (log.destinationPort == 443 && log.body.isNullOrBlank()) {
                    Icon(Icons.Default.Lock, null, tint = TxM, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${log.sniHostname ?: log.destinationIp}${log.urlPath?.take(32) ?: ""}",
                color = TxS, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (log.direction == "OUTBOUND") "↑" else "↓",
                color = if (log.direction == "OUTBOUND") Amber else Ac, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(fmtBytes(log.sizeBytes), color = TxM, fontSize = 10.sp)
            Text(fmtRelative(log.timestamp), color = TxM, fontSize = 9.sp)
        }
    }
}

@Composable
private fun MethodBadge(method: String?) {
    if (method == null) return
    val (bg, fg) = when (method.uppercase()) {
        "POST"   -> AmberDim to Amber
        "PUT"    -> Purple.copy(alpha = 0.15f) to Purple
        "DELETE" -> RedDim to Red
        "GET"    -> AcDim to Ac
        else     -> Bg3 to TxS
    }
    Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
        Text(method.uppercase(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

// ── ANALYTICS SCREEN ─────────────────────────────────────────────────────────
@Composable
private fun AnalyticsScreen(logs: List<PayloadLogEntity>, onBack: () -> Unit) {
    val flaggedCount = remember(logs) { logs.count { isLogFlagged(it) } }
    val warnCount = remember(logs) { logs.count { computeRiskScore(it) in 50..79 } }
    val cleanCount = remember(logs) { logs.count { computeRiskScore(it) < 30 } }
    val totalKb = remember(logs) { logs.sumOf { it.sizeBytes } / 1_024 }

    val appRisks = remember(logs) {
        logs.groupBy { shortAppName(it.ownerPackage) }
            .mapValues { (_, v) -> v.maxOf { computeRiskScore(it) } }
            .entries.sortedByDescending { it.value }.take(6)
    }
    val total = logs.size.coerceAtLeast(1)
    val protocolRows = remember(logs) {
        listOf(
            Triple("HTTP/1.x", logs.count { it.protocol == "HTTP1" }.toDouble() / total, "${logs.count { it.protocol == "HTTP1" }}"),
            Triple("HTTP/2", logs.count { it.protocol == "HTTP2" }.toDouble() / total, "${logs.count { it.protocol == "HTTP2" }}"),
            Triple("Outbound", logs.count { it.direction == "OUTBOUND" }.toDouble() / total, "${logs.count { it.direction == "OUTBOUND" }}"),
            Triple("Inbound", logs.count { it.direction == "INBOUND" }.toDouble() / total, "${logs.count { it.direction == "INBOUND" }}"),
        ).filter { it.second > 0 }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Bg1).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ac) }
            Text("Analytics", color = TxP, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = LineCol)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = Bg2, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LineCol)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${logs.size}", color = Ac, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 52.sp)
                    Text("Total requests captured", color = TxS, fontSize = 12.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "$flaggedCount", "Flagged", Red)
                StatCard(Modifier.weight(1f), "$warnCount", "Warnings", Amber)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "$cleanCount", "Clean", Ac)
                StatCard(Modifier.weight(1f), "$totalKb KB", "Total Size", Blue)
            }

            if (appRisks.isNotEmpty()) {
                BarChartCard("Risk by app", appRisks.map { Triple(it.key, it.value / 100.0, "${it.value}") })
            }
            if (protocolRows.isNotEmpty()) {
                BarChartCard("Traffic breakdown", protocolRows)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String, valueColor: Color) {
    Surface(modifier, color = Bg2, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, LineCol)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(label, color = TxM, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }
    }
}

@Composable
private fun BarChartCard(title: String, rows: List<Triple<String, Double, String>>) {
    Surface(color = Bg2, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LineCol)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = TxP, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            rows.forEach { (label, fraction, valStr) ->
                val barColor = when { fraction > 0.7 -> Red; fraction > 0.4 -> Amber; else -> Ac }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(label, color = TxS, fontSize = 11.sp, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Bg3)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp)).background(barColor))
                    }
                    Text(valStr, color = TxM, fontSize = 10.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

// ── DETAIL SCREEN ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(log: PayloadLogEntity, onBack: () -> Unit) {
    val riskScore = remember(log.id) { computeRiskScore(log) }
    val flagged = remember(log.id) { isLogFlagged(log) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Payload", "Headers", "Risk", "Fields")

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Header
        Surface(color = Bg1, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ac) }
                    Text("Captures", color = Ac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MethodBadge(log.method)
                        Text(log.sniHostname ?: log.destinationIp, color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(log.urlPath ?: "/", color = TxS, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rc = when { riskScore >= 60 -> Red; riskScore >= 40 -> Amber; else -> Ac }
                    MetaCell(Modifier.weight(1f), "Risk", "$riskScore", rc)
                    MetaCell(Modifier.weight(1f), "Dir", log.direction.take(3), TxP)
                    MetaCell(Modifier.weight(1f), "Proto", log.protocol.take(5), TxP)
                    MetaCell(Modifier.weight(1f), "Size", fmtBytes(log.sizeBytes), TxP)
                }
            }
        }

        // Tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Bg1,
            contentColor = Ac,
            edgePadding = 0.dp,
            divider = { HorizontalDivider(color = LineCol) }
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = {
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (selectedTab == i) Ac else TxM, letterSpacing = 0.4.sp)
                    }
                )
            }
        }

        // Tab content
        when (selectedTab) {
            0 -> PayloadTab(log)
            1 -> HeadersTab(log)
            2 -> RiskTab(log, riskScore, flagged)
            3 -> FieldsTab(log)
        }
    }
}

@Composable
private fun MetaCell(modifier: Modifier, label: String, value: String, valueColor: Color) {
    Surface(modifier, color = Bg2, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = TxM, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(2.dp))
            Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── PAYLOAD TAB ───────────────────────────────────────────────────────────────
@Composable
private fun PayloadTab(log: PayloadLogEntity) {
    var fmt by remember { mutableStateOf("json") }
    val isHttps = log.destinationPort == 443
    val hasBody = !log.body.isNullOrBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        SectionLabel(if (log.direction == "OUTBOUND") "Request Body" else "Response Body")

        // HTTPS with no body → explain why (encryption)
        if (isHttps && !hasBody) {
            Surface(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = Blue.copy(alpha = 0.09f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Blue.copy(alpha = 0.3f))
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Lock, null, tint = Blue, modifier = Modifier.size(18.dp))
                    Column {
                        Text("TLS Encrypted — body not visible", color = Blue,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This connection uses HTTPS. The payload is encrypted end-to-end " +
                                    "and cannot be read without the PrivacyGuard CA certificate installed " +
                                    "on this device as a trusted authority.\n\n" +
                                    "App: ${log.ownerPackage ?: "unknown"}\n" +
                                    "Host: ${log.sniHostname ?: log.destinationIp}\n" +
                                    "Sent: ${fmtBytes(log.sizeBytes)}",
                            color = TxS, fontSize = 11.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        // Format selector (only when there is a body)
        if (hasBody) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                listOf("JSON", "RAW", "HEX").forEach { f ->
                    FmtButton(f, fmt.uppercase() == f) { fmt = f.lowercase() }
                }
            }
        }

        // Body box
        val body = log.body
        val displayText = remember(body, fmt) {
            when {
                body.isNullOrBlank() -> if (isHttps) "(encrypted — see notice above)" else "(no body)"
                fmt == "hex" -> toHexDump(body)
                fmt == "raw" -> body
                else -> tryPrettyJson(body)
            }
        }
        val annotated = remember(displayText, fmt) {
            if (fmt == "json" && hasBody) syntaxHighlight(displayText)
            else buildAnnotatedString { append(displayText) }
        }

        Surface(Modifier.fillMaxWidth(), color = Bg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, LineCol)) {
            Text(annotated, Modifier.fillMaxWidth().padding(12.dp),
                color = if (hasBody) TxP else TxM,
                fontSize = 10.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Headers")
        Surface(Modifier.fillMaxWidth(), color = Bg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, LineCol)) {
            val prettyHeaders = remember(log.headers) { tryPrettyJson(log.headers) }
            Text(prettyHeaders.ifBlank { "(none)" }, Modifier.fillMaxWidth().padding(12.dp),
                color = TxS, fontSize = 10.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TxM, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun FmtButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) AcDim else Bg3,
        shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, if (selected) Ac.copy(alpha = 0.4f) else LineCol)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = if (selected) Ac else TxS, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

private fun tryPrettyJson(s: String): String {
    return try { org.json.JSONObject(s).toString(2) }
    catch (_: Exception) {
        try { org.json.JSONArray(s).toString(2) }
        catch (_: Exception) { s }
    }
}

private fun syntaxHighlight(json: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val keyColor  = Color(0xFF7DD3FC)
    val strColor  = Color(0xFF86EFAC)
    val numColor  = Color(0xFFFCA5A5)
    val boolColor = Color(0xFF9D7AFF)
    val nullColor = Color(0xFF48566A)
    var i = 0
    while (i < json.length) {
        when {
            json[i] == '"' -> {
                val start = i++
                while (i < json.length && !(json[i] == '"' && json[i - 1] != '\\')) i++
                i++ // closing quote
                val token = json.substring(start, minOf(i, json.length))
                val afterSpaces = json.drop(i).trimStart()
                withStyle(SpanStyle(color = if (afterSpaces.startsWith(":")) keyColor else strColor)) { append(token) }
            }
            json.substring(i).let { it.startsWith("true") } -> {
                withStyle(SpanStyle(color = boolColor)) { append("true") }; i += 4
            }
            json.substring(i).startsWith("false") -> {
                withStyle(SpanStyle(color = boolColor)) { append("false") }; i += 5
            }
            json.substring(i).startsWith("null") -> {
                withStyle(SpanStyle(color = nullColor)) { append("null") }; i += 4
            }
            json[i].isDigit() || (json[i] == '-' && i + 1 < json.length && json[i + 1].isDigit()) -> {
                val start = i; if (json[i] == '-') i++
                while (i < json.length && (json[i].isDigit() || json[i] in ".eE+-")) i++
                withStyle(SpanStyle(color = numColor)) { append(json.substring(start, i)) }
            }
            else -> { append(json[i]); i++ }
        }
    }
}

private fun toHexDump(s: String): String {
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
@Composable
private fun HeadersTab(log: PayloadLogEntity) {
    val headers = remember(log.id) { parseHeadersMap(log.headers) }
    val sensitiveKeys = remember { setOf("authorization", "cookie", "x-auth", "x-api-key", "x-device", "x-forwarded-for") }
    val warnKeys = remember { setOf("user-agent", "x-session", "x-request-id", "x-client", "x-app") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
        item { SectionLabel("Headers (${headers.size})") }
        if (headers.isEmpty()) {
            item { Text("No headers captured", color = TxM, fontSize = 12.sp) }
        }
        items(headers.entries.toList()) { (key, value) ->
            val kl = key.lowercase()
            val severity = when {
                sensitiveKeys.any { kl.contains(it) } -> "bad"
                warnKeys.any { kl.contains(it) }      -> "warn"
                else -> "ok"
            }
            HeaderItem(key, value, severity)
            Spacer(Modifier.height(4.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HeaderItem(name: String, value: String, severity: String) {
    val borderColor = when (severity) { "bad" -> Red; "warn" -> Amber; else -> LineCol }
    Surface(Modifier.fillMaxWidth(), color = Bg2, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, LineCol)) {
        Row {
            Box(Modifier.width(3.dp).defaultMinSize(minHeight = 48.dp).background(borderColor,
                RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(name, color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(value, color = TxP, fontSize = 11.sp, lineHeight = 16.sp)
                if (severity != "ok") {
                    Spacer(Modifier.height(4.dp))
                    Surface(color = borderColor.copy(alpha = 0.14f), shape = RoundedCornerShape(4.dp)) {
                        Text(severity.uppercase(), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            color = borderColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── RISK TAB ──────────────────────────────────────────────────────────────────
@Composable
private fun RiskTab(log: PayloadLogEntity, riskScore: Int, flagged: Boolean) {
    val risks = remember(log.id) { buildRisks(log, riskScore) }
    val riskColor = when { riskScore >= 80 -> Red; riskScore >= 50 -> Amber; else -> Ac }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
        item {
            Surface(Modifier.fillMaxWidth().padding(bottom = 14.dp),
                color = Bg2, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LineCol)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Risk ring
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                            val inset = Offset(3.dp.toPx(), 3.dp.toPx())
                            val sz = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx())
                            drawArc(Bg3, -90f, 360f, false, style = stroke, topLeft = inset, size = sz)
                            drawArc(riskColor, -90f, 360f * (riskScore / 100f), false, style = stroke, topLeft = inset, size = sz)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$riskScore", color = riskColor, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                            Text("RISK", color = TxM, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                    Column {
                        Text(when { riskScore >= 80 -> "High Risk"; riskScore >= 50 -> "Medium Risk"; else -> "Low Risk" },
                            color = riskColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text("${risks.count { it.first == "crit" }} critical · ${risks.count { it.first == "warn" }} warnings",
                            color = TxS, fontSize = 11.sp)
                    }
                }
            }
        }
        items(risks) { (level, title, body) ->
            RiskAnomalyItem(level, title, body)
            Spacer(Modifier.height(8.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun buildRisks(log: PayloadLogEntity, riskScore: Int): List<Triple<String, String, String>> {
    val risks = mutableListOf<Triple<String, String, String>>()
    if (log.piiRedacted)
        risks += Triple("crit", "PII Detected & Redacted", "Personal identifiable information found and redacted before logging.")
    val headers = parseHeadersMap(log.headers)
    val hKeys = headers.keys.map { it.lowercase() }
    if (hKeys.any { it.contains("authorization") })
        risks += Triple("warn", "Authorization Header Present", "Bearer token or credentials transmitted in this request.")
    if (hKeys.any { it.contains("x-device") || it.contains("device-id") })
        risks += Triple("crit", "Device Identifier in Headers", "Device ID being sent to remote — potential tracking vector.")
    if (hKeys.any { it.contains("cookie") })
        risks += Triple("warn", "Session Cookie Transmitted", "Cookie header may contain session tokens or tracking IDs.")
    if (!log.body.isNullOrBlank() && log.body.length > 500)
        risks += Triple("info", "Large Request Body (${fmtBytes(log.body.length)})", "Request body is ${log.body.length} bytes.")
    if (log.method == "POST" || log.method == "PUT")
        risks += Triple("info", "${log.method} Request", "Data is being submitted to ${log.sniHostname ?: log.destinationIp}.")
    if (log.protocol == "HTTP1" && log.destinationPort != 443)
        risks += Triple("crit", "Unencrypted HTTP Traffic", "Data transmitted over plain HTTP without TLS encryption.")
    if (!log.isMitmSuccess)
        risks += Triple("warn", "Partial Capture", "MITM interception did not fully succeed — payload may be incomplete.")
    if (risks.isEmpty())
        risks += Triple("info", "No anomalies detected", "This request appears clean based on available metadata.")
    return risks
}

@Composable
private fun RiskAnomalyItem(level: String, title: String, body: String) {
    val (iconTint, surfaceBg, badgeFg) = when (level) {
        "crit" -> Triple(Red, RedDim, Red)
        "warn" -> Triple(Amber, AmberDim, Amber)
        else   -> Triple(Blue, Blue.copy(alpha = 0.12f), Blue)
    }
    val icon = when (level) { "crit" -> Icons.Default.Warning; "warn" -> Icons.Default.Info; else -> Icons.Default.CheckCircle }
    Surface(Modifier.fillMaxWidth(), color = Bg2, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LineCol)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(32.dp), color = surfaceBg, shape = RoundedCornerShape(10.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = TxP, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Surface(color = surfaceBg, shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.3f))) {
                        Text(level.uppercase(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = badgeFg, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(body, color = TxS, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

// ── FIELDS TAB ────────────────────────────────────────────────────────────────
@Composable
private fun FieldsTab(log: PayloadLogEntity) {
    val fields = remember(log.id) {
        buildList {
            add(Triple("package", log.ownerPackage ?: "unknown", Ac))
            add(Triple("sni_hostname", log.sniHostname ?: "(none)", Blue))
            add(Triple("destination_ip", log.destinationIp, TxP))
            add(Triple("destination_port", "${log.destinationPort}", TxP))
            add(Triple("protocol", log.protocol, TxP))
            if (!log.method.isNullOrBlank()) add(Triple("method", log.method, Amber))
            if (!log.urlPath.isNullOrBlank()) add(Triple("url_path", log.urlPath, Blue))
            add(Triple("direction", log.direction, if (log.direction == "OUTBOUND") Amber else Ac))
            add(Triple("size_bytes", "${log.sizeBytes} (${fmtBytes(log.sizeBytes)})", TxP))
            add(Triple("body_encoding", log.bodyEncoding, TxS))
            add(Triple("pii_redacted", "${log.piiRedacted}", if (log.piiRedacted) Red else Ac))
            add(Triple("mitm_success", "${log.isMitmSuccess}", if (log.isMitmSuccess) Ac else Red))
            add(Triple("session_id", log.sessionId.take(36), TxM))
            add(Triple("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)), TxS))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
        item { SectionLabel("Extracted Fields (${fields.size})") }
        items(fields) { (key, value, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(6.dp).offset(y = 4.dp).clip(CircleShape).background(color.copy(alpha = 0.7f)))
                Column(Modifier.weight(1f)) {
                    Text(key, color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = color, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = LineCol, thickness = 0.5.dp)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// أضف هذه الدالة في أي مكان في الملف
@Composable
private fun CertificateStatusCard(caManager: CaManager) {
    val isInstalled = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // محاولة التحقق من وجود الشهادة
        isInstalled.value = caManager.getCaCert() != null
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = if (isInstalled.value) Ac.copy(alpha = 0.1f) else Red.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (isInstalled.value) Ac.copy(alpha = 0.3f) else Red.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isInstalled.value) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint = if (isInstalled.value) Ac else Red,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isInstalled.value) "✓ CA Certificate Installed" else "✗ CA Certificate NOT Installed",
                color = if (isInstalled.value) Ac else Red,
                fontSize = 11.sp
            )
        }
    }
}