package com.privacyguard.app.ui.sensors

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.sensors.SensorAttributor
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.SensorEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TimelineFilter(val label: String) { ALL("All"), ALERTS("Alerts"), CAMERA("Camera"), MIC("Microphone") }

/** How app names are found right now, for the header. */
enum class AttributionMode { ADVANCED, USAGE_ACCESS, OFF }

data class TimelineRow(
    val id: Long,
    val isCamera: Boolean,
    /** "WhatsApp", "Probably WhatsApp" or "Unknown app" — never a guess stated as fact. */
    val who: String,
    /** "Could be: Snapchat, WhatsApp, Camera" when unknown; how it was found otherwise. */
    val whoDetail: String,
    val packageName: String?,
    val whenLabel: String,
    val durationLabel: String,
    /** Short facts: "Screen off", "Locked", "Night", "During a call", "Sent 3.2 MB". */
    val tags: List<String>,
    val reason: String,
    /** NONE / LOW / HIGH / CRITICAL */
    val severity: String,
    val inProgress: Boolean,
    val markedExpected: Boolean,
)

data class TimelineState(
    val rows: List<TimelineRow> = emptyList(),
    val filter: TimelineFilter = TimelineFilter.ALL,
    val mode: AttributionMode = AttributionMode.OFF,
    val alertCount: Int = 0,
    val total: Int = 0,
)

class SensorTimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).sensorEventDao()
    private val attributor = SensorAttributor(app)
    private val filter = MutableStateFlow(TimelineFilter.ALL)
    private val mode = MutableStateFlow(currentMode())
    private val labels = HashMap<String, String>()

    val state: StateFlow<TimelineState> = combine(
        dao.observeSince(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000), filter, mode,
    ) { events, f, m ->
        val shown = events.filter {
            when (f) {
                TimelineFilter.ALL -> true
                TimelineFilter.ALERTS -> it.severity == "HIGH" || it.severity == "CRITICAL"
                TimelineFilter.CAMERA -> it.sensor == "CAMERA"
                TimelineFilter.MIC -> it.sensor == "MIC"
            }
        }
        TimelineState(
            rows = shown.map(::toRow),
            filter = f,
            mode = m,
            alertCount = events.count { it.severity == "HIGH" || it.severity == "CRITICAL" },
            total = events.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState())

    fun setFilter(f: TimelineFilter) { filter.value = f }

    /** Re-read when returning from the Usage Access setting. */
    fun refreshMode() { mode.value = currentMode() }

    fun markExpected(id: Long) {
        viewModelScope.launch { dao.markExpected(id) }
    }

    private fun currentMode() = when {
        attributor.hasAdvancedMode() -> AttributionMode.ADVANCED
        attributor.hasUsageAccess() -> AttributionMode.USAGE_ACCESS
        else -> AttributionMode.OFF
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = getApplication<Application>().packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
        }.getOrDefault(pkg)
    }

    private fun toRow(e: SensorEventEntity): TimelineRow {
        val name = e.packageName?.let(::label)
        val who = when {
            name == null -> "Unknown app"
            e.confidence == "CONFIRMED" -> name
            e.confidence == "LIKELY" -> "Probably $name"
            else -> "Unknown app"
        }
        val candidates = e.candidates.split(',').filter { it.isNotBlank() }.take(3).map(::label)
        val whoDetail = when {
            e.confidence == "CONFIRMED" -> "Identified from Android's own record"
            e.confidence == "LIKELY" -> "It was on screen and has permission"
            candidates.isNotEmpty() -> "Could be: ${candidates.joinToString()}"
            else -> "Android does not say which app"
        }
        val tags = buildList {
            if (!e.screenOn) add("Screen off")
            if (e.locked) add("Locked")
            if (e.quietHours) add("Night")
            if (e.inCall) add("During a call")
            if (e.networkBurst) add("Sent ${formatBytes(e.uploadBytes ?: 0)}")
            if (e.userMarkedExpected) add("You said: it was me")
        }
        return TimelineRow(
            id = e.id,
            isCamera = e.sensor == "CAMERA",
            who = who,
            whoDetail = whoDetail,
            packageName = e.packageName,
            whenLabel = whenLabel(e.startTime),
            durationLabel = e.endTime?.let { durationLabel(it - e.startTime) } ?: "in use now",
            tags = tags,
            reason = e.reason.ifBlank {
                val what = if (e.sensor == "CAMERA") "camera" else "microphone"
                if (e.endTime == null) "The $what is in use." else "The $what was used."
            },
            severity = e.severity,
            inProgress = e.endTime == null,
            markedExpected = e.userMarkedExpected,
        )
    }

    private fun whenLabel(time: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        return when {
            now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today $clock"
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday $clock"
            else -> SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(time))
        }
    }

    private fun durationLabel(ms: Long): String = when {
        ms < 1_000 -> "under a second"
        ms < 60_000 -> "${ms / 1_000} s"
        ms < 3_600_000 -> "${ms / 60_000} min ${ms % 60_000 / 1_000} s"
        else -> "${ms / 3_600_000} h ${ms % 3_600_000 / 60_000} min"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024} KB"
    }
}
