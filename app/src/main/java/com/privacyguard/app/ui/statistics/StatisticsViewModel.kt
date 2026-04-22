package com.privacyguard.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.stats.StatsManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatisticsData(
    val blockedToday: Int = 0,
    val dataSavedToday: String = "0 MB",
    val trackersToday: Int = 0,
    val blockedWeek: Int = 0,
    val dataSavedWeek: String = "0 MB",
    val trackersWeek: Int = 0,
    val blockedTotal: Int = 0,
    val dataSavedTotal: String = "0 MB",
    val trackersTotal: Int = 0
)

data class TrackerStats(
    val name: String,
    val company: String,
    val count: Int
)

class StatisticsViewModel : ViewModel() {
    val stats: StateFlow<StatisticsData> = StatsManager.snapshot
        .map { snapshot ->
            StatisticsData(
                blockedToday = snapshot.blockedToday.toInt(),
                dataSavedToday = formatBytes(snapshot.dataSavedBytes),
                trackersToday = snapshot.totalTrackersBlocked,
                blockedWeek = snapshot.blockedToday.toInt(),
                dataSavedWeek = formatBytes(snapshot.dataSavedBytes),
                trackersWeek = snapshot.totalTrackersBlocked,
                blockedTotal = snapshot.blockedToday.toInt(),
                dataSavedTotal = formatBytes(snapshot.dataSavedBytes),
                trackersTotal = snapshot.totalTrackersBlocked
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatisticsData()
        )

    val topTrackers: StateFlow<List<TrackerStats>> = StatsManager.snapshot
        .map { snapshot ->
            snapshot.topTrackers.map {
                TrackerStats(
                    name = it.domain,
                    company = inferCompany(it.domain),
                    count = it.count
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun inferCompany(domain: String): String = when {
        domain.contains("google", ignoreCase = true) || domain.contains("doubleclick", ignoreCase = true) -> "Google"
        domain.contains("facebook", ignoreCase = true) -> "Meta"
        domain.contains("amazon", ignoreCase = true) -> "Amazon"
        domain.contains("taboola", ignoreCase = true) -> "Taboola"
        domain.contains("outbrain", ignoreCase = true) -> "Outbrain"
        else -> "Network"
    }
}
