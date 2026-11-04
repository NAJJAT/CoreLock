package com.privacyguard.app.ui.activity

import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.ui.home.HomeLogic
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.session.BlockSource
import com.privacyguard.vpn.firewall.DnsBypassGuard
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import java.util.TimeZone

/** Activity's two views (DESIGN_SPEC_1.1.md §6.2). Blocked is the default. */
enum class ActivityFilter { BLOCKED, ALL }

/** Time groups, newest first. "Earlier" runs back to the end of history retention. */
enum class ActivityGroup { JUST_NOW, TODAY, YESTERDAY, EARLIER }

/**
 * Why a row was stopped, in the words a row shows. Worked out when the list is shown
 * (ADR 0010, no schema change): from the user's rules and the lists as they are now.
 */
enum class ActivityReason {
    /** An ad, tracker or telemetry list (or the ad-SDK policy, which blocks the same kind of names). */
    ADS_LIST,
    /** A malware or phishing list. Allowing it needs the security warning first. */
    MALWARE_LIST,
    /** One of the user's own Block rules. */
    USER_RULE,
    /** An encrypted lookup service that would skip the filter (DoH, by name or address). */
    LOOKUP_BYPASS,
    /** Encrypted lookups on their own port (DoT, 853): dropped by port, no rule can open it. */
    LOOKUP_BYPASS_PORT,
    /** A security setting (unencrypted or outdated connections, port policy) or a reason no longer known. */
    SETTING,
    ALLOWED,
}

/** What the detail sheet and the fix flow can offer for one row. */
enum class ActivityAction {
    ALLOW_FOR_APP, ALLOW_EVERYWHERE, KEEP_BLOCKED, ALLOW_ANYWAY, REMOVE_MY_RULE, BLOCK_FOR_APP, BLOCK_EVERYWHERE,
}

/**
 * One blocked lookup, blocked connection or connection that went through.
 *
 * [packageName] is blank when Android did not say which app asked: most lookups go
 * through Android's own resolver (CLAUDE.md §16.1), so a per-app choice is impossible.
 */
data class ActivityItem(
    val id: String,
    val isLookup: Boolean,
    val timestamp: Long,
    val packageName: String,
    val appName: String,
    /** Server name, normalized (lower case, no trailing dot); null when only the address is known. */
    val host: String?,
    val ip: String?,
    val port: Int?,
    val protocol: String?,
    val blocked: Boolean,
    val blockSource: BlockSource?,
    val reason: ActivityReason,
    /** The user rule behind [ActivityReason.USER_RULE]; what "Remove my rule" deletes. */
    val ruleId: String? = null,
    val ruleLabel: String? = null,
    val encryption: String? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    /** Plain name for the row ("Google ads"); the host when nothing better is known. */
    val title: String = host ?: ip.orEmpty(),
    val purpose: DomainPurpose? = null,
    /** The owner a list names ("Google"), without legal form; null when unknown. */
    val company: String? = null,
    /** Identical rows in the same time group, shown as one ("4 times"); this row is the newest. */
    val count: Int = 1,
) {
    val appKnown: Boolean get() = packageName.isNotBlank()
}

object ActivityLogic {

    /** "Just now" is the last five minutes (DESIGN_SPEC_1.1.md §6.2). */
    const val JUST_NOW_MS = 5L * 60L * 1000L

    /** The fix flow looks at the last hour: what broke "just now", not last week. */
    const val FIX_WINDOW_MS = 60L * 60L * 1000L

    /** App detail shows the last 24 hours, like the Apps list's numbers. */
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Rows per list. History keeps up to 90 days; a list of every row would take seconds
     * to read and render, and nobody scrolls through thousands of rows.
     */
    const val LIST_LIMIT = 500

    fun group(timestamp: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): ActivityGroup {
        val today = HomeLogic.startOfDay(now, zone)
        return when {
            now - timestamp < JUST_NOW_MS -> ActivityGroup.JUST_NOW
            timestamp >= today -> ActivityGroup.TODAY
            // The day before: start of the day of a moment just before midnight (DST-safe).
            timestamp >= HomeLogic.startOfDay(today - 1, zone) -> ActivityGroup.YESTERDAY
            else -> ActivityGroup.EARLIER
        }
    }

    /**
     * The rows in their groups, groups in order, each newest first; empty groups left out.
     * Repeats of the same thing (same app, destination and reason) inside one group become
     * one row with a count: apps retry blocked names, and a list of the same line ten times
     * hides everything else.
     */
    fun grouped(items: List<ActivityItem>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<ActivityGroup, List<ActivityItem>>> =
        items.sortedByDescending { it.timestamp }
            .groupBy { group(it.timestamp, now, zone) }
            .toSortedMap()
            .map { (g, rows) -> g to merged(rows) }

    /** Keeps the newest of each kind, with how many there were. */
    fun merged(rows: List<ActivityItem>): List<ActivityItem> =
        // Named rows by name: an Allow acts on one name. IP-only rows by what the row shows,
        // since one service answers from many addresses and the user can't tell them apart.
        rows.groupBy { MergeKey(it.packageName, it.host ?: "title:${it.title}", it.reason, it.blocked) }
            .values
            .map { same -> same.maxBy { it.timestamp }.copy(count = same.sumOf { it.count }) }
            .sortedByDescending { it.timestamp }

    private data class MergeKey(val pkg: String, val target: String?, val reason: ActivityReason, val blocked: Boolean)

    /**
     * Why a row was stopped. The order follows NameBlockPolicy (CLAUDE.md §5.3): the
     * user's Block rule first, then the malware lists, then everything else.
     *
     * @param userRule the first user rule that matches the row now, if any
     * @param onMalwareList the name or a parent of it is on a malware or phishing list
     * @param onBlockList the name or a parent of it is on an ad, tracker or telemetry list now.
     *   Lookups store no source, so without it a name blocked by a rule deleted since
     *   would be shown as listed; only a list we can find is named.
     * @param port the destination port of a connection: plain lookups on port 53 that
     *   skip CoreLock's resolver (over TCP or IPv6) are dropped with no source recorded
     */
    fun reasonFor(
        blocked: Boolean,
        isLookup: Boolean,
        blockSource: BlockSource?,
        userRule: FilterRule?,
        onMalwareList: Boolean,
        port: Int? = null,
        onBlockList: Boolean = true,
    ): ActivityReason {
        if (!blocked) return ActivityReason.ALLOWED
        // Ports closed before any rule runs (PrivacyVpnService): no rule can open them, so
        // they come first, as in the packet path.
        if (blockSource == BlockSource.DOT || (blockSource == null && !isLookup && port != null && DnsBypassGuard.isUnfilteredPlainDns(port)))  {
            return ActivityReason.LOOKUP_BYPASS_PORT
        }
        if (userRule != null && userRule.action == FilterRule.Action.DENY && userRule.source == FilterRule.Source.USER) {
            return ActivityReason.USER_RULE
        }
        return when {
            blockSource == BlockSource.DOH -> ActivityReason.LOOKUP_BYPASS
            isLookup || blockSource == BlockSource.SNI || blockSource == BlockSource.QUIC || blockSource == BlockSource.DNS ->
                when {
                    onMalwareList -> ActivityReason.MALWARE_LIST
                    onBlockList -> ActivityReason.ADS_LIST
                    else -> ActivityReason.SETTING
                }
            // A connection without a recorded source: a port or security policy, or a rule
            // that was removed since. Never claim a list we can't name.
            else -> ActivityReason.SETTING
        }
    }

    /**
     * A company name as people say it: "Google LLC" reads as "Google". Only a legal form at
     * the end is removed, and never the whole name.
     */
    fun plainCompany(name: String): String {
        val trimmed = name.trim()
        val plain = LEGAL_FORM.replace(trimmed, "").trim()
        return plain.ifEmpty { trimmed }
    }

    // Case-sensitive on purpose: "AG" or "AB" as written by the company, not a word.
    private val LEGAL_FORM = Regex(
        """[,\s]+(LLC|L\.L\.C\.|Inc\.?|Incorporated|Ltd\.?|Limited|Corp\.?|Corporation|Co\.|GmbH|AG|B\.V\.|BV|N\.V\.|S\.A\.|SA|SAS|plc|PLC|Pty Ltd|Oy|AB|ASA|S\.r\.l\.|KK)$""",
    )

    /**
     * A per-app choice needs a connection whose app is known. Lookups usually come from
     * Android's resolver, so the app is unknown when the name is decided (owner's decision D3).
     */
    fun everywhereOnly(item: ActivityItem): Boolean = item.isLookup || !item.appKnown

    /** The actions the detail sheet offers for [item], in display order (primary first). */
    fun actionsFor(item: ActivityItem): List<ActivityAction> {
        val hasTarget = item.host != null || item.ip != null
        return when (item.reason) {
            ActivityReason.ALLOWED -> when {
                !hasTarget -> emptyList()
                item.appKnown -> listOf(ActivityAction.BLOCK_FOR_APP, ActivityAction.BLOCK_EVERYWHERE)
                else -> listOf(ActivityAction.BLOCK_EVERYWHERE)
            }
            ActivityReason.USER_RULE ->
                if (item.ruleId != null) listOf(ActivityAction.REMOVE_MY_RULE, ActivityAction.KEEP_BLOCKED)
                else listOf(ActivityAction.KEEP_BLOCKED)
            // Keep blocked is the primary choice; "Allow anyway" shows the warning first.
            ActivityReason.MALWARE_LIST ->
                if (item.host != null) listOf(ActivityAction.KEEP_BLOCKED, ActivityAction.ALLOW_ANYWAY)
                else listOf(ActivityAction.KEEP_BLOCKED)
            ActivityReason.ADS_LIST -> when {
                item.host == null -> listOf(ActivityAction.KEEP_BLOCKED)
                everywhereOnly(item) -> listOf(ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED)
                else -> listOf(ActivityAction.ALLOW_FOR_APP, ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED)
            }
            // Opening an encrypted lookup service lets that app skip every list. It stays a
            // deliberate expert choice in Rules (where a user's own server can be allowed),
            // not a one-tap button next to a blocked row.
            ActivityReason.LOOKUP_BYPASS, ActivityReason.LOOKUP_BYPASS_PORT, ActivityReason.SETTING ->
                listOf(ActivityAction.KEEP_BLOCKED)
        }
    }

    // ── Fix flow ("Something not working?", §6.2) ───────────────────────────────

    data class FixApp(val packageName: String, val appName: String, val blocked: Int, val lastBlocked: Long)

    /** One blocked name in the fix flow, with the row that stands for all its attempts. */
    data class FixName(val item: ActivityItem, val attempts: Int, val lastBlocked: Long)

    /** Apps with blocks in [items] (already the last hour), most recent first. */
    fun fixApps(items: List<ActivityItem>): List<FixApp> =
        items.filter { fixable(it) && it.appKnown }
            .groupBy { it.packageName }
            .map { (pkg, rows) -> FixApp(pkg, rows.first().appName, rows.size, rows.maxOf { it.timestamp }) }
            .sortedByDescending { it.lastBlocked }

    /**
     * Blocked names, most likely cause of a broken app first: an app's own servers,
     * content delivery and services break apps; ads and trackers rarely do. Then by
     * attempts (apps retry what they need), then by time.
     *
     * [packageName] null means every blocked name (the user isn't sure which app).
     */
    fun fixNames(items: List<ActivityItem>, packageName: String?): List<FixName> =
        items.filter { fixable(it) && (packageName == null || it.packageName == packageName) }
            .groupBy { it.host }
            .map { (_, rows) ->
                val newest = rows.maxBy { it.timestamp }
                // Per app only when every attempt was a connection of one known app (D3): a
                // lookup block stays a block for every app, so a per-app Allow wouldn't fix it;
                // and a name several apps hit (Not sure) isn't one app's to allow.
                val oneApp = rows.all { it.packageName == newest.packageName }
                val representative = when {
                    oneApp && rows.none(::everywhereOnly) -> newest
                    else -> (rows.firstOrNull(::everywhereOnly) ?: newest).let { r ->
                        if (everywhereOnly(r)) r else r.copy(packageName = "")
                    }.copy(timestamp = newest.timestamp)
                }
                FixName(representative, rows.size, newest.timestamp)
            }
            .sortedWith(
                compareBy<FixName> { if (breaksApps(it.item.purpose)) 0 else 1 }
                    .thenByDescending { it.attempts }
                    .thenByDescending { it.lastBlocked },
            )

    /** Lookups whose app Android didn't tell us: shown under the chosen app's names, marked "everywhere only". */
    fun unattributedNames(items: List<ActivityItem>): List<FixName> =
        fixNames(items.filter { !it.appKnown }, packageName = null)

    // One rule for steps 1 and 2: an app is offered only if step 2 has a name to show for it.
    // Rows without a name (IP only) have no one-tap fix; they stay visible in Activity.
    private fun fixable(item: ActivityItem): Boolean = item.blocked && item.host != null

    private fun breaksApps(purpose: DomainPurpose?): Boolean = when (purpose) {
        DomainPurpose.ADS, DomainPurpose.ANALYTICS, DomainPurpose.TRACKING -> false
        else -> true
    }
}

/**
 * The rules Activity, the fix flow and App detail create (owner's decision D8): a
 * ViewModel helper over the existing rule model, no new rule logic. Every rule is a user
 * rule with [FilterRule.HIGH_PRIORITY] (CLAUDE.md §5.3), so it isn't outranked by an
 * older rule, and has a fixed id, so tapping twice changes nothing and Undo knows what
 * to delete.
 */
object ActivityRules {

    fun allowForApp(host: String, packageName: String, label: String): FilterRule {
        val h = NameBlockPolicy.normalize(host)
        return FilterRule(
            id = "user:allow:app:$packageName:domain:$h",
            label = label,
            action = FilterRule.Action.ALLOW,
            source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchPackage = packageName,
            matchDomain = h,
        )
    }

    /**
     * [overridesSecurity] only after the user confirmed the malware warning: it is the one
     * thing that lets a name on a malware or phishing list through.
     */
    fun allowEverywhere(host: String, label: String, overridesSecurity: Boolean = false): FilterRule {
        val h = NameBlockPolicy.normalize(host)
        return FilterRule(
            id = "user:allow:domain:$h",
            label = label,
            action = FilterRule.Action.ALLOW,
            source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchDomain = h,
            overridesSecurity = overridesSecurity,
        )
    }

    /** A Block for [host] (or the address when no name is known) in one app. */
    fun blockForApp(host: String?, ip: String?, packageName: String, label: String): FilterRule? {
        val h = host?.let(NameBlockPolicy::normalize)
        if (h == null && ip.isNullOrBlank()) return null
        return FilterRule(
            id = if (h != null) "user:block:app:$packageName:domain:$h" else "user:block:app:$packageName:ip:$ip",
            label = label,
            action = FilterRule.Action.DENY,
            source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchPackage = packageName,
            matchDomain = h,
            matchIp = ip.takeIf { h == null },
        )
    }

    /** Same ids as the 1.0 Traffic screen's Block button, so either screen can undo the other's rule. */
    fun blockEverywhere(host: String?, ip: String?, label: String): FilterRule? {
        val h = host?.let(NameBlockPolicy::normalize)
        if (h == null && ip.isNullOrBlank()) return null
        return FilterRule(
            id = if (h != null) "user:block:domain:$h" else "user:block:ip:$ip",
            label = label,
            action = FilterRule.Action.DENY,
            source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchDomain = h,
            matchIp = ip.takeIf { h == null },
        )
    }
}
