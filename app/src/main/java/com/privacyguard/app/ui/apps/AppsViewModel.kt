package com.privacyguard.app.ui.apps

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.apps.InstalledAppsCache
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.repository.RuleSyncBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** The list: unknown until the encrypted database answers, never a made-up empty list. */
sealed interface AppsList {
    data object Loading : AppsList
    data class Loaded(val items: List<AppRiskItem>) : AppsList
    data object Error : AppsList
}

data class AppsUiState(
    val list: AppsList = AppsList.Loading,
    val query: String = "",
    val filter: AppsFilter = AppsFilter.ALL,
    val sort: AppSort = AppSort.MOST_BLOCKED,
) {
    /** What the list shows; empty while loading. */
    val shown: List<AppRiskItem>
        get() = (list as? AppsList.Loaded)?.items?.let { AppsLogic.shown(it, query, filter, sort) }.orEmpty()

    /** Apps the stalkerware check flags (1.0's banner), counted over every app. */
    val couldWatch: Int
        get() = (list as? AppsList.Loaded)?.items?.count { AppsLogic.riskWord(it) == RiskWord.COULD_WATCH } ?: 0
}

/**
 * Apps (DESIGN_SPEC_1.1.md §6.3). Pushed, not polled: the list is built again when Room
 * reports a write to the history tables, rules change, an app is installed or removed,
 * or the set of apps with an open connection changes, at most every [RELOAD_MIN_MS]
 * and only while the screen is shown. 1.0 rebuilt it every 3 s and dropped every error
 * in an empty catch (CLAUDE.md §16.2 item 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModel internal constructor(
    app: Application,
    private val repo: AppsRepository,
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, AppsRepository(app))

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(AppsFilter.ALL)
    private val sort = MutableStateFlow(AppSort.MOST_BLOCKED)
    private val retry = MutableStateFlow(0)

    private val list: Flow<AppsList> = retry.flatMapLatest {
        flow<AppsList> {
            emit(AppsList.Loading)
            changes().collect {
                emit(AppsList.Loaded(repo.load()))
                // Rows arrive in bursts while apps are busy: one rebuild per pause.
                delay(RELOAD_MIN_MS)
            }
        }.catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Apps failed: ${e.javaClass.simpleName}")
            emit(AppsList.Error)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<AppsUiState> = combine(list, query, filter, sort) { l, q, f, s -> AppsUiState(l, q, f, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppsUiState())

    fun setQuery(q: String) { query.value = q }
    fun setFilter(f: AppsFilter) { filter.value = f }
    fun setSort(s: AppSort) { sort.value = s }
    fun retry() { retry.value++ }

    /** One signal per change that can alter the list; the first one loads it. */
    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val db = repo.database()
        merge(
            db.invalidationTracker.createFlow(*repo.historyTables(), emitInitialState = false).map { },
            repo.configDatabase().invalidationTracker.createFlow("rules", emitInitialState = false).map { },
            RuleSyncBus.version.drop(1).map { },
            InstalledAppsCache.version.drop(1).map { repo.installedAppsChanged() },
            // Open connections change every second; only who has one matters here.
            StatsManager.snapshot
                .map { s -> s.activeConnections.mapTo(HashSet()) { it.packageName } }
                .distinctUntilChanged().drop(1).map { },
            ticks(),
        ).conflate().collect { emit(Unit) }
    }

    /** "Active now" and "today" move with time alone: one rebuild a minute, nothing more. */
    private fun ticks(): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            delay(MINUTE_MS)
            emit(Unit)
        }
    }

    companion object {
        private const val TAG = "AppsViewModel"
        /** A summary of every app: one rebuild per 5 s is plenty. */
        const val RELOAD_MIN_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MINUTE_MS = 60_000L

        /** Kept for the callers 1.0 had (Traffic, Rules). */
        fun packageBlockRuleId(packageName: String) = AppsLogic.packageBlockRuleId(packageName)
    }
}
