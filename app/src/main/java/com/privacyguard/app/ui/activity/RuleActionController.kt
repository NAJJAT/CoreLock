package com.privacyguard.app.ui.activity

import android.content.Context
import android.util.Log
import com.privacyguard.app.R
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A short message after an action, with Undo when the change can be taken back. */
data class ActivityMessage(val text: String, val undo: (() -> Unit)? = null, val id: Long = System.nanoTime())

/**
 * The one place Activity, the fix flow and App detail turn a choice into a rule
 * (owner's decision D8). Every rule comes from [ActivityRules]; an Allow for a name on a
 * malware list is never created without the user confirming the warning, and the name
 * is checked against the lists again at the moment of the tap, not from the list's cache.
 */
class RuleActionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: RuleStore,
) {
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** An Allow waiting for the user to confirm the malware warning. */
    data class PendingAllow(val item: ActivityItem, val action: ActivityAction)

    private val _malwareWarning = MutableStateFlow<PendingAllow?>(null)
    val malwareWarning: StateFlow<PendingAllow?> = _malwareWarning.asStateFlow()

    private val _message = MutableStateFlow<ActivityMessage?>(null)
    val message: StateFlow<ActivityMessage?> = _message.asStateFlow()

    fun messageShown(id: Long) { if (_message.value?.id == id) _message.value = null }
    fun dismissMalwareWarning() { _malwareWarning.value = null }

    /**
     * Runs [action] for [item]. [onDone] runs after a rule changed (or on Keep blocked),
     * so the caller can close its sheet or go back a step. [onUndone] runs after Undo
     * succeeded, so a list that hid the row can show it again.
     */
    fun perform(
        action: ActivityAction,
        item: ActivityItem,
        confirmedMalware: Boolean = false,
        onUndone: () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            try {
                if (performNow(action, item, confirmedMalware, onUndone)) onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Action failed: ${e.javaClass.simpleName}")
                _message.value = ActivityMessage(str(R.string.ui_action_failed))
            }
        }
    }

    /** True when the action finished; false while the malware warning waits for an answer. */
    private suspend fun performNow(action: ActivityAction, item: ActivityItem, confirmedMalware: Boolean, onUndone: () -> Unit): Boolean {
        val host = item.host
        when (action) {
            ActivityAction.KEEP_BLOCKED -> Unit

            ActivityAction.ALLOW_FOR_APP, ActivityAction.ALLOW_EVERYWHERE, ActivityAction.ALLOW_ANYWAY -> {
                host ?: return true
                val malware = repo.isMalwareNow(host)
                if (malware && !confirmedMalware) {
                    _malwareWarning.value = PendingAllow(item, action)
                    return false
                }
                _malwareWarning.value = null
                // Per app only where the app is known at the connection (D3); a malware
                // override stays as narrow as the place it was found.
                val forApp = !ActivityLogic.everywhereOnly(item) &&
                    (action == ActivityAction.ALLOW_FOR_APP || action == ActivityAction.ALLOW_ANYWAY)
                val rule = if (forApp) {
                    ActivityRules.allowForApp(host, item.packageName, str(R.string.rule_allow_domain_for_app, host, item.appName))
                        .copy(overridesSecurity = malware)
                } else {
                    ActivityRules.allowEverywhere(
                        host,
                        str(if (malware) R.string.rule_allow_domain_risky else R.string.rule_allow_domain, host),
                        overridesSecurity = malware,
                    )
                }
                applyWithUndo(rule, str(R.string.ui_allowed_try_again), onUndone)
            }

            ActivityAction.BLOCK_FOR_APP -> {
                val label = str(R.string.rule_block_domain_for_app, host ?: item.ip.orEmpty(), item.appName)
                ActivityRules.blockForApp(host, item.ip, item.packageName, label)?.let { applyWithUndo(it, str(R.string.ui_blocked_done), onUndone) }
            }

            ActivityAction.BLOCK_EVERYWHERE -> {
                val label = str(R.string.rule_block_domain, host ?: item.ip.orEmpty())
                ActivityRules.blockEverywhere(host, item.ip, label)?.let { applyWithUndo(it, str(R.string.ui_blocked_done), onUndone) }
            }

            ActivityAction.REMOVE_MY_RULE -> {
                val id = item.ruleId ?: return true
                val removed = repo.removeUserRule(id) ?: return true
                _message.value = ActivityMessage(str(R.string.ui_rule_removed), undo = { scope.launch { if (safely { repo.restore(removed) }) onUndone() } })
            }
        }
        return true
    }

    private suspend fun applyWithUndo(rule: FilterRule, text: String, onUndone: () -> Unit) {
        val applied = repo.apply(rule)
        _message.value = ActivityMessage(text, undo = { scope.launch { if (safely { repo.undo(applied) }) onUndone() } })
    }

    /** True when [block] finished. */
    private suspend fun safely(block: suspend () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Undo failed: ${e.javaClass.simpleName}")
            _message.value = ActivityMessage(str(R.string.ui_action_failed))
            false
        }
    }

    private companion object { const val TAG = "RuleActions" }
}
