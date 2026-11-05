package com.privacyguard.app.ui.apps

import com.privacyguard.app.R
import com.privacyguard.app.ui.components.WordTone
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.ConnectionProfile

/** What the user chose for an app as a whole (DESIGN_SPEC_1.1.md §6.4). */
enum class AppStatus(val label: Int, val description: Int?) {
    ALLOWED(R.string.ui_app_allowed, null),
    BLOCKED_IN_BACKGROUND(R.string.ui_app_blocked_background, R.string.ui_app_blocked_background_desc),
    BLOCKED(R.string.ui_app_blocked, R.string.ui_app_blocked_desc),
}

/**
 * The plain risk word per app (owner's decision D9, spec §6.3.1), in table order: the
 * first that matches wins. Only data the Apps list already had in 1.0.
 */
enum class RiskWord(val label: Int, val explanation: Int, val tone: WordTone) {
    COULD_WATCH(R.string.ui_risk_watch, R.string.ui_risk_watch_desc, WordTone.DANGER),
    HIGH_RISK(R.string.ui_risk_high, R.string.ui_risk_high_desc, WordTone.ATTENTION),
    UNPROTECTED(R.string.ui_risk_unprotected, R.string.ui_risk_unprotected_desc, WordTone.ATTENTION),
    TRACKERS(R.string.ui_risk_trackers, R.string.ui_risk_trackers_desc, WordTone.MUTED),
    BACKGROUND(R.string.ui_risk_background, R.string.ui_risk_background_desc, WordTone.MUTED),
    NO_ACTIVITY(R.string.ui_risk_none, R.string.ui_risk_none_desc, WordTone.MUTED),
    NORMAL(R.string.ui_risk_normal, R.string.ui_risk_normal_desc, WordTone.MUTED),
}

enum class AppsFilter(val label: Int) {
    ALL(R.string.ui_filter_all),
    BLOCKED(R.string.ui_filter_blocked),
    ACTIVE_NOW(R.string.ui_apps_active_now),
}

/** "Sort by" on Apps. The first four are the spec's; risk and destinations keep 1.0's sorts. */
enum class AppSort(val label: Int) {
    MOST_BLOCKED(R.string.ui_sort_most_blocked),
    NAME(R.string.ui_sort_name),
    DATA(R.string.ui_sort_most_data),
    BACKGROUND(R.string.ui_sort_background),
    RISK(R.string.ui_sort_risk),
    DESTINATIONS(R.string.ui_sort_destinations),
}

object AppsLogic {

    /** Same threshold as 1.0's stalkerware badge and "HIGH" risk. */
    const val HIGH = 70

    /** "Active now": traffic in the last 5 minutes (spec §6.3). */
    const val ACTIVE_WINDOW_MS = 5 * 60_000L

    /** Risk of an app with no traffic in the window: LOW, sorted after apps with traffic. */
    const val NO_TRAFFIC_RISK = 5

    fun riskWord(item: AppRiskItem): RiskWord = when {
        item.stalkerwareScore >= HIGH -> RiskWord.COULD_WATCH
        item.maxRiskScore >= HIGH -> RiskWord.HIGH_RISK
        item.cleartextCount > 0 -> RiskWord.UNPROTECTED
        item.suspiciousCount > 0 -> RiskWord.TRACKERS
        item.backgroundCount > 0 -> RiskWord.BACKGROUND
        item.totalDestinations == 0 -> RiskWord.NO_ACTIVITY
        else -> RiskWord.NORMAL
    }

    /**
     * Highest risk among profiles seen since [since]; without one, the risk from the
     * window's connections, or [NO_TRAFFIC_RISK] when the app had none.
     */
    fun windowRisk(profiles: List<ConnectionProfile>, since: Long, connectionRisk: Int?): Int =
        profiles.filter { it.lastSeen >= since }.maxOfOrNull { it.riskScore } ?: connectionRisk ?: NO_TRAFFIC_RISK

    fun packageBlockRuleId(packageName: String) = "pkg:block:$packageName"
    fun backgroundBlockRuleId(packageName: String) = "bg:block:$packageName"

    /**
     * A user rule about [packageName] as a whole: Block with no destination or other
     * condition. "Block ads.example.com for this app" (D8) is not one: 1.0 counted it, so
     * an app with one blocked name showed as blocked, and "Allowed" deleted that name rule.
     */
    fun isAppLevelBlock(rule: FilterRule, packageName: String): Boolean =
        rule.source == FilterRule.Source.USER &&
            rule.action == FilterRule.Action.DENY &&
            rule.matchPackage == packageName &&
            rule.matchUid == null && rule.matchDomain == null && rule.matchIp == null &&
            rule.matchPort == null && rule.matchProtocol == null && rule.matchEncryption == null

    /** True for a background-only rule; anything else (null, and 1.0's false) blocks always. */
    private fun backgroundOnly(rule: FilterRule) = rule.matchBackground == true

    /** The app's status from its enabled app-level rules; a full block wins over background. */
    fun statusOf(rules: List<FilterRule>, packageName: String): AppStatus {
        val own = rules.filter { it.isEnabled && isAppLevelBlock(it, packageName) }
        return when {
            own.any { !backgroundOnly(it) } -> AppStatus.BLOCKED
            own.any { backgroundOnly(it) } -> AppStatus.BLOCKED_IN_BACKGROUND
            else -> AppStatus.ALLOWED
        }
    }

    /** What to delete and write to make [packageName]'s status [target]. */
    data class StatusChange(val remove: List<FilterRule>, val upsert: List<FilterRule>) {
        val isEmpty: Boolean get() = remove.isEmpty() && upsert.isEmpty()
    }

    /**
     * One status at a time, so the segmented control always tells the truth: Blocked
     * removes a background-only rule (it would add nothing), and the other way round.
     * Disabled rules are left alone: the user switched them off in Rules on purpose.
     * Every rule written is a user rule at [FilterRule.HIGH_PRIORITY] (CLAUDE.md §5.3).
     */
    fun changeTo(
        target: AppStatus,
        packageName: String,
        rules: List<FilterRule>,
        blockLabel: String,
        backgroundLabel: String,
    ): StatusChange {
        val own = rules.filter { it.isEnabled && isAppLevelBlock(it, packageName) }
        val full = own.filterNot(::backgroundOnly)
        val background = own.filter(::backgroundOnly)
        fun rule(id: String, label: String, bg: Boolean?) = FilterRule(
            id = id, label = label,
            action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchPackage = packageName, matchBackground = bg,
        )
        return when (target) {
            AppStatus.ALLOWED -> StatusChange(remove = own, upsert = emptyList())
            AppStatus.BLOCKED -> StatusChange(
                remove = background,
                upsert = if (full.isEmpty()) listOf(rule(packageBlockRuleId(packageName), blockLabel, null)) else emptyList(),
            )
            AppStatus.BLOCKED_IN_BACKGROUND -> StatusChange(
                remove = full,
                upsert = if (background.isEmpty()) listOf(rule(backgroundBlockRuleId(packageName), backgroundLabel, true)) else emptyList(),
            )
        }
    }

    /**
     * What Undo applies after [change]: delete the rules that are new, write back the ones
     * removed or overwritten. [before]: every rule by id, read just before [change].
     */
    fun reverseOf(change: StatusChange, before: Map<String, FilterRule>): StatusChange = StatusChange(
        remove = change.upsert.filter { it.id !in before },
        upsert = change.remove + change.upsert.mapNotNull { before[it.id] },
    )

    /** The list as shown: search (name or package, as 1.0), filter, then sort. */
    fun shown(items: List<AppRiskItem>, query: String, filter: AppsFilter, sort: AppSort): List<AppRiskItem> {
        val q = query.trim()
        return items
            .asSequence()
            .filter { q.isEmpty() || it.appName.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .filter {
                when (filter) {
                    AppsFilter.ALL -> true
                    AppsFilter.BLOCKED -> it.status != AppStatus.ALLOWED
                    AppsFilter.ACTIVE_NOW -> it.activeNow
                }
            }
            .sortedWith(comparator(sort))
            .toList()
    }

    /** Riskiest first; "No activity" last: an app that sent nothing is the least worrying. */
    internal fun riskRank(word: RiskWord): Int = when (word) {
        RiskWord.NO_ACTIVITY -> RiskWord.entries.size
        else -> word.ordinal
    }

    private val byName: Comparator<AppRiskItem> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }

    private fun comparator(sort: AppSort): Comparator<AppRiskItem> = when (sort) {
        AppSort.MOST_BLOCKED -> compareByDescending<AppRiskItem> { it.blockedToday }.then(byName)
        AppSort.NAME -> byName
        AppSort.DATA -> compareByDescending<AppRiskItem> { it.totalBytesOut }.then(byName)
        AppSort.BACKGROUND -> compareByDescending<AppRiskItem> { it.backgroundCount }.then(byName)
        // The word first, so "Could watch you" leads, then 1.0's score within a word.
        AppSort.RISK -> compareBy<AppRiskItem> { riskRank(riskWord(it)) }.thenByDescending { it.maxRiskScore }.then(byName)
        AppSort.DESTINATIONS -> compareByDescending<AppRiskItem> { it.totalDestinations }.then(byName)
    }
}
