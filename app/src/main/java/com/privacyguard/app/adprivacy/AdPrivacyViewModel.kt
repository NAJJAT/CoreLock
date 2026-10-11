package com.privacyguard.app.adprivacy

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.SensorEventEntity
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.platform.android.ForegroundAppDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One mic/camera use, with the likely app resolved to a label. */
data class SensorUseRow(val event: SensorEventEntity, val appLabel: String?)

/** All uses attributed to one app (null package = unknown). */
data class SensorAppGroup(val packageName: String?, val appLabel: String?, val uses: List<SensorUseRow>)

data class MicSearchResult(val from: Long, val to: Long, val uses: List<SensorUseRow>)

class AdPrivacyViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val pm: PackageManager = app.packageManager
    private val prefs = SettingsPreferences.getInstance(app)
    private val foreground = ForegroundAppDetector(app)

    val sensorGroups: StateFlow<List<SensorAppGroup>> = db.sensorEventDao().recentFlow(500)
        .map { events ->
            events.map { SensorUseRow(it, it.likelyPackage?.let(::label)) }
                .groupBy { it.event.likelyPackage }
                .map { (pkg, uses) -> SensorAppGroup(pkg, uses.first().appLabel, uses) }
                .sortedByDescending { group -> group.uses.maxOf { it.event.startedAt } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _usageAccess = MutableStateFlow(foreground.hasUsageAccess())
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    private val _locationApps = MutableStateFlow<List<PermissionApp>>(emptyList())
    val locationApps: StateFlow<List<PermissionApp>> = _locationApps.asStateFlow()

    private val _micApps = MutableStateFlow<List<PermissionApp>>(emptyList())
    val micApps: StateFlow<List<PermissionApp>> = _micApps.asStateFlow()

    private val _search = MutableStateFlow<MicSearchResult?>(null)
    val search: StateFlow<MicSearchResult?> = _search.asStateFlow()

    val checklistDone: StateFlow<Set<String>> = prefs.adChecklistDone
    val adSdkBlocking: StateFlow<Boolean> = prefs.adSdkBlockingEnabled

    init { refresh() }

    /** Re-read grants: the user may have just changed them in system settings. */
    fun refresh() {
        _usageAccess.value = foreground.hasUsageAccess()
        viewModelScope.launch {
            val self = getApplication<Application>().packageName
            val (location, mic) = withContext(Dispatchers.IO) {
                PermissionAudit.locationApps(pm, self) to PermissionAudit.micApps(pm, self)
            }
            _locationApps.value = location
            _micApps.value = mic
        }
    }

    fun searchMic(from: Long, to: Long) {
        viewModelScope.launch {
            val uses = db.sensorEventDao().overlapping(SensorEventEntity.SENSOR_MIC, from, to)
                .map { SensorUseRow(it, it.likelyPackage?.let(::label)) }
            _search.value = MicSearchResult(from, to, uses)
        }
    }

    fun setStepDone(step: ChecklistStep, done: Boolean) = prefs.setAdChecklistDone(step.id, done)

    fun setAdSdkBlocking(enabled: Boolean) = prefs.setAdSdkBlockingEnabled(enabled)

    private fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
