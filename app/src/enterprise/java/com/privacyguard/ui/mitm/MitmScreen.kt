package com.privacyguard.ui.mitm

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacyguard.app.R
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.ui.components.CoreLockTopBar
import com.privacyguard.app.ui.components.PrimaryButton
import com.privacyguard.app.ui.components.PrimaryToggleRow
import com.privacyguard.app.ui.components.SecondaryButton
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.data.repository.PayloadLogRepositoryImpl
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.CaManager
import com.privacyguard.vpn.mitm.LeakDetector
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Advanced › HTTPS inspection (DESIGN_SPEC_1.1.md §5.7). Moved whole from the 1.0 Payload
// tab and restyled with the theme tokens only; what is captured, stored and shown is the
// same. The one behaviour change is D5: switching inspection on always asks first.

internal const val TAG_HTTPS_LIST = "https_list"
internal const val TAG_HTTPS_SWITCH = "https_switch"
internal const val TAG_HTTPS_CONFIRM = "https_confirm"
internal const val TAG_HTTPS_CONSENT = "https_consent"

// ── HELPERS ─────────────────────────────────────────────────────────────────
internal fun computeRiskScore(log: PayloadLogEntity): Int = PayloadRisk.score(log.riskInput())

/** Personal data types [com.privacyguard.vpn.mitm.LeakDetector] found in this request. */
internal fun leakLabel(context: android.content.Context, t: LeakDetector.LeakType): String = context.getString(
    when (t) {
        LeakDetector.LeakType.LOCATION -> R.string.leak_location
        LeakDetector.LeakType.ADVERTISING_ID -> R.string.leak_ad_id
        LeakDetector.LeakType.DEVICE_ID -> R.string.leak_device_id
        LeakDetector.LeakType.NETWORK_INFO -> R.string.leak_network
        LeakDetector.LeakType.EMAIL -> R.string.leak_email
        LeakDetector.LeakType.PHONE_NUMBER -> R.string.leak_phone
    }
)

internal fun leakDescription(context: android.content.Context, t: LeakDetector.LeakType): String = context.getString(
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
internal fun isLogFlagged(log: PayloadLogEntity): Boolean = PayloadRisk.isFlagged(log.riskInput())

// The installed app's own label (localized by its developer), not a guess from the package name.
internal fun shortAppName(context: android.content.Context, pkg: String?): String {
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
internal fun fmtBytes(bytes: Int): String = com.privacyguard.app.core.utils.ByteFormat.format(bytes.toLong())

// Locale-aware "5 minutes ago" / "5 minuten geleden" / "قبل ٥ دقائق".
private fun fmtRelative(ts: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        ts, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS,
    ).toString()

// ── SWITCHING ON (D5) ───────────────────────────────────────────────────────

/** What switching inspection on asks first. There is always exactly one question. */
internal enum class SwitchOnPrompt { CONSENT, CONFIRM }

/**
 * D5: inspection is never switched on by the switch alone. Without valid consent the
 * consent dialog (unchanged from 1.0) is the question; with consent, a short
 * confirmation is. Only the answer turns inspection on.
 */
internal fun switchOnPrompt(consentValid: Boolean): SwitchOnPrompt =
    if (consentValid) SwitchOnPrompt.CONFIRM else SwitchOnPrompt.CONSENT

/**
 * The inspection switch and the question it asks before turning on. [onConsented]
 * records consent and turns inspection on ([MitmViewModel.recordConsent]); [onConfirmed]
 * only turns it on. Cancelling either leaves inspection off.
 */
@Composable
internal fun InspectionSwitch(
    isEnabled: Boolean,
    consentValid: Boolean,
    onConfirmed: () -> Unit,
    onConsented: () -> Unit,
    onSwitchedOff: () -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf<SwitchOnPrompt?>(null) }
    CardSurface {
        PrimaryToggleRow(
            title = stringResource(R.string.ui_adv_https),
            subtitle = stringResource(R.string.ui_adv_https_sub),
            checked = isEnabled,
            onCheckedChange = { on ->
                if (on) prompt = switchOnPrompt(consentValid) else onSwitchedOff()
            },
            modifier = Modifier.testTag(TAG_HTTPS_SWITCH),
        )
    }
    when (prompt) {
        SwitchOnPrompt.CONSENT -> ConsentDialog(
            onAccept = { prompt = null; onConsented() },
            onDecline = { prompt = null },
        )
        SwitchOnPrompt.CONFIRM -> SwitchOnConfirmDialog(
            onConfirm = { prompt = null; onConfirmed() },
            onCancel = { prompt = null },
        )
        null -> Unit
    }
}

/** The 1.0 consent dialog: same text, same buttons, theme colours only. */
@Composable
internal fun ConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    TokenDialog(
        title = stringResource(R.string.mitm_consent_title),
        body = stringResource(R.string.mitm_consent_body),
        confirm = stringResource(R.string.mitm_consent_confirm),
        dismiss = stringResource(R.string.mitm_cancel),
        onConfirm = onAccept,
        onDismiss = onDecline,
        modifier = Modifier.testTag(TAG_HTTPS_CONSENT),
        ignoreObscuredTouches = true,
    )
}

@Composable
private fun SwitchOnConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    TokenDialog(
        title = stringResource(R.string.ui_https_confirm_title),
        body = stringResource(R.string.ui_https_confirm_body),
        confirm = stringResource(R.string.ui_https_confirm_on),
        dismiss = stringResource(R.string.mitm_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel,
        icon = Icons.Rounded.Warning,
        modifier = Modifier.testTag(TAG_HTTPS_CONFIRM),
        ignoreObscuredTouches = true,
    )
}

@Composable
private fun TokenDialog(
    title: String,
    body: String,
    confirm: String?,
    dismiss: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = CoreLockTheme.colors.attention,
    /** The two questions that turn inspection on: a tap through another app's overlay must not answer them. */
    ignoreObscuredTouches: Boolean = false,
) {
    val colors = CoreLockTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceMuted,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = iconTint) } },
        title = {
            if (ignoreObscuredTouches) com.privacyguard.app.ui.components.IgnoreObscuredTouches()
            Text(title, style = MaterialTheme.typography.titleLarge)
        },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (confirm != null) TextButton(onClick = onConfirm) {
                Text(confirm, color = colors.primary, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = dismiss?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = colors.onSurfaceMuted, style = MaterialTheme.typography.labelLarge) } }
        },
    )
}

// ── NAV STATE ───────────────────────────────────────────────────────────────
private sealed class PScreen {
    object List : PScreen()
    object Analytics : PScreen()
    data class Detail(val log: PayloadLogEntity) : PScreen()
}

// ── MAIN ENTRY ───────────────────────────────────────────────────────────────
@Composable
fun MitmScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val mitmConfig  = remember { MitmConfig(context) }
    val caManager   = remember { CaManager(context) }          // shared with the capture list
    val database    = remember { AppDatabase.getInstance(context) }
    val repository: PayloadLogRepository = remember { PayloadLogRepositoryImpl(database.payloadLogDao()) }
    // The same shipper the VPN fills; a new one here would always be empty.
    val payloadShipper = remember { PayloadShipper.getInstance(context) }
    val vm: MitmViewModel = viewModel(factory = MitmViewModelFactory(mitmConfig, repository, payloadShipper))
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<PScreen>(PScreen.List) }
    var showConsentDialog by remember { mutableStateOf(false) }

    // Inspection is on without valid consent: the user's consent expired (90 days), or
    // the organisation's MDM turned inspection on without consenting. Ask again; a no
    // turns it off. (Switching on from the screen asks before, in InspectionSwitch.)
    LaunchedEffect(uiState.isEnabled, uiState.isConsentValid) {
        if (uiState.isEnabled && !uiState.isConsentValid) showConsentDialog = true
    }

    val caHelper     = remember { CaInstallHelper(context, caManager) }
    val installScope = rememberCoroutineScope()
    var showScreenLockDialog by remember { mutableStateOf(false) }
    // null = hidden; true = cert saved to Downloads; false = saving failed
    var settingsInstallSaved by remember { mutableStateOf<Boolean?>(null) }

    // Exports the cert to Downloads and shows the "install it from Settings" steps.
    val exportCa: () -> Unit = {
        installScope.launch {
            if (!caManager.initialize()) { settingsInstallSaved = false; return@launch }
            val saved = withContext(Dispatchers.IO) { caHelper.exportCaToDownloads() } != null
            settingsInstallSaved = saved
        }
    }
    // Android 9 and below write public Downloads directly, which needs the storage
    // permission granted at runtime; declaring it alone never let the write succeed.
    val storagePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) exportCa() else settingsInstallSaved = false }
    val saveCaForSettings: () -> Unit = {
        val needsPermission = android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else exportCa()
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

    Box(Modifier.fillMaxSize().background(CoreLockTheme.colors.background)) {
        when (val s = screen) {
            is PScreen.List -> CaptureListScreen(
                uiState = uiState, vm = vm, caManager = caManager,
                onBack = onBack,
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
            // No consent, no inspection: leaving the switch on without consent decrypted
            // nothing but showed "on", which misled the user.
            ConsentDialog(
                onAccept = { vm.recordConsent(); showConsentDialog = false },
                onDecline = { vm.disableMitm(); showConsentDialog = false },
            )
        }

        if (showScreenLockDialog) {
            TokenDialog(
                title = stringResource(R.string.mitm_lock_title),
                body = stringResource(R.string.mitm_lock_body),
                confirm = stringResource(R.string.mitm_open_settings),
                dismiss = stringResource(R.string.mitm_cancel),
                onConfirm = {
                    showScreenLockDialog = false
                    openSettings(context, caHelper.getSecuritySettingsIntent())
                },
                onDismiss = { showScreenLockDialog = false },
                icon = Icons.Rounded.Lock,
            )
        }

        settingsInstallSaved?.let { saved ->
            // Android 11+ only lets the Settings app install CA certificates, so the cert
            // is saved to Downloads and the user is walked through picking it there.
            if (saved) TokenDialog(
                title = stringResource(R.string.mitm_install_title),
                body = stringResource(R.string.mitm_install_steps, caHelper.exportFileName()),
                confirm = stringResource(R.string.mitm_open_settings),
                dismiss = stringResource(R.string.mitm_later),
                onConfirm = {
                    settingsInstallSaved = null
                    openSettings(context, caHelper.getInstallFromStorageIntent(), caHelper.getSecuritySettingsIntent())
                },
                onDismiss = { settingsInstallSaved = null },
                icon = Icons.Rounded.Download,
                iconTint = CoreLockTheme.colors.primary,
            ) else TokenDialog(
                title = stringResource(R.string.mitm_save_failed_title),
                body = stringResource(R.string.mitm_save_failed_body),
                confirm = stringResource(R.string.mitm_ok),
                dismiss = null,
                onConfirm = { settingsInstallSaved = null },
                onDismiss = { settingsInstallSaved = null },
                icon = Icons.Rounded.Error,
                iconTint = CoreLockTheme.colors.danger,
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

// ── CAPTURE LIST SCREEN ──────────────────────────────────────────────────────
@Composable
private fun CaptureListScreen(
    uiState: MitmUiState,
    vm: MitmViewModel,
    caManager: CaManager,
    onBack: (() -> Unit)?,
    onInstallCa: () -> Unit,
    onSaveCa: () -> Unit,
    onItemClick: (PayloadLogEntity) -> Unit,
    onStatsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    var showRemoveCaPrompt by remember { mutableStateOf(false) }

    if (showRemoveCaPrompt) {
        TokenDialog(
            title = stringResource(R.string.mitm_remove_ca_title),
            body = stringResource(R.string.mitm_remove_ca_body),
            confirm = stringResource(R.string.mitm_open_settings),
            dismiss = stringResource(R.string.mitm_later),
            onConfirm = {
                showRemoveCaPrompt = false
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onDismiss = { showRemoveCaPrompt = false },
        )
    }

    // An empty result of a search or filter is "no match", not "nothing captured".
    val filterActive = uiState.searchQuery.isNotBlank() || uiState.methodFilter != null ||
        uiState.showFlaggedOnly || uiState.filterHasBody || uiState.filterDirection != null

    Column(Modifier.fillMaxSize()) {
        CoreLockTopBar(
            title = stringResource(R.string.ui_adv_https),
            onBack = onBack,
            actions = {
                // The two icon buttons of 1.0 had no label; TalkBack read "button".
                IconButton(onClick = onInstallCa) {
                    Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.ui_https_install_cd), tint = colors.primary)
                }
                IconButton(onClick = onStatsClick) {
                    Icon(Icons.Rounded.BarChart, contentDescription = stringResource(R.string.mitm_analytics), tint = colors.onSurfaceMuted)
                }
            },
        )

        // One scrolling list: at 200 % font the 1.0 fixed header left no room for the captures.
        LazyColumn(
            Modifier.fillMaxSize().testTag(TAG_HTTPS_LIST),
            contentPadding = PaddingValues(bottom = spacing.xxxl),
        ) {
            item(key = "switch") {
                Box(Modifier.padding(horizontal = spacing.screen, vertical = spacing.s)) {
                    InspectionSwitch(
                        isEnabled = uiState.isEnabled,
                        consentValid = uiState.isConsentValid,
                        onConfirmed = { vm.enableMitm() },
                        onConsented = { vm.recordConsent() },
                        onSwitchedOff = {
                            vm.disableMitm()
                            // Turning inspection off retires the CA: delete its key so it can
                            // never sign again, then ask the user to untrust the installed copy.
                            scope.launch { caManager.reset() }
                            showRemoveCaPrompt = true
                        },
                    )
                }
            }

            // Engine status (codes come from MitmEngine; its own message is English, for logs).
            if (uiState.isEnabled && uiState.mitmStatus !in listOf("IDLE", "ACTIVE")) item(key = "status") {
                StatusCard(uiState.mitmStatus, uiState.mitmStatusDomain)
            }

            if (uiState.isEnabled) {
                item(key = "ca") {
                    Box(Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs)) {
                        CaInstallCard(context = context, caManager = caManager, onInstall = onInstallCa, onSaveFile = onSaveCa)
                    }
                }
                item(key = "quic") {
                    Box(Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs)) {
                        CardSurface {
                            PrimaryToggleRow(
                                title = stringResource(R.string.mitm_quic),
                                subtitle = stringResource(if (uiState.blockQuic) R.string.mitm_quic_on else R.string.mitm_quic_off),
                                checked = uiState.blockQuic,
                                onCheckedChange = { vm.setBlockQuic(it) },
                            )
                        }
                    }
                }
            }

            item(key = "filters") {
                val allActive = uiState.methodFilter == null && !uiState.showFlaggedOnly && !uiState.filterHasBody
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    contentPadding = PaddingValues(horizontal = spacing.screen),
                    modifier = Modifier.padding(top = spacing.m),
                ) {
                    item { TokenChip(stringResource(R.string.mitm_filter_all), allActive) {
                        vm.setMethodFilter(null); vm.setShowFlaggedOnly(false); vm.setFilterHasBody(false)
                    } }
                    item { TokenChip("POST", uiState.methodFilter == "POST") { vm.setMethodFilter(if (uiState.methodFilter == "POST") null else "POST") } }
                    item { TokenChip("GET", uiState.methodFilter == "GET") { vm.setMethodFilter(if (uiState.methodFilter == "GET") null else "GET") } }
                    item { TokenChip(stringResource(R.string.mitm_filter_flagged), uiState.showFlaggedOnly) { vm.setShowFlaggedOnly(!uiState.showFlaggedOnly) } }
                    item { TokenChip(stringResource(R.string.mitm_filter_body), uiState.filterHasBody) { vm.setFilterHasBody(!uiState.filterHasBody) } }
                }
            }

            item(key = "search") {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { vm.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.s),
                    placeholder = { Text(stringResource(R.string.mitm_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CoreLockTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        focusedPlaceholderColor = colors.onSurfaceMuted,
                        unfocusedPlaceholderColor = colors.onSurfaceMuted,
                        focusedLeadingIconColor = colors.onSurfaceMuted,
                        unfocusedLeadingIconColor = colors.onSurfaceMuted,
                        cursorColor = colors.primary,
                    ),
                )
            }

            if (uiState.recentLogs.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = spacing.xl, vertical = spacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.m),
                    ) {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = null, tint = colors.onSurfaceMuted, modifier = Modifier.size(spacing.xxxl))
                        Text(
                            stringResource(when {
                                filterActive -> R.string.mitm_no_match
                                uiState.isEnabled -> R.string.mitm_empty_on
                                else -> R.string.mitm_empty_off
                            }),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(when {
                                filterActive -> R.string.mitm_no_match_desc
                                uiState.isEnabled -> R.string.mitm_empty_on_desc
                                else -> R.string.mitm_empty_off_desc
                            }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                if (uiState.listCapped) item(key = "capped") {
                    Text(
                        stringResource(R.string.mitm_list_capped, MitmViewModel.LIST_LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.s),
                    )
                }
                items(uiState.recentLogs, key = { it.id }) { log ->
                    PayloadListItem(log = log, onClick = { onItemClick(log) })
                    HorizontalDivider(color = colors.outline.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: String, domain: String?) {
    val colors = CoreLockTheme.colors
    val (container, content, icon) = when (status) {
        "ERROR", "HANDSHAKE_FAILED" -> Triple(colors.dangerContainer, colors.onDangerContainer, Icons.Rounded.Error)
        "PINNED_BYPASS", "TIMEOUT" -> Triple(colors.attentionContainer, colors.onAttentionContainer, Icons.Rounded.Warning)
        else -> Triple(colors.surfaceVariant, colors.onSurface, Icons.Rounded.Info)
    }
    val (label, message) = when (status) {
        "STARTING" -> R.string.mitm_st_starting to R.string.mitm_st_starting_desc
        "TIMEOUT" -> R.string.mitm_st_timeout to R.string.mitm_st_timeout_desc
        "PINNED_BYPASS" -> R.string.mitm_st_pinned to R.string.mitm_st_pinned_desc
        "HANDSHAKE_FAILED" -> R.string.mitm_st_handshake to R.string.mitm_st_handshake_desc
        else -> R.string.mitm_st_error to R.string.mitm_st_error_desc
    }
    Surface(
        color = container,
        contentColor = content,
        shape = CoreLockTheme.shapes.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CoreLockTheme.spacing.screen, vertical = CoreLockTheme.spacing.xs),
    ) {
        Row(Modifier.padding(CoreLockTheme.spacing.m), horizontalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.m)) {
            Icon(icon, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(CoreLockTheme.spacing.xs)) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                domain?.let { Text(stringResource(R.string.mitm_domain, it), style = MaterialTheme.typography.bodySmall) }
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
    onSaveFile: () -> Unit,
) {
    val helper       = remember(context, caManager) { CaInstallHelper(context, caManager) }
    val installScope = rememberCoroutineScope()
    var caReady      by remember { mutableStateOf(false) }
    var caFailed     by remember { mutableStateOf(false) }
    var caTrusted    by remember { mutableStateOf(false) }
    // Old PrivacyGuard CAs still trusted by Android; their keys are gone, so they are
    // dead weight and make it unclear which certificate is the working one.
    var staleCas     by remember { mutableIntStateOf(0) }

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

    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    // State is said by the icon and the title, not by colour alone.
    val (icon, tint) = when {
        caTrusted -> Icons.Rounded.VerifiedUser to colors.protected
        caFailed -> Icons.Rounded.Error to colors.danger
        caReady -> Icons.Rounded.Lock to colors.attention
        else -> Icons.Rounded.Lock to colors.onSurfaceMuted
    }

    CardSurface {
        Column(Modifier.padding(spacing.l), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                Icon(icon, contentDescription = null, tint = tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        stringResource(when {
                            caTrusted -> R.string.mitm_ca_trusted
                            caFailed  -> R.string.mitm_ca_failed
                            caReady   -> R.string.mitm_ca_ready
                            else      -> R.string.mitm_ca_generating
                        }),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        stringResource(when {
                            caTrusted -> R.string.mitm_ca_trusted_desc
                            caFailed  -> R.string.mitm_ca_failed_desc
                            caReady && helper.canInstallFromApp -> R.string.mitm_ca_ready_app_desc
                            caReady   -> R.string.mitm_ca_ready_settings_desc
                            else      -> R.string.mitm_ca_generating_desc
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                    )
                    if (staleCas > 0) {
                        Text(
                            pluralStringResource(R.plurals.mitm_stale_cas, staleCas, staleCas),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.attention,
                        )
                    }
                }
            }
            if (staleCas > 0) {
                SecondaryButton(
                    stringResource(R.string.mitm_remove_old),
                    onClick = { openSettings(context, helper.getTrustedCredentialsIntent(), helper.getSecuritySettingsIntent()) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (caFailed) {
                SecondaryButton(
                    stringResource(R.string.mitm_regenerate),
                    onClick = {
                        installScope.launch {
                            caFailed = false
                            caManager.reset()
                            val ok = caManager.initialize()
                            caReady  = ok
                            caFailed = !ok
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (caReady && !caTrusted) {
                // In-app installer on Android ≤ 10, Settings walkthrough on 11+.
                // Trust is re-checked by the ON_RESUME effect when the user returns.
                PrimaryButton(stringResource(R.string.mitm_install), onClick = onInstall, modifier = Modifier.fillMaxWidth())
                // Secondary on Android ≤ 10: save to Downloads and install from Settings
                // (for OEM installers that reject the in-app flow). On 11+ Install
                // already does exactly this, so the button would be redundant.
                if (helper.canInstallFromApp) {
                    SecondaryButton(stringResource(R.string.mitm_save_crt), onClick = onSaveFile, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = CoreLockTheme.colors.surface,
        shape = CoreLockTheme.shapes.card,
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** A filter chip styled like ChipRow; these filters combine, so they are not one ChipRow. */
@Composable
internal fun TokenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CoreLockTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        shape = CoreLockTheme.shapes.small,
        modifier = Modifier.heightIn(min = CoreLockTheme.spacing.minTouchTarget),
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.onSurface,
            selectedContainerColor = colors.protectedContainer,
            selectedLabelColor = colors.onProtectedContainer,
            selectedLeadingIconColor = colors.onProtectedContainer,
        ),
    )
}

@Composable
private fun PayloadListItem(log: PayloadLogEntity, onClick: () -> Unit) {
    val colors = CoreLockTheme.colors
    val spacing = CoreLockTheme.spacing
    val riskScore = remember(log.id) { computeRiskScore(log) }
    val flagged = remember(log.id) { isLogFlagged(log) }
    val dotColor = when { flagged || riskScore >= 80 -> colors.danger; riskScore >= 50 -> colors.attention; else -> colors.protected }
    val context = LocalContext.current
    val appName = remember(log.ownerPackage) { shortAppName(context, log.ownerPackage) }
    val outbound = log.direction == "OUTBOUND"

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = spacing.minTouchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Flagged rows get a bar on the start edge (mirrored in RTL) and the word in the row.
        Box(Modifier.width(spacing.xs).fillMaxHeight().background(if (flagged) colors.danger else Color.Transparent))
        Row(
            Modifier.weight(1f).padding(start = spacing.m, end = spacing.l, top = spacing.m, bottom = spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Box(Modifier.size(spacing.s).clip(CircleShape).background(dotColor))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    Text(
                        appName, style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (log.method != null) {
                        MethodBadge(log.method)
                    } else {
                        Badge(if (log.destinationPort == 443) "HTTPS" else log.protocol.take(5), colors.surfaceVariant, colors.onSurfaceMuted)
                    }
                    if (flagged) Text(stringResource(R.string.mitm_flagged), style = MaterialTheme.typography.labelMedium, color = colors.danger)
                }
                Text(
                    "${log.sniHostname ?: log.destinationIp}${log.urlPath?.take(32) ?: ""}",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val leaks = remember(log.id) { leaksOf(log) }
                if (leaks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.mitm_sends, leaks.joinToString(" · ") { leakLabel(context, it) }),
                        style = MaterialTheme.typography.labelMedium, color = colors.danger,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Icon(
                    if (outbound) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(if (outbound) R.string.mitm_outbound else R.string.mitm_inbound),
                    tint = if (outbound) colors.attention else colors.protected,
                    modifier = Modifier.size(18.dp),
                )
                Text(fmtBytes(log.sizeBytes), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                Text(fmtRelative(log.timestamp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            }
        }
    }
}

@Composable
internal fun MethodBadge(method: String?) {
    if (method == null) return
    val colors = CoreLockTheme.colors
    val (bg, fg) = when (method.uppercase()) {
        "POST", "PUT" -> colors.attentionContainer to colors.onAttentionContainer
        "DELETE" -> colors.dangerContainer to colors.onDangerContainer
        "GET" -> colors.protectedContainer to colors.onProtectedContainer
        else -> colors.surfaceVariant to colors.onSurface
    }
    Badge(method.uppercase(), bg, fg)
}

@Composable
internal fun Badge(text: String, container: Color, content: Color) {
    Surface(color = container, shape = CoreLockTheme.shapes.small) {
        Text(
            text,
            Modifier.padding(horizontal = CoreLockTheme.spacing.s, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = content,
        )
    }
}
