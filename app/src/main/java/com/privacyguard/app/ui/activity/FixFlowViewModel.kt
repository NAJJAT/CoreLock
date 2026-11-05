package com.privacyguard.app.ui.activity

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.privacyguard.app.vpn.ProtectionPause
import kotlinx.coroutines.launch

/** Where the user is in "Something not working?" (DESIGN_SPEC_1.1.md §6.2). */
sealed interface FixStep {
    /** Step 1: which app. */
    data object App : FixStep
    /** Step 2: what was blocked for [packageName] (null: everything, the user isn't sure). */
    data class Names(val packageName: String?, val appName: String?) : FixStep
    /** Step 3: what to do about one name. */
    data class Decide(val name: ActivityLogic.FixName, val from: Names) : FixStep
}

sealed interface FixData {
    data object Loading : FixData
    data class Loaded(val items: List<ActivityItem>) : FixData
    data object Error : FixData
}

/** [handled]: names the user allowed or blocked in this flow; they leave step 2 until undone. */
data class FixUiState(
    val step: FixStep = FixStep.App,
    val data: FixData = FixData.Loading,
    val handled: Set<String> = emptySet(),
    /** Whether the D1 pause can be offered here (read at open and on every return to the screen). */
    val pause: ProtectionPause.Availability = ProtectionPause.Availability.NOT_RUNNING,
)

/**
 * The fix flow: three taps, no typing. Reads the last hour once when it opens (and on
 * Try again): the user is looking at what just broke, and a list that reorders itself
 * while they read it would be worse than a slightly old one. After an Allow the name
 * leaves the list, since it is no longer blocked.
 */
class FixFlowViewModel internal constructor(
    app: Application,
    private val repo: ActivityRepository,
) : AndroidViewModel(app) {

    constructor(app: Application) : this(app, ActivityRepository(app))

    private val _state = MutableStateFlow(FixUiState())
    val state: StateFlow<FixUiState> = _state.asStateFlow()

    val ruleActions = RuleActionController(app, viewModelScope, repo)

    init { load(); refreshPause() }

    /** The user may have allowed alarms or changed Always-on in Android's settings meanwhile. */
    fun refreshPause() { _state.update { it.copy(pause = ProtectionPause.availability(getApplication())) } }

    fun load() {
        _state.value = _state.value.copy(data = FixData.Loading)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                data = try {
                    FixData.Loaded(repo.lastHourBlocked())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Fix flow failed: ${e.javaClass.simpleName}")
                    FixData.Error
                },
            )
        }
    }

    fun chooseApp(packageName: String?, appName: String?) { go(FixStep.Names(packageName, appName)) }

    fun chooseName(name: ActivityLogic.FixName) {
        val from = _state.value.step as? FixStep.Names ?: FixStep.Names(null, null)
        go(FixStep.Decide(name, from))
    }

    /** Back one step; false when already at the first (the screen then closes). */
    fun back(): Boolean = when (val step = _state.value.step) {
        FixStep.App -> false
        is FixStep.Names -> { go(FixStep.App); true }
        is FixStep.Decide -> { go(step.from); true }
    }

    fun perform(action: ActivityAction, item: ActivityItem, confirmedMalware: Boolean = false) {
        val host = item.host
        ruleActions.perform(
            action, item, confirmedMalware,
            onUndone = { if (host != null) _state.update { it.copy(handled = it.handled - host) } },
        ) {
            if (action != ActivityAction.KEEP_BLOCKED && host != null) _state.update { it.copy(handled = it.handled + host) }
            (_state.value.step as? FixStep.Decide)?.let { go(it.from) }
        }
    }

    private fun go(step: FixStep) { _state.value = _state.value.copy(step = step) }

    private companion object { const val TAG = "FixFlow" }
}

/**
 * Step 2's lists for [step]: the app's own names, then lookups whose app Android didn't
 * tell us (they may be this app's; allowed everywhere only, D3). Names in [handled] are
 * left out. Pure, so the screen recomputes it from state alone.
 */
internal fun fixStepNames(
    step: FixStep.Names,
    items: List<ActivityItem>,
    handled: Set<String>,
): Pair<List<ActivityLogic.FixName>, List<ActivityLogic.FixName>> {
    if (step.packageName == null) {
        return ActivityLogic.fixNames(items, null).filterNot { it.item.host in handled } to emptyList()
    }
    val lookups = ActivityLogic.unattributedNames(items).filterNot { it.item.host in handled }.associateBy { it.item.host }
    val own = ActivityLogic.fixNames(items, step.packageName).filterNot { it.item.host in handled }.map { name ->
        // Also blocked as a lookup: a per-app Allow acts only on connections, so the lookup
        // would stay blocked and the app stay broken. Offer it as an everywhere choice.
        val lookup = lookups[name.item.host] ?: return@map name
        name.copy(
            item = lookup.item.copy(appName = name.item.appName, timestamp = maxOf(name.lastBlocked, lookup.lastBlocked)),
            attempts = name.attempts + lookup.attempts,
            lastBlocked = maxOf(name.lastBlocked, lookup.lastBlocked),
        )
    }
    val ownHosts = own.mapTo(HashSet()) { it.item.host }
    return own to lookups.values.filterNot { it.item.host in ownHosts }
}
