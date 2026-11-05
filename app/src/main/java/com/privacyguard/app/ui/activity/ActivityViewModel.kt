package com.privacyguard.app.ui.activity

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers

/** The list: unknown until the encrypted database answers, never a made-up empty list. */
sealed interface ActivityList {
    data object Loading : ActivityList
    /** [filter]: what [items] were loaded for; shown only under that filter. */
    /** [blockedToday]: BlockStats.today, the number Home and the notification show (Blocked only). */
    data class Loaded(val items: List<ActivityItem>, val now: Long, val filter: ActivityFilter = ActivityFilter.BLOCKED, val blockedToday: Int? = null) : ActivityList
    data object Error : ActivityList
}

data class ActivityUiState(
    val filter: ActivityFilter = ActivityFilter.BLOCKED,
    val list: ActivityList = ActivityList.Loading,
    val protectionOn: Boolean = false,
)

/**
 * Activity (DESIGN_SPEC_1.1.md §6.2): what was stopped, why, and a way to allow it.
 * Pushed, not polled: the list is read again when Room reports a write to the history
 * tables or the rules change, at most every [RELOAD_MIN_MS]. The 1.0 Traffic screen ran
 * a 5 s loop for as long as it was visible (CLAUDE.md §16.2 item 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel internal constructor(
    app: Application,
    private val repo: ActivityRepository,
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, ActivityRepository(app))

    private val filter = MutableStateFlow(ActivityFilter.BLOCKED)
    private val retry = MutableStateFlow(0)

    // Set by a rule or list change, read by the next reload: conflate() keeps only the
    // newest signal, so the kind of change can't travel in it.
    private val rulesDirty = java.util.concurrent.atomic.AtomicBoolean(false)

    private val list: Flow<ActivityList> = combine(filter, retry) { f, _ -> f }.flatMapLatest { f ->
        flow<ActivityList> {
            emit(ActivityList.Loading)
            var shown: List<Long?>? = null
            changes().collect {
                if (rulesDirty.getAndSet(false)) {
                    repo.rulesChanged()
                    shown = null  // the same rows may now have another reason
                }
                // Most writes are allowed traffic, which can't change Blocked: two indexed
                // reads decide, and the full reload runs only when there is something new.
                val signature = repo.signature(f)
                if (signature != shown) {
                    val now = System.currentTimeMillis()
                    emit(ActivityList.Loaded(repo.load(f, now), now, f, if (f == ActivityFilter.BLOCKED) repo.blockedToday(now) else null))
                    shown = signature
                }
                // Throttle: rows arrive in bursts while apps are busy; writes during the
                // pause are conflated into one check.
                delay(RELOAD_MIN_MS)
            }
        }.catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Activity failed: ${e.javaClass.simpleName}")
            emit(ActivityList.Error)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<ActivityUiState> = combine(filter, list, PrivacyVpnService.state, clock()) { f, l, service, now ->
        ActivityUiState(f, shownList(f, l, now), service == PrivacyVpnService.State.RUNNING)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ActivityUiState(protectionOn = PrivacyVpnService.state.value == PrivacyVpnService.State.RUNNING),
    )

    private val _selected = MutableStateFlow<ActivityItem?>(null)
    /** The row whose detail sheet is open. */
    val selected: StateFlow<ActivityItem?> = _selected.asStateFlow()

    /** Turns sheet choices into rules (D8), with the malware warning and Undo. */
    val ruleActions = RuleActionController(app, viewModelScope, repo)

    fun setFilter(f: ActivityFilter) { filter.value = f }
    fun retry() { retry.value++ }
    fun select(item: ActivityItem?) { _selected.value = item }

    fun perform(action: ActivityAction, item: ActivityItem, confirmedMalware: Boolean = false) =
        ruleActions.perform(action, item, confirmedMalware) { _selected.value = null }

    /** One signal per change that can alter the list; the first one loads it. */
    private fun changes(): Flow<Unit> = flow {
        emit(Unit)
        val db = repo.database()
        val config = repo.configDatabase()
        merge(
            db.invalidationTracker.createFlow(*repo.historyTables(), emitInitialState = false).map { },
            merge(
                config.invalidationTracker.createFlow("rules", emitInitialState = false),
                db.invalidationTracker.createFlow("blocklist", emitInitialState = false),
                RuleSyncBus.version.drop(1),
            ).map { rulesDirty.set(true) },
        ).conflate().collect { emit(Unit) }
    }

    /**
     * The time, once a minute: rows move from "Just now" to "Today" with time alone. A
     * clock for regrouping rows already in memory; it reads nothing (CLAUDE.md §6.2).
     */
    private fun clock(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(MINUTE_MS)
        }
    }

    companion object {
        /**
         * What the screen shows for filter [f]: never the rows of the other filter (just
         * switched, the new list is still loading), and grouped at the later of load time
         * and [now], since time alone moves rows between groups.
         */
        internal fun shownList(f: ActivityFilter, l: ActivityList, now: Long): ActivityList = when {
            l is ActivityList.Loaded && l.filter != f -> ActivityList.Loading
            l is ActivityList.Loaded -> l.copy(now = maxOf(l.now, now))
            else -> l
        }

        private const val TAG = "ActivityViewModel"
        /** Like Home's count: one reload per 2 s is plenty for a list a person reads. */
        const val RELOAD_MIN_MS = 2_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MINUTE_MS = 60_000L
    }
}
