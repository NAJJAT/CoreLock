package com.privacyguard.app.ui.activity

import android.content.Context
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.R
import com.privacyguard.app.core.tracker.DomainClassifier
import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.ui.traffic.AppNames
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.session.BlockSource
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Activity's rows and applies its rule choices. Shared by Activity, the fix flow
 * and (Phase 5) App detail, so a name is described and allowed the same way everywhere.
 *
 * Everything runs on [Dispatchers.IO]: the history database is encrypted and can take
 * seconds to open after boot.
 */
/** What [RuleActionController] needs from storage; a fake in tests. */
interface RuleStore {
    suspend fun isMalwareNow(host: String): Boolean
    suspend fun apply(rule: FilterRule): ActivityRepository.Applied
    suspend fun undo(applied: ActivityRepository.Applied)
    suspend fun removeUserRule(id: String): FilterRule?
    suspend fun restore(rule: FilterRule)
}

class ActivityRepository(private val context: Context) : RuleStore {

    private val db by lazy { AppDatabase.getInstance(context) }
    private val configDb by lazy { ConfigDatabase.getInstance(context) }
    // A private engine for the rules repo: the VPN service reloads its own engine from
    // the database on RuleSyncBus, so the running filter applies the change at once.
    private val rulesRepo by lazy { RulesRepo(configDb.rulesDao(), FilterEngine()) }
    private val blocklists by lazy { BlocklistRepo(db.blocklistDao(), configDb.blocklistToggleDao()) }

    /** Malware answers per name, kept until [rulesChanged] (rules or lists changed). */
    private val malwareCache = LinkedHashMap<String, Boolean>()

    /**
     * The newest rows for [filter], newest first, at most [ActivityLogic.LIST_LIMIT].
     * Blocked: blocked lookups and blocked connections. All: those plus connections that
     * went through (allowed lookups are left out: nearly every visit is a lookup and then
     * a connection, so they would show each visit twice).
     */
    suspend fun load(filter: ActivityFilter, now: Long = System.currentTimeMillis()): List<ActivityItem> = withContext(Dispatchers.IO) {
        val limit = ActivityLogic.LIST_LIMIT
        val lookups = db.dnsQueryDao().newestBlocked(now, limit)
        val connections = when (filter) {
            ActivityFilter.BLOCKED -> db.connectionDao().newestBlocked(now, limit)
            ActivityFilter.ALL -> db.connectionDao().newest(now, limit)
        }
        build(lookups, connections).sortedByDescending { it.timestamp }.take(limit)
    }

    /** Blocked rows of the last hour, for the fix flow. */
    suspend fun lastHourBlocked(now: Long = System.currentTimeMillis()): List<ActivityItem> = withContext(Dispatchers.IO) {
        val from = now - ActivityLogic.FIX_WINDOW_MS
        build(db.dnsQueryDao().blockedBetween(from, now), db.connectionDao().blockedBetween(from, now))
    }

    /**
     * One app's last 24 hours, for App detail's "Connected recently": one item per name
     * and outcome (blocked or allowed), the newest, with [ActivityItem.count] attempts.
     * Rows are merged before they are described: a busy app has thousands of rows, and
     * describing each would classify the same name thousands of times.
     */
    suspend fun forApp(packageName: String, now: Long = System.currentTimeMillis()): List<ActivityItem> = withContext(Dispatchers.IO) {
        val from = now - ActivityLogic.DAY_MS
        fun <T> newestPer(rows: List<T>, key: (T) -> Any): List<Pair<T, Int>> =
            rows.groupBy(key).values.map { group -> group.first() to group.size }  // rows come newest first
        val lookups = newestPer(db.dnsQueryDao().blockedForAppBetween(packageName, from, now)) { NameBlockPolicy.normalize(it.domain) }
        val connections = newestPer(db.connectionDao().forPackageBetween(packageName, from, now)) { c ->
            val name = c.sniHostname?.takeIf { it.isNotBlank() } ?: c.domain?.takeIf { it.isNotBlank() }
            (name?.let(NameBlockPolicy::normalize) ?: c.destinationIp) to c.wasBlocked
        }
        val counts = HashMap<String, Int>()
        lookups.forEach { (q, n) -> counts["d${q.id}"] = n }
        connections.forEach { (c, n) -> counts["c${c.id}"] = n }
        build(lookups.map { it.first }, connections.map { it.first })
            .map { it.copy(count = counts[it.id] ?: 1) }
    }

    /**
     * What CoreLock does with [host] now, as one item the detail actions work on (Domain
     * detail). From the last week's rows, like that screen's numbers; the user's own rule
     * for the name decides first, read fresh, so the item is right just after an action.
     * [packageName] null: the screen is about the name, so only "everywhere" is offered.
     */
    suspend fun nameNow(host: String, packageName: String?, now: Long = System.currentTimeMillis()): ActivityItem = withContext(Dispatchers.IO) {
        val name = NameBlockPolicy.normalize(host)
        val pkg = packageName?.takeUnless { it.isBlank() || AppNames.isUnattributed(it) }
        val since = now - 7 * ActivityLogic.DAY_MS
        rulesChanged()  // one name, read once: never an engine from before the last tap
        val lookups = db.dnsQueryDao().forDomainSince(name, since).take(NAME_ROWS)
        val connections = db.connectionDao().forHostSince(name, since)
            .filter { pkg == null || it.packageName == pkg }
            .take(NAME_ROWS)
        val shown = build(lookups, connections).let(com.privacyguard.app.ui.apps.AppDetailLogic::perName).firstOrNull()
            ?: describe(
                ActivityItem(
                    id = "n:$name", isLookup = pkg == null, timestamp = now, packageName = pkg.orEmpty(),
                    appName = pkg?.let { AppNames.label(context, it, null) }.orEmpty(),
                    host = name, ip = null, port = null, protocol = null,
                    blocked = false, blockSource = null, reason = ActivityReason.ALLOWED,
                ),
            )
        // App-agnostic screen: an app's row must not turn into an app-specific choice.
        val scoped = if (pkg == null) shown.copy(packageName = "", appName = "") else shown
        val engine = cachedEngine ?: FilterEngine().apply { setRules(rulesRepo.userRules()) }.also { cachedEngine = it }
        val rule = engine.domainRuleFor(name, pkg)
        when {
            rule?.source == FilterRule.Source.USER && rule.action == FilterRule.Action.DENY -> scoped.copy(
                blocked = true, reason = ActivityReason.USER_RULE, ruleId = rule.id, ruleLabel = rule.label,
            )
            rule?.source == FilterRule.Source.USER && rule.action == FilterRule.Action.ALLOW && scoped.blocked &&
                scoped.reason != ActivityReason.LOOKUP_BYPASS_PORT -> scoped.copy(
                blocked = false, blockSource = null, reason = ActivityReason.ALLOWED, ruleId = null, ruleLabel = null,
            )
            else -> scoped
        }
    }

    /**
     * What a reload would show differently: the newest blocked lookup and connection (and
     * for All the newest connection). History rows are only inserted, never changed, so an
     * equal signature means an equal list; this costs two indexed reads, a reload hundreds.
     */
    suspend fun signature(filter: ActivityFilter): List<Long?> = withContext(Dispatchers.IO) {
        listOf(
            db.dnsQueryDao().newestBlockedId(),
            db.connectionDao().newestBlockedId(),
            if (filter == ActivityFilter.ALL) db.connectionDao().newestId() else null,
        )
    }

    /** Rules or lists changed: the cached engine and malware answers are out of date. */
    fun rulesChanged() {
        cachedEngine = null
        synchronized(malwareCache) { malwareCache.clear() }
    }

    // Kept between reloads, rebuilt by [rulesChanged]: building it reads every user rule.
    @Volatile private var cachedEngine: FilterEngine? = null
    // App labels change only with an app update or a language change; a new screen rebuilds them.
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun build(lookups: List<DnsQueryEntity>, connections: List<ConnectionEntity>): List<ActivityItem> {
        val engine = cachedEngine ?: FilterEngine().apply { setRules(rulesRepo.userRules()) }.also { cachedEngine = it }
        fun appName(pkg: String, stored: String?): String = names.getOrPut(pkg) { AppNames.label(context, pkg, stored) }

        val fromLookups = lookups
            .filterNot { isOwnPackage(it.appPackage) }
            .map { q ->
                val pkg = q.appPackage.takeUnless { AppNames.isUnattributed(it) }.orEmpty()
                val host = NameBlockPolicy.normalize(q.domain)
                val rule = engine.domainRuleFor(host, pkg.ifBlank { null })
                describe(
                    ActivityItem(
                        id = "d${q.id}", isLookup = true, timestamp = q.timestamp,
                        packageName = pkg, appName = appName(pkg, q.appName),
                        host = host, ip = null, port = null, protocol = null,
                        blocked = q.wasBlocked, blockSource = if (q.wasBlocked) BlockSource.DNS else null,
                        reason = ActivityLogic.reasonFor(q.wasBlocked, true, BlockSource.DNS, rule, q.wasBlocked && isMalware(host)),
                        ruleId = rule?.takeIf { it.action == FilterRule.Action.DENY }?.id,
                        ruleLabel = rule?.takeIf { it.action == FilterRule.Action.DENY }?.label,
                    ),
                )
            }

        val fromConnections = connections
            .filterNot { isOwnPackage(it.packageName) }
            .map { c ->
                val pkg = c.packageName.takeUnless { AppNames.isUnattributed(it) }.orEmpty()
                val host = (c.sniHostname?.takeIf { it.isNotBlank() } ?: c.domain?.takeIf { it.isNotBlank() })
                    ?.let(NameBlockPolicy::normalize)
                val source = if (c.wasBlocked) BlockSource.fromStored(c.blockSource, c.protocol) else null
                val decision = if (c.wasBlocked) {
                    engine.evaluate(
                        uid = -1, pkg = pkg.ifBlank { null }, domain = host, ip = c.destinationIp,
                        port = c.destinationPort, protocol = if (c.protocol == "UDP") 17 else 6,
                        isBackground = c.wasBackground,
                    ).matchedRule
                } else null
                val malware = c.wasBlocked && host != null && source != BlockSource.DOH && source != BlockSource.DOT && isMalware(host)
                describe(
                    ActivityItem(
                        id = "c${c.id}", isLookup = false, timestamp = c.timestamp,
                        packageName = pkg, appName = appName(pkg, c.appName),
                        host = host, ip = c.destinationIp.ifBlank { null },
                        port = c.destinationPort.takeIf { it > 0 }, protocol = c.protocol,
                        blocked = c.wasBlocked, blockSource = source,
                        reason = ActivityLogic.reasonFor(c.wasBlocked, false, source, decision, malware, port = c.destinationPort),
                        ruleId = decision?.takeIf { it.action == FilterRule.Action.DENY }?.id,
                        ruleLabel = decision?.takeIf { it.action == FilterRule.Action.DENY }?.label,
                        encryption = c.encryptionStatus, bytesSent = c.bytesSent, bytesReceived = c.bytesReceived,
                    ),
                )
            }
        return fromLookups + fromConnections
    }

    /** The plain title: "Google ads" when a list names the company and purpose, else the name itself. */
    private fun describe(item: ActivityItem): ActivityItem {
        // An address alone means nothing to a person; for a lookup service the address is
        // in the technical details.
        if (item.host == null && (item.reason == ActivityReason.LOOKUP_BYPASS || item.reason == ActivityReason.LOOKUP_BYPASS_PORT)) {
            return item.copy(title = context.getString(R.string.ui_title_lookup_service))
        }
        val host = item.host ?: return item
        val cls = DomainClassifier.classify(host, item.packageName.ifBlank { null })
        val company = cls.company?.let(ActivityLogic::plainCompany)
        val title = when {
            company == null -> host
            cls.purpose == DomainPurpose.ADS -> context.getString(R.string.trf_p_ads, company)
            cls.purpose == DomainPurpose.ANALYTICS -> context.getString(R.string.trf_p_analytics, company)
            cls.purpose == DomainPurpose.TRACKING -> context.getString(R.string.trf_p_tracking, company)
            cls.purpose == DomainPurpose.SERVICE -> context.getString(R.string.trf_p_service, company)
            else -> host
        }
        return item.copy(title = title, purpose = cls.purpose, company = company)
    }

    private suspend fun isMalware(host: String): Boolean {
        synchronized(malwareCache) { malwareCache[host] }?.let { return it }
        val answer = blocklists.isSecurityThreat(host)
        synchronized(malwareCache) {
            // Bounded: a long history has thousands of names; the newest matter.
            if (malwareCache.size >= MALWARE_CACHE_MAX) malwareCache.remove(malwareCache.keys.first())
            malwareCache[host] = answer
        }
        return answer
    }

    /** The current malware answer for [host], not a cached one: checked before every Allow. */
    override suspend fun isMalwareNow(host: String): Boolean = blocklists.isSecurityThreat(NameBlockPolicy.normalize(host))

    // ── Rules ───────────────────────────────────────────────────────────────────

    /** What [apply] replaced, so Undo can put it back (or delete a rule that is new). */
    data class Applied(val rule: FilterRule, val previous: FilterRule?)

    override suspend fun apply(rule: FilterRule): Applied = withContext(Dispatchers.IO) {
        val previous = rulesRepo.allRules().firstOrNull { it.id == rule.id }
        rulesRepo.upsertRule(rule)
        Applied(rule, previous)
    }

    override suspend fun undo(applied: Applied) = withContext(Dispatchers.IO) {
        if (applied.previous != null) rulesRepo.upsertRule(applied.previous) else rulesRepo.deleteRule(applied.rule.id)
    }

    /** "Remove my rule": only a user rule, never a list or system rule. */
    override suspend fun removeUserRule(id: String): FilterRule? = withContext(Dispatchers.IO) {
        val rule = rulesRepo.allRules().firstOrNull { it.id == id && it.source == FilterRule.Source.USER } ?: return@withContext null
        rulesRepo.deleteRule(id)
        rule
    }

    /** Puts a removed rule back (Undo after "Remove my rule"). */
    override suspend fun restore(rule: FilterRule) = withContext(Dispatchers.IO) { rulesRepo.upsertRule(rule) }

    /** The tables behind [load]; a write to any of them can change the list. */
    fun historyTables(): Array<String> = arrayOf("dns_queries", "connections")

    fun database(): AppDatabase = db
    fun configDatabase(): ConfigDatabase = configDb

    private fun isOwnPackage(pkg: String?): Boolean =
        pkg == BuildConfig.APPLICATION_ID || pkg?.startsWith("com.privacyguard.app") == true

    companion object {
        private const val MALWARE_CACHE_MAX = 2_000
        /** Newest rows per kind for one name: enough to find its latest block. */
        private const val NAME_ROWS = 200
    }
}
