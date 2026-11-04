package com.privacyguard.app.ui.settings

import android.app.Application
import android.content.Context
import android.content.RestrictionsManager
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.R
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.PrefsBlocklistUpdateTimes
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.LocalDataEraser
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import com.privacyguard.app.ui.statistics.HistoryExport
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.app.vpn.ProtectionWatchdog
import com.privacyguard.app.workers.BlocklistUpdateWorker
import com.privacyguard.app.workers.WeeklyReportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the Settings list shows; a plain value so tests can draw any state. */
data class SettingsHomeState(
    val level: String = "STANDARD",
    val lists: SettingsLogic.ListsUpdated? = null,
    val listsUpdating: Boolean = false,
    val listNames: Int = 0,
    val lockdown: KillSwitch.LockdownStatus = KillSwitch.LockdownStatus.NOT_RUNNING,
    /** On only when the user wants it and Android lets it schedule the restart. */
    val autoRestart: Boolean = false,
    val notifications: Boolean = true,
    val notificationDetails: Boolean = false,
    val weeklyReport: Boolean = false,
    val appearance: Appearance = SettingsLogic.DEFAULT_APPEARANCE,
    val retentionDays: Int = 30,
    val allowScreenshots: Boolean = false,
)

/** What Settings asks for; one-shot results go back as [SettingsEvent]. */
sealed interface SettingsEvent {
    data class Share(val file: File) : SettingsEvent
    data object ExportFailed : SettingsEvent
    data object Cleared : SettingsEvent
    /** Auto-restart was turned on but Android has not allowed exact alarms yet. */
    data object NeedsAlarmAccess : SettingsEvent
    data object ConfigSaved : SettingsEvent
    data object ConfigSaveFailed : SettingsEvent
    data object ConfigImported : SettingsEvent
}

/** The import between choosing a file and the user's answer (D2): nothing has changed yet. */
sealed interface ImportUi {
    /** What Import would add and change; [skipped]: entries of the file that were not valid. */
    data class Confirm(val plan: ConfigTransfer.Plan, val settingsChanged: Int, val skipped: Int) : ImportUi
    /** Why nothing can be imported from this file. */
    data class Problem(@StringRes val message: Int) : ImportUi
}

/**
 * Settings (DESIGN_SPEC_1.1.md §6.5). Every switch writes the same preference, and starts
 * or stops the same worker, as 1.0 did: only the layout changed.
 */
class SettingsHomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SettingsPreferences.getInstance(app)
    private val times = PrefsBlocklistUpdateTimes(app)

    /** Read from Android, which can change them while the app is in the background: see [refresh]. */
    private val system = MutableStateFlow(SystemState())

    private data class SystemState(
        val lockdown: KillSwitch.LockdownStatus = KillSwitch.LockdownStatus.NOT_RUNNING,
        val exactAlarms: Boolean = true,
        val lists: SettingsLogic.ListsUpdated? = null,
    )

    private val rulesRepo by lazy { RulesRepo(ConfigDatabase.getInstance(app).rulesDao(), FilterEngine()) }

    private val _import = MutableStateFlow<ImportUi?>(null)
    val import: StateFlow<ImportUi?> = _import.asStateFlow()

    /** The file's settings, kept between the preview and the user's answer. */
    private var pendingSettings: ConfigTransfer.Settings? = null

    private val _events = MutableStateFlow<SettingsEvent?>(null)
    val events: StateFlow<SettingsEvent?> = _events.asStateFlow()
    fun eventHandled() { _events.value = null }

    private val toggles = combine(prefs.notificationsEnabled, prefs.notificationDetails, prefs.weeklyReport, prefs.allowScreenshots, prefs.autoRestart) {
            notifications, details, weekly, screenshots, restart ->
        listOf(notifications, details, weekly, screenshots, restart)
    }

    private val lists = combine(BlocklistManager.isUpdating, BlocklistManager.size, BlocklistManager.lastUpdate) { updating, size, _ -> updating to size }

    val state: StateFlow<SettingsHomeState> = combine(
        combine(prefs.protectionLevel, prefs.appearance, prefs.retentionDays) { level, look, days -> Triple(level, look, days) },
        toggles, lists, system,
    ) { (level, look, days), t, (updating, size), sys ->
        SettingsHomeState(
            level = level,
            lists = sys.lists,
            listsUpdating = updating,
            listNames = size,
            lockdown = sys.lockdown,
            autoRestart = t[4] && sys.exactAlarms,
            notifications = t[0],
            notificationDetails = t[1],
            weeklyReport = t[2],
            appearance = SettingsLogic.appearanceOf(look),
            retentionDays = days,
            allowScreenshots = t[3],
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHomeState())

    init {
        // A finished download changes "Updated …": read the times again when it ends.
        viewModelScope.launch { BlocklistManager.lastUpdate.collect { refresh() } }
    }

    /** On every resume: the kill switch, alarm access and list times are changed outside the app. */
    fun refresh() {
        viewModelScope.launch {
            val lists = withContext(Dispatchers.IO) {
                SettingsLogic.listsUpdated(SettingsLogic.DOWNLOADED_LISTS.map { times.lastSuccess(it) })
            }
            system.value = SystemState(
                lockdown = KillSwitch.lockdownStatus,
                exactAlarms = ProtectionWatchdog.canScheduleExact(getApplication()),
                lists = lists,
            )
        }
    }

    fun setLevel(level: String) = prefs.setProtectionLevel(level)
    fun setNotifications(on: Boolean) = prefs.setNotificationsEnabled(on)
    fun setNotificationDetails(on: Boolean) = prefs.setNotificationDetails(on)
    fun setAllowScreenshots(on: Boolean) = prefs.setAllowScreenshots(on)
    fun setRetentionDays(days: Int) { if (days in SettingsLogic.RETENTION_DAYS) prefs.setRetentionDays(days) }
    fun setAppearance(appearance: Appearance) = prefs.setAppearance(appearance.name)

    fun setWeeklyReport(on: Boolean) {
        prefs.setWeeklyReport(on)
        if (on) WeeklyReportWorker.scheduleWeekly(getApplication()) else WeeklyReportWorker.cancelReports(getApplication())
    }

    /** As in 1.0: on without alarm access asks for it; declined, it goes back off ([declineAlarmAccess]). */
    fun setAutoRestart(on: Boolean) {
        prefs.setAutoRestart(on)
        if (on && !system.value.exactAlarms) _events.value = SettingsEvent.NeedsAlarmAccess
    }

    fun declineAlarmAccess() = prefs.setAutoRestart(false)

    fun refreshLists() = BlocklistUpdateWorker.updateNow(getApplication())

    fun exportHistory() {
        viewModelScope.launch {
            _events.value = try {
                SettingsEvent.Share(HistoryExport.writeCsv(getApplication()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Export failed: ${e.javaClass.simpleName}")
                SettingsEvent.ExportFailed
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            LocalDataEraser.eraseAll(getApplication())
            _events.value = SettingsEvent.Cleared
        }
    }

    // ── Export and import of rules and settings (D2, ADR 0013) ──────────────

    /** Writes the file to [uri], a place the user picked in Android's file picker. */
    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            _events.value = try {
                val app = getApplication<Application>()
                val rules = rulesRepo.userRules()
                val json = ConfigTransfer.write(ConfigTransfer.exportable(rules), currentSettings(rules.map { it.id }.toSet()))
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                SettingsEvent.ConfigSaved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Settings export failed: ${e.javaClass.simpleName}")
                SettingsEvent.ConfigSaveFailed
            }
        }
    }

    /** Reads [uri] and shows what an import would do. Nothing changes until [confirmImport]. */
    fun previewImport(uri: Uri) {
        viewModelScope.launch {
            pendingSettings = null
            if (isManagedByOrganisation()) { _import.value = ImportUi.Problem(R.string.ui_cfg_err_managed); return@launch }
            val bytes = try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { readAtMost(it, ConfigTransfer.MAX_BYTES) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Settings file not read: ${e.javaClass.simpleName}")
                null
            }
            _import.value = when {
                bytes == null -> ImportUi.Problem(R.string.ui_cfg_err_read)
                bytes === TOO_BIG -> ImportUi.Problem(R.string.ui_cfg_err_too_big)
                else -> when (val result = withContext(Dispatchers.Default) { ConfigTransfer.read(bytes) }) {
                    is ConfigTransfer.ReadResult.Refused -> ImportUi.Problem(problemOf(result.reason))
                    is ConfigTransfer.ReadResult.Ok -> {
                        val existing = rulesRepo.userRules()
                        val plan = ConfigTransfer.plan(result.rules, existing)
                        val changed = ConfigTransfer.changedSettings(result.settings, currentSettings(existing.map { it.id }.toSet()))
                        if (plan.newRules.isEmpty() && changed == 0) {
                            ImportUi.Problem(R.string.ui_cfg_nothing)
                        } else {
                            pendingSettings = result.settings
                            ImportUi.Confirm(plan, changed, result.skipped)
                        }
                    }
                }
            }
        }
    }

    fun cancelImport() {
        _import.value = null
        pendingSettings = null
    }

    /** The user said Import: rules are added (never replaced), then the settings applied. */
    fun confirmImport() {
        val confirm = _import.value as? ImportUi.Confirm ?: return
        val settings = pendingSettings ?: return
        _import.value = null
        pendingSettings = null
        viewModelScope.launch {
            // Asked again: the phone may have been enrolled while the question was open.
            if (isManagedByOrganisation()) { _import.value = ImportUi.Problem(R.string.ui_cfg_err_managed); return@launch }
            val app = getApplication<Application>()
            val rules = withContext(Dispatchers.IO) {
                confirm.plan.newRules.map { e ->
                    val (labelRes, isApp) = ConfigTransfer.labelOf(e)
                    val pkg = e.app
                    val subject = if (isApp && pkg != null) com.privacyguard.app.ui.traffic.AppNames.label(app, pkg, null)
                        else e.site ?: e.address ?: pkg.orEmpty()
                    ConfigTransfer.toFilterRule(e, ConfigTransfer.idOf(e) { UUID.randomUUID().toString() }, app.getString(labelRes, subject))
                }
            }
            rulesRepo.addRules(rules)
            applySettings(settings)
            _events.value = SettingsEvent.ConfigImported
        }
    }

    /** Each setting through the same call its own screen makes. */
    private suspend fun applySettings(s: ConfigTransfer.Settings) {
        val app = getApplication<Application>()
        s.level?.let(prefs::setProtectionLevel)
        s.blockQuic?.let {
            prefs.setBlockQuic(when (it) { ConfigTransfer.Quic.ON -> true; ConfigTransfer.Quic.OFF -> false; ConfigTransfer.Quic.LEVEL_DEFAULT -> null })
        }
        s.encryptedDns?.let(prefs::setDohEnabled)
        s.dnsServer?.let { prefs.setUpstreamDns(it.ip); prefs.setDohProvider(it.dohProvider) }
        s.blockUnencrypted?.let {
            if (it) rulesRepo.upsertRule(SettingsViewModel.blockCleartextRule(app.getString(R.string.diag_rule_block_http)))
            else rulesRepo.deleteRule(SettingsViewModel.RULE_ID_BLOCK_CLEARTEXT)
        }
        s.blockWeakTls?.let {
            if (it) rulesRepo.upsertRule(SettingsViewModel.blockWeakTlsRule(app.getString(R.string.diag_rule_block_weak_tls)))
            else rulesRepo.deleteRule(SettingsViewModel.RULE_ID_BLOCK_WEAK_TLS)
        }
        s.certificateChecks?.let(prefs::setCtMonitoringEnabled)
        s.publicIpCheck?.let(prefs::setExternalIpCheckEnabled)
        s.notifications?.let(prefs::setNotificationsEnabled)
        s.notificationDetails?.let(prefs::setNotificationDetails)
        s.weeklyReport?.let(::setWeeklyReport)
        s.keepHistoryDays?.let(::setRetentionDays)
        s.theme?.let(prefs::setAppearance)
    }

    /** The settings as they are now, in the file's terms. [ruleIds]: to see the two global rules. */
    private fun currentSettings(ruleIds: Set<String>) = ConfigTransfer.Settings(
        level = prefs.protectionLevel.value,
        blockQuic = when (prefs.blockQuicChoice.value) {
            true -> ConfigTransfer.Quic.ON
            false -> ConfigTransfer.Quic.OFF
            null -> ConfigTransfer.Quic.LEVEL_DEFAULT
        },
        encryptedDns = prefs.dohEnabled.value,
        // A server the user typed is not exported: an import accepts only the built-in three.
        dnsServer = AdvancedLogic.presetOf(prefs.upstreamDns.value),
        blockUnencrypted = SettingsViewModel.RULE_ID_BLOCK_CLEARTEXT in ruleIds,
        blockWeakTls = SettingsViewModel.RULE_ID_BLOCK_WEAK_TLS in ruleIds,
        certificateChecks = prefs.ctMonitoringEnabled.value,
        publicIpCheck = prefs.externalIpCheckEnabled.value,
        notifications = prefs.notificationsEnabled.value,
        notificationDetails = prefs.notificationDetails.value,
        weeklyReport = prefs.weeklyReport.value,
        keepHistoryDays = prefs.retentionDays.value.takeIf { it in SettingsLogic.RETENTION_DAYS },
        theme = prefs.appearance.value,
    )

    /** ADR 0013 §5: while an organisation manages the phone, a file must not change its settings. */
    private fun isManagedByOrganisation(): Boolean {
        val rm = getApplication<Application>().getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager ?: return false
        return runCatching { !rm.applicationRestrictions.isEmpty }.getOrDefault(false)
    }

    private fun problemOf(reason: ConfigTransfer.Refusal) = when (reason) {
        ConfigTransfer.Refusal.TOO_BIG -> R.string.ui_cfg_err_too_big
        ConfigTransfer.Refusal.NOT_OURS -> R.string.ui_cfg_err_invalid
        ConfigTransfer.Refusal.NEWER_VERSION -> R.string.ui_cfg_err_newer
        ConfigTransfer.Refusal.TOO_MANY_RULES -> R.string.ui_cfg_err_too_many
    }

    internal companion object {
        private const val TAG = "SettingsHome"

        /** Marks a file over the limit; compared by identity. */
        private val TOO_BIG = ByteArray(0)

        /** Reads at most [max] bytes; a longer stream is not read further (returns [TOO_BIG]). */
        fun readAtMost(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return TOO_BIG
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** True when [bytes] is the marker [readAtMost] returns for a stream over the limit. */
        fun isTooBig(bytes: ByteArray) = bytes === TOO_BIG
    }
}
