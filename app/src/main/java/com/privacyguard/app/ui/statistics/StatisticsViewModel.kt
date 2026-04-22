package com.privacyguard.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val _stats = MutableStateFlow(StatisticsData())
    val stats: StateFlow<StatisticsData> = _stats.asStateFlow()

    private val _topTrackers = MutableStateFlow<List<TrackerStats>>(emptyList())
    val topTrackers: StateFlow<List<TrackerStats>> = _topTrackers.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            delay(200)
            _stats.value = StatisticsData(
                blockedToday = 47,
                dataSavedToday = "2.3 MB",
                trackersToday = 128,
                blockedWeek = 342,
                dataSavedWeek = "18.7 MB",
                trackersWeek = 891,
                blockedTotal = 1452,
                dataSavedTotal = "76.4 MB",
                trackersTotal = 3421
            )
            _topTrackers.value = listOf(
                TrackerStats("Google Analytics", "Google", 1247),
                TrackerStats("Facebook Pixel", "Meta", 892),
                TrackerStats("DoubleClick", "Google", 654),
                TrackerStats("ScorecardResearch", "comScore", 432),
                TrackerStats("Amazon Ads", "Amazon", 321)
            )
        }
    }
}
