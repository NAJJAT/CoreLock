package com.privacyguard.app.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.blocklist.PrefsBlocklistUpdateTimes
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.LocalDataEraser
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

    private companion object { const val TAG = "SettingsHome" }
}
