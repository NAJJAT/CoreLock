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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.privacyguard.app.R
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
import com.privacyguard.app.data.db.notDecryptedReason
import com.privacyguard.app.data.db.notDecryptedReasonRes
import com.privacyguard.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.LeakDetector
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.privacyguard.vpn.mitm.CaManager
import com.privacyguard.ui.mitm.CaInstallHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
internal fun computeRiskScore(log: PayloadLogEntity): Int = PayloadRisk.score(log.riskInput())

/** Personal data types [com.privacyguard.vpn.mitm.LeakDetector] found in this request. */
private fun leakLabel(context: android.content.Context, t: LeakDetector.LeakType): String = context.getString(
    when (t) {
        LeakDetector.LeakType.LOCATION -> R.string.leak_location
        LeakDetector.LeakType.ADVERTISING_ID -> R.string.leak_ad_id
        LeakDetector.LeakType.DEVICE_ID -> R.string.leak_device_id
        LeakDetector.LeakType.NETWORK_INFO -> R.string.leak_network
        LeakDetector.LeakType.EMAIL -> R.string.leak_email
        LeakDetector.LeakType.PHONE_NUMBER -> R.string.leak_phone
    }
)

private fun leakDescription(context: android.content.Context, t: LeakDetector.LeakType): String = context.getString(
    when (t) {
        LeakDetector.LeakType.LOCATION -> R.string.leak_location_desc
        LeakDetector.LeakType.ADVERTISING_ID -> R.string.leak_ad_id_desc
        LeakDetector.LeakType.DEVICE_ID -> R.string.leak_device_id_desc
        LeakDetector.LeakType.NETWORK_INFO -> R.string.leak_network_desc
        LeakDetector.LeakType.EMAIL -> R.string.leak_email_desc
        LeakDetector.LeakType.PHONE_NUMBER -> R.string.leak_phone_desc
    }
)

internal fun leaksOf(log: PayloadLogEntity): Set<LeakDetector.LeakType> = PayloadRisk.leaks(log.riskInput())

// The same rule as the "Flagged" filter and the analytics: see PayloadRisk.
private fun isLogFlagged(log: PayloadLogEntity): Boolean = PayloadRisk.isFlagged(log.riskInput())

private fun parseHeadersMap(json: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    try {
        val obj = org.json.JSONObject(json)
        for (k in obj.keys()) map[k] = obj.optString(k)
    } catch (_: Exception) {}
    return map
}

// The installed app's own label (localized by its developer), not a guess from the package name.
private fun shortAppName(context: android.content.Context, pkg: String?): String {
    if (pkg.isNullOrBlank()) return context.getString(R.string.mitm_unknown_app)
    runCatching {
        val pm = context.packageManager
        return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }
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
                pkg.contains("browser", ignoreCase = true) -> context.getString(R.string.mitm_browser)
        else -> pkg.split(".").filter { it.length > 2 }
            .lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
    }
}

// The app-wide byte format (decimal units, user's language).
private fun fmtBytes(bytes: Int): String = com.privacyguard.app.core.utils.ByteFormat.format(bytes.toLong())

// Locale-aware "5 minutes ago" / "5 minuten geleden" / "قبل ٥ دقائق".
private fun fmtRelative(ts: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS,
    ).toString()

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
    // The same shipper the VPN fills; a new one here would always be empty.
    val payloadShipper = remember { PayloadShipper.getInstance(context) }
    val vm: MitmViewModel = viewModel(factory = MitmViewModelFactory(mitmConfig, repository, payloadShipper))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<PScreen>(PScreen.List) }
    var showConsentDialog by remember { mutableStateOf(false) }

    // Show consent dialog whenever MITM is toggled on without prior consent.
    LaunchedEffect(uiState.isEnabled, uiState.isConsentValid) {
        if (uiState.isEnabled && !uiState.isConsentValid) showConsentDialog = true
    }

    val caHelper     = remember { CaInstallHelper(context, caManager) }
    val installScope = rememberCoroutineScope()
    var showScreenLockDialog by remember { mutableStateOf(false) }
    // null = hidden; true = cert saved to Downloads; false = saving failed
    var settingsInstallSaved by remember { mutableStateOf<Boolean?>(null) }

    // Exports the cert to Downloads and shows the "install it from Settings" steps.
    val saveCaForSettings: () -> Unit = {
        installScope.launch {
            if (!caManager.initialize()) { settingsInstallSaved = false; return@launch }
            val saved = withContext(Dispatchers.IO) { caHelper.exportCaToDownloads() } != null
            settingsInstallSaved = saved
        }
    }

    // Android 10 and below: open the system installer directly.
    // Android 11+: the system rejects app-started CA installs, so go via Settings.
    val startCaInstall: () -> Unit = {
        installScope.launch {
            if (!caManager.initialize()) { settingsInstallSaved = false; return@launch }
            if (!caHelper.isScreenLockSet()) { showScreenLockDialog = true; return@launch }
            val intent = caHelper.getInAppInstallIntent()
            val launched = intent != null &&
                runCatching { context.startActivity(intent) }.isSuccess
            if (!launched) saveCaForSettings()
        }
    }

    // After the user accepts consent the ViewModel emits one installCaEvent.
    LaunchedEffect(Unit) {
        vm.installCaEvent.collect { startCaInstall() }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (val s = screen) {
            is PScreen.List -> CaptureListScreen(
                uiState = uiState, vm = vm, caManager = caManager,
                onInstallCa = startCaInstall,
                onSaveCa = saveCaForSettings,
                onItemClick = { log -> screen = PScreen.Detail(log) },
                onStatsClick = { screen = PScreen.Analytics }
            )
            is PScreen.Analytics -> AnalyticsScreen(
                analytics = uiState.analytics,
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
                title = { Text(stringResource(R.string.mitm_consent_title), color = Ac, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.mitm_consent_body),

                        color = TxS, fontSize = 13.sp, lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.recordConsent(); showConsentDialog = false }) {
                        Text(stringResource(R.string.mitm_consent_confirm), color = Ac, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConsentDialog = false }) {
                        Text(stringResource(R.string.mitm_cancel), color = TxS)
                    }
                }
            )
        }

        if (showScreenLockDialog) {
            ScreenLockRequiredDialog(
                onOpenSettings = {
                    showScreenLockDialog = false
                    openSettings(context, caHelper.getSecuritySettingsIntent())
                },
                onDismiss = { showScreenLockDialog = false },
            )
        }

        settingsInstallSaved?.let { saved ->
            SettingsInstallDialog(
                saved = saved,
                fileName = caHelper.exportFileName(),
                onOpenSettings = {
                    settingsInstallSaved = null
                    openSettings(context, caHelper.getInstallFromStorageIntent(), caHelper.getSecuritySettingsIntent())
                },
                onDismiss = { settingsInstallSaved = null },
            )
        }
    }
}

/**
 * Starts the first of [intents] the device has, falling back to the top-level
 * Settings app if the OEM lacks all of those screens.
 */
private fun openSettings(context: android.content.Context, vararg intents: Intent) {
    val opened = intents.any { runCatching { context.startActivity(it) }.isSuccess }
    if (!opened) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ScreenLockRequiredDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg2,
        icon = { Icon(Icons.Default.Lock, null, tint = Amber) },
        title = { Text(stringResource(R.string.mitm_lock_title), color = Amber, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                stringResource(R.string.mitm_lock_body),

                color = TxS, fontSize = 13.sp, lineHeight = 18.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.mitm_open_settings), color = Amber, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mitm_cancel), color = TxS) }
        }
    )
}

/**
 * Android 11+ only lets the Settings app install CA certificates, so we save the
 * cert to Downloads and walk the user through picking it there.
 */
@Composable
private fun SettingsInstallDialog(
    saved: Boolean,
    fileName: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg2,
        icon = { Icon(if (saved) Icons.Default.Download else Icons.Default.ErrorOutline, null,
            tint = if (saved) Ac else Red) },
        title = {
            Text(
                if (saved) stringResource(R.string.mitm_install_title) else stringResource(R.string.mitm_save_failed_title),
                color = if (saved) Ac else Red, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                if (saved)
                    stringResource(R.string.mitm_install_steps, fileName)

                else
                    stringResource(R.string.mitm_save_failed_body),

                color = TxS, fontSize = 13.sp, lineHeight = 18.sp
            )
        },
        confirmButton = {
            if (saved) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.mitm_open_settings), color = Ac, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.mitm_ok), color = TxS) }
            }
        },
        dismissButton = if (saved) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mitm_later), color = TxS) } }
        } else null
    )
}

// ── CAPTURE LIST SCREEN ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureListScreen(
    uiState: MitmUiState,
    vm: MitmViewModel,
    caManager: CaManager,
    onInstallCa: () -> Unit,
    onSaveCa: () -> Unit,
    onItemClick: (PayloadLogEntity) -> Unit,
    onStatsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRemoveCaPrompt by remember { mutableStateOf(false) }

    if (showRemoveCaPrompt) {
        AlertDialog(
            onDismissRequest = { showRemoveCaPrompt = false },
            title = { Text(stringResource(R.string.mitm_remove_ca_title)) },
            text = {
                Text(
                    stringResource(R.string.mitm_remove_ca_body)

                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveCaPrompt = false
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.mitm_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showRemoveCaPrompt = false }) { Text(stringResource(R.string.mitm_later)) } },
        )
    }

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
                    Text(stringResource(R.string.mitm_badge_payload).uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Ac, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // CA certificate install button
                IconButton(onClick = onInstallCa) {
                    Icon(Icons.Default.Lock, null, tint = Ac, modifier = Modifier.size(20.dp))
                }

                Switch(
                    checked = uiState.isEnabled,
                    onCheckedChange = { on ->
                        if (on) {
                            vm.enableMitm()
                        } else {
                            vm.disableMitm()
                            // Turning inspection off retires the CA: delete its key so it can
                            // never sign again, then ask the user to untrust the installed copy.
                            scope.launch { caManager.reset() }
                            showRemoveCaPrompt = true
                        }
                    },
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
            val allActive = uiState.methodFilter == null && !uiState.showFlaggedOnly && !uiState.filterHasBody
            FilterPill(stringResource(R.string.mitm_filter_all), allActive) {
                vm.setMethodFilter(null); vm.setShowFlaggedOnly(false); vm.setFilterHasBody(false)
            }
            FilterPill("POST", uiState.methodFilter == "POST") { vm.setMethodFilter(if (uiState.methodFilter == "POST") null else "POST") }
            FilterPill("GET", uiState.methodFilter == "GET") { vm.setMethodFilter(if (uiState.methodFilter == "GET") null else "GET") }
            FilterPill(stringResource(R.string.mitm_filter_flagged), uiState.showFlaggedOnly, accent = Red) { vm.setShowFlaggedOnly(!uiState.showFlaggedOnly) }
            FilterPill(stringResource(R.string.mitm_filter_body), uiState.filterHasBody, accent = Amber) { vm.setFilterHasBody(!uiState.filterHasBody) }
        }

        // Search
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.mitm_search), color = TxM, fontSize = 12.sp) },
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
                    // Codes come from MitmEngine; the engine's own message is English (logs).
                    val (stateLabel, stateMessage) = when (uiState.mitmStatus) {
                        "STARTING" -> R.string.mitm_st_starting to R.string.mitm_st_starting_desc
                        "TIMEOUT" -> R.string.mitm_st_timeout to R.string.mitm_st_timeout_desc
                        "PINNED_BYPASS" -> R.string.mitm_st_pinned to R.string.mitm_st_pinned_desc
                        "HANDSHAKE_FAILED" -> R.string.mitm_st_handshake to R.string.mitm_st_handshake_desc
                        else -> R.string.mitm_st_error to R.string.mitm_st_error_desc
                    }
                    Text(stringResource(stateLabel).uppercase(), color = bc, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(stateMessage), color = TxS, fontSize = 11.sp)
                    uiState.mitmStatusDomain?.let { Text(stringResource(R.string.mitm_domain, it), color = TxM, fontSize = 10.sp) }
                }
            }
        }

        // CA card — shown only when CA is not yet installed (helper checks quickly).
        if (uiState.isEnabled) {
            CaInstallCard(
                context = context, caManager = caManager,
                onInstall = onInstallCa, onSaveFile = onSaveCa,
            )
            QuicBlockRow(blockQuic = uiState.blockQuic, onToggle = { vm.setBlockQuic(it) })
        }

        // List or empty
        // An empty result of a search or filter is "no match", not "nothing captured".
        val filterActive = uiState.searchQuery.isNotBlank() || uiState.methodFilter != null ||
            uiState.showFlaggedOnly || uiState.filterHasBody || uiState.filterDirection != null
        if (uiState.recentLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.NetworkCheck, null, tint = TxM, modifier = Modifier.size(56.dp))
                    Text(
                        stringResource(when {
                            filterActive -> R.string.mitm_no_match
                            uiState.isEnabled -> R.string.mitm_empty_on
                            else -> R.string.mitm_empty_off
                        }),
                        color = TxS, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(when {
                            filterActive -> R.string.mitm_no_match_desc
                            uiState.isEnabled -> R.string.mitm_empty_on_desc
                            else -> R.string.mitm_empty_off_desc
                        }),
                        color = TxM, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (uiState.listCapped) item {
                    Text(
                        stringResource(R.string.mitm_list_capped, MitmViewModel.LIST_LIMIT),
                        color = TxM, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(uiState.recentLogs, key = { it.id }) { log ->
                    PayloadListItem(log = log, onClick = { onItemClick(log) })
                    HorizontalDivider(color = LineCol, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ── QUIC BLOCK ROW ────────────────────────────────────────────────────────────
@Composable
private fun QuicBlockRow(blockQuic: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = if (blockQuic) Blue.copy(alpha = 0.08f) else Bg2,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (blockQuic) Blue.copy(alpha = 0.35f) else LineCol)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Block, null, tint = if (blockQuic) Blue else TxM, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.mitm_quic),
                    color = if (blockQuic) Blue else TxS,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (blockQuic)
                        stringResource(R.string.mitm_quic_on)
                    else
                        stringResource(R.string.mitm_quic_off),
                    color = TxM, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
            Switch(
                checked = blockQuic,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Bg, checkedTrackColor = Blue,
                    uncheckedThumbColor = TxM, uncheckedTrackColor = Bg3
                )
            )
        }
    }
}

// ── CA INSTALL CARD ───────────────────────────────────────────────────────────
@Composable
private fun CaInstallCard(
    context: android.content.Context,
    caManager: CaManager,
    onInstall: () -> Unit,
    onSaveFile: () -> Unit,
) {
    val helper       = remember(context, caManager) { CaInstallHelper(context, caManager) }
    val installScope = rememberCoroutineScope()
    var caReady      by remember { mutableStateOf(false) }
    var caFailed     by remember { mutableStateOf(false) }
    var caTrusted    by remember { mutableStateOf(false) }
    // Old PrivacyGuard CAs still trusted by Android; their keys are gone, so they are
    // dead weight and make it unclear which certificate is the working one.
    var staleCas     by remember { mutableStateOf(0) }

    // Initialise the CA on first composition so the card reflects real state immediately.
    LaunchedEffect(helper) {
        val ok = caManager.initialize()
        caReady   = ok
        caFailed  = !ok
        caTrusted = ok && helper.isCaTrustedByDevice()
        staleCas  = helper.staleTrustedCaCount()
    }

    // Re-check trust when the user returns to the app (e.g. after the CA install dialog).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (caReady) {
            caTrusted = helper.isCaTrustedByDevice()
            staleCas  = helper.staleTrustedCaCount()
        }
    }

    val cardColor  = when { caTrusted -> Ac; caReady -> Amber; caFailed -> Red; else -> Red }
    val cardBg     = cardColor.copy(alpha = 0.08f)
    val cardBorder = cardColor.copy(alpha = 0.35f)

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = cardBg, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (caTrusted) Icons.Default.VerifiedUser else Icons.Default.Lock,
                contentDescription = null,
                tint = cardColor, modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        caTrusted -> stringResource(R.string.mitm_ca_trusted)
                        caFailed  -> stringResource(R.string.mitm_ca_failed)
                        caReady   -> stringResource(R.string.mitm_ca_ready)
                        else      -> stringResource(R.string.mitm_ca_generating)
                    },
                    color = cardColor, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        caTrusted ->
                            stringResource(R.string.mitm_ca_trusted_desc)

                        caFailed  ->
                            stringResource(R.string.mitm_ca_failed_desc)

                        caReady && helper.canInstallFromApp ->
                            stringResource(R.string.mitm_ca_ready_app_desc)

                        caReady   ->
                            stringResource(R.string.mitm_ca_ready_settings_desc)

                        else      ->
                            stringResource(R.string.mitm_ca_generating_desc)

                    },
                    color = TxS, fontSize = 10.sp, lineHeight = 15.sp
                )
                if (staleCas > 0) {
                    Text(
                        pluralStringResource(R.plurals.mitm_stale_cas, staleCas, staleCas),

                        color = Amber, fontSize = 10.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Surface(
                        onClick = { openSettings(context, helper.getTrustedCredentialsIntent(), helper.getSecuritySettingsIntent()) },
                        color = Amber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.mitm_remove_old),
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (caFailed) {
                    // Regenerate button
                    Surface(
                        onClick = {
                            installScope.launch {
                                caFailed = false
                                caManager.reset()
                                val ok = caManager.initialize()
                                caReady  = ok
                                caFailed = !ok
                            }
                        },
                        color = Red.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Red.copy(alpha = 0.4f))
                    ) {
                        Text(
                            stringResource(R.string.mitm_regenerate),
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                } else if (caReady && !caTrusted) {
                    // In-app installer on Android ≤ 10, Settings walkthrough on 11+.
                    // Trust is re-checked by the ON_RESUME effect when the user returns.
                    Surface(
                        onClick = onInstall,
                        color = Amber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.4f))
                    ) {
                        Text(
                            stringResource(R.string.mitm_install),
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    // Secondary on Android ≤ 10: save to Downloads and install from Settings
                    // (for OEM installers that reject the in-app flow). On 11+ Install
                    // already does exactly this, so the button would be redundant.
                    if (helper.canInstallFromApp) Surface(
                        onClick = onSaveFile,
                        color = Bg3,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, LineCol)
                    ) {
                        Text(
                            stringResource(R.string.mitm_save_crt),
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
    val context = LocalContext.current
    val appName = remember(log.ownerPackage) { shortAppName(context, log.ownerPackage) }

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
            val leaks = remember(log.id) { leaksOf(log) }
            if (leaks.isNotEmpty()) {
                Text(
                    stringResource(R.string.mitm_sends, leaks.joinToString(" · ") { leakLabel(context, it) }),
                    color = Red, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
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
private fun AnalyticsScreen(analytics: PayloadAnalytics, onBack: () -> Unit) {
    // Every stored entry, whatever the list filters say; the four buckets add up to the total.
    val context = LocalContext.current
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

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Bg1).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ac) }
            Text(stringResource(R.string.mitm_analytics), color = TxP, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = LineCol)

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = Bg2, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, LineCol)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.total}", color = Ac, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 52.sp)
                    Text(stringResource(R.string.mitm_total_requests), color = TxS, fontSize = 12.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.FLAGGED)}", stringResource(R.string.mitm_flagged), Red)
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.WARNING)}", stringResource(R.string.mitm_warnings), Amber)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.CLEAN)}", stringResource(R.string.mitm_clean), Ac)
                StatCard(Modifier.weight(1f), "${a.count(PayloadRisk.Bucket.METADATA_ONLY)}", stringResource(R.string.mitm_metadata_only), Blue)
            }
            StatCard(Modifier.fillMaxWidth(), com.privacyguard.app.core.utils.ByteFormat.format(a.totalBytes), stringResource(R.string.mitm_total_size), Blue)

            if (appRisks.isNotEmpty()) {
                BarChartCard(stringResource(R.string.mitm_risk_by_app), appRisks.map { Triple(it.first, it.second / 100.0, "${it.second}") })
            }
            if (protocolRows.isNotEmpty()) {
                BarChartCard(stringResource(R.string.mitm_traffic_breakdown), protocolRows)
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
    val tabs = listOf(stringResource(R.string.mitm_tab_payload), stringResource(R.string.mitm_tab_headers), stringResource(R.string.mitm_tab_risk), stringResource(R.string.mitm_tab_fields))

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Header
        Surface(color = Bg1, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ac) }
                    Text(stringResource(R.string.mitm_captures), color = Ac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                    MetaCell(Modifier.weight(1f), stringResource(R.string.mitm_tab_risk), "$riskScore", rc)
                    MetaCell(Modifier.weight(1f), stringResource(R.string.mitm_dir), log.direction.take(3), TxP)
                    MetaCell(Modifier.weight(1f), stringResource(R.string.mitm_proto), log.protocol.take(5), TxP)
                    MetaCell(Modifier.weight(1f), stringResource(R.string.mitm_size), fmtBytes(log.sizeBytes), TxP)
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
    val hasBody = !log.body.isNullOrBlank()
    val notDecryptedReason = log.notDecryptedReasonRes()?.let { stringResource(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        SectionLabel(if (log.direction == "OUTBOUND") stringResource(R.string.mitm_request_body) else stringResource(R.string.mitm_response_body))

        // HTTPS that was NOT decrypted → explain why
        if (notDecryptedReason != null) {
            Surface(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = Blue.copy(alpha = 0.09f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Blue.copy(alpha = 0.3f))
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Lock, null, tint = Blue, modifier = Modifier.size(18.dp))
                    Column {
                        Text(stringResource(R.string.mitm_not_decrypted), color = Blue,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.mitm_not_decrypted_detail, notDecryptedReason, shortAppName(LocalContext.current, log.ownerPackage), log.sniHostname ?: log.destinationIp, fmtBytes(log.sizeBytes)),

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
        SectionLabel(stringResource(R.string.mitm_tab_headers))
        Surface(Modifier.fillMaxWidth(), color = Bg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, LineCol)) {
            val prettyHeaders = remember(log.headers) { tryPrettyJson(log.headers) }
            Text(prettyHeaders.ifBlank { stringResource(R.string.mitm_none) }, Modifier.fillMaxWidth().padding(12.dp),
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
        item { SectionLabel(stringResource(R.string.mitm_headers_count, headers.size)) }
        if (headers.isEmpty()) {
            item { Text(stringResource(R.string.mitm_no_headers), color = TxM, fontSize = 12.sp) }
        }
        items(headers.entries.toList(), key = { "header:${it.key}" }) { (key, value) ->
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
                        Text((if (severity == "bad") stringResource(R.string.mitm_sev_bad) else stringResource(R.string.mitm_sev_warn)).uppercase(), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
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
    val context = LocalContext.current
    val risks = remember(log.id) { buildRisks(context, log, riskScore) }
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
                            Text(stringResource(R.string.mitm_tab_risk).uppercase(), color = TxM, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                    Column {
                        Text(when { riskScore >= 80 -> stringResource(R.string.mitm_risk_high); riskScore >= 50 -> stringResource(R.string.mitm_risk_medium); else -> stringResource(R.string.mitm_risk_low) },
                            color = riskColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.mitm_risk_counts, pluralStringResource(R.plurals.mitm_critical_count, risks.count { it.first == "crit" }, risks.count { it.first == "crit" }), pluralStringResource(R.plurals.mitm_warning_count, risks.count { it.first == "warn" }, risks.count { it.first == "warn" })),
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

private fun buildRisks(context: android.content.Context, log: PayloadLogEntity, riskScore: Int): List<Triple<String, String, String>> {
    fun s(id: Int, vararg a: Any) = context.getString(id, *a)
    val risks = mutableListOf<Triple<String, String, String>>()
    for (leak in leaksOf(log))
        risks += Triple("crit", s(R.string.mitm_r_leak, leakLabel(context, leak)), leakDescription(context, leak))
    val headers = parseHeadersMap(log.headers)
    val hKeys = headers.keys.map { it.lowercase() }
    if (hKeys.any { it.contains("authorization") })
        risks += Triple("warn", s(R.string.mitm_r_auth), s(R.string.mitm_r_auth_desc))
    if (hKeys.any { it.contains("x-device") || it.contains("device-id") })
        risks += Triple("crit", s(R.string.mitm_r_device), s(R.string.mitm_r_device_desc))
    if (hKeys.any { it.contains("cookie") })
        risks += Triple("warn", s(R.string.mitm_r_cookie), s(R.string.mitm_r_cookie_desc))
    if (!log.body.isNullOrBlank() && log.body.length > 500)
        risks += Triple("info", s(R.string.mitm_r_large, fmtBytes(log.body.length)), s(R.string.mitm_r_large_desc, log.body.length))
    if (log.method == "POST" || log.method == "PUT")
        risks += Triple("info", s(R.string.mitm_r_submit, log.method.orEmpty()), s(R.string.mitm_r_submit_desc, log.sniHostname ?: log.destinationIp))
    if (log.protocol == "HTTP1" && log.destinationPort != 443)
        risks += Triple("crit", s(R.string.mitm_r_http), s(R.string.mitm_r_http_desc))
    log.notDecryptedReasonRes()?.let { context.getString(it) }?.let {
        risks += Triple("info", s(R.string.mitm_r_not_decrypted), it)
    }
    if (risks.isEmpty())
        risks += Triple("info", s(R.string.mitm_r_clean), s(R.string.mitm_r_clean_desc))
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
                        Text(stringResource(when (level) { "crit" -> R.string.mitm_level_crit; "warn" -> R.string.mitm_sev_warn; else -> R.string.mitm_level_info }).uppercase(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
    val context = LocalContext.current
    val unknown = stringResource(R.string.mitm_unknown_app)
    val none = stringResource(R.string.mitm_none)
    val noneFound = stringResource(R.string.mitm_none_found)
    val fields = remember(log.id) {
        buildList {
            add(Triple("package", log.ownerPackage ?: unknown, Ac))
            add(Triple("sni_hostname", log.sniHostname ?: none, Blue))
            add(Triple("destination_ip", log.destinationIp, TxP))
            add(Triple("destination_port", "${log.destinationPort}", TxP))
            add(Triple("protocol", log.protocol, TxP))
            if (!log.method.isNullOrBlank()) add(Triple("method", log.method, Amber))
            if (!log.urlPath.isNullOrBlank()) add(Triple("url_path", log.urlPath, Blue))
            add(Triple("direction", log.direction, if (log.direction == "OUTBOUND") Amber else Ac))
            add(Triple("size_bytes", "${log.sizeBytes} (${fmtBytes(log.sizeBytes)})", TxP))
            add(Triple("body_encoding", log.bodyEncoding, TxS))
            add(Triple("personal_data", leaksOf(log).joinToString { leakLabel(context, it) }.ifEmpty { noneFound },
                if (log.piiRedacted) Red else Ac))
            add(Triple("mitm_success", "${log.isMitmSuccess}", if (log.isMitmSuccess) Ac else Red))
            add(Triple("session_id", log.sessionId.take(36), TxM))
            add(Triple("timestamp", android.text.format.DateUtils.formatDateTime(context, log.timestamp, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR), TxS))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
        item { SectionLabel(stringResource(R.string.mitm_fields_count, fields.size)) }
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

