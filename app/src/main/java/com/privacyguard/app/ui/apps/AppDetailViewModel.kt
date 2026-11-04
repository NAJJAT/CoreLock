package com.privacyguard.app.ui.apps

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.R
import com.privacyguard.app.core.apk.ApkScanner
import com.privacyguard.app.core.detection.PermissionMismatchDetector
import com.privacyguard.app.core.detection.PermissionMismatchFinding
import com.privacyguard.app.core.stats.ActiveConnectionInfo
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsDomainSummary
import com.privacyguard.app.data.db.host
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.app.ui.activity.ActivityAction
import com.privacyguard.app.ui.activity.ActivityItem
import com.privacyguard.app.ui.activity.ActivityMessage
import com.privacyguard.app.ui.activity.ActivityRepository
import com.privacyguard.app.ui.activity.RuleActionController
import com.privacyguard.app.ui.traffic.AppNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.privacyguard.app.ui.launchWhileObserved

data class DomainRow(
    val domain: String,
    val count: Int,
    val backgroundCount: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val trackerName: String?,
    /** Owner from DomainClassifier (the classifier every screen uses); null when unknown. */
    val company: String?,
)

data class ActivityHourRow(
    val hour: Int,
    val allowed: Int,
    val blocked: Int,
    val suspicious: Int,
)

data class RecentActivityRow(
    val timestamp: Long,
    val label: String,
    val detail: String,
    val blocked: Boolean,
    val idle: Boolean,
)

/** "Connected recently": unknown until the database answers, never a made-up empty list. */
sealed interface ConnectedRecently {
    data object Loading : ConnectedRecently
    data class Loaded(val sections: List<AppDetailLogic.Section>) : ConnectedRecently
    data object Error : ConnectedRecently
}

data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    /** The Apps list's item for this app: risk word, grade, blocked today. */
    val summary: AppRiskItem? = null,
    /** From the rules themselves, so the control moves as soon as a rule is written. */
    val status: AppStatus? = null,
    val connected: ConnectedRecently = ConnectedRecently.Loading,
    val domains: List<DomainRow> = emptyList(),
    val detectedSdks: List<TrackerEntry> = emptyList(),
    val mismatchFindings: List<PermissionMismatchFinding> = emptyList(),
    val declaredPermissions: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isLoadingDomains: Boolean = true,
    val isLoadingMismatch: Boolean = true,
    val activityTimeline: List<ActivityHourRow> = List(24) { ActivityHourRow(it, 0, 0, 0) },
    val dnsQueryCount: Int = 0,
    val idleDnsQueryCount: Int = 0,
    val connectionCount: Int = 0,
    val idleConnectionCount: Int = 0,
    val dnsDomains: List<DnsDomainSummary> = emptyList(),
    val recentActivity: List<RecentActivityRow> = emptyList(),
) {
    val isBlocked: Boolean get() = status == AppStatus.BLOCKED
    val isBackgroundBlocked: Boolean get() = status == AppStatus.BLOCKED_IN_BACKGROUND
}

/**
 * App detail (DESIGN_SPEC_1.1.md §6.4). Pushed, not polled: read again when Room reports
 * a write to this app's history tables, the rules change, or the app's open connections
 * change, at most every [RELOAD_MIN_MS] and only while the screen is shown. 1.0 ran a
 * `while (true)` loop every 5 s (CLAUDE.md §16.2 item 4).
 */
class AppDetailViewModel internal constructor(
    app: Application,
    savedState: SavedStateHandle,
    private val apps: AppsRepository,
    private val activity: ActivityRepository,
) : AndroidViewModel(app) {

    constructor(app: Application, savedState: SavedStateHandle) :
        this(app, savedState, AppsRepository(app), ActivityRepository(app))

    private val packageName: String = savedState["packageName"] ?: ""
    private val appName: String = AppNames.label(app, packageName, savedState["appName"])

    private val db by lazy { AppDatabase.getInstance(app) }
    private val metadataRepo by lazy { MetadataRepo(db.connectionProfileDao()) }

    // Loaded once and awaited by every analysis: the APK scan and refreshes can finish
    // before the permission lookup, and analysing with an empty set reported "declares
    // no permission" for apps that do (e.g. WhatsApp and contacts).
    private val permissionsLookup by lazy {
        viewModelScope.async(Dispatchers.IO) { loadRequestedPermissions(packageName) }
    }

    private val _state = MutableStateFlow(AppDetailState(packageName = packageName, appName = appName))
    val state: StateFlow<AppDetailState> = _state.asStateFlow()

    /** Name rules from "Connected recently" (D8), with the malware warning and Undo. */
    val ruleActions = RuleActionController(app, viewModelScope, activity)

    private val _selected = MutableStateFlow<ActivityItem?>(null)
    val selected: StateFlow<ActivityItem?> = _selected.asStateFlow()

    private val _message = MutableStateFlow<ActivityMessage?>(null)
    /** After a status change: what changed, with Undo. */
    val message: StateFlow<ActivityMessage?> = _message.asStateFlow()

    private var statusJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launchWhileObserved(_state) { observe() }
        loadMismatchSignals()
        scanApk()
    }

    fun select(item: ActivityItem?) { _selected.value = item }
    fun messageShown(id: Long) { if (_message.value?.id == id) _message.value = null }

    fun perform(action: ActivityAction, item: ActivityItem, confirmedMalware: Boolean = false) =
        ruleActions.perform(action, item, confirmedMalware) { _selected.value = null }

    fun retry() {
        _state.update { it.copy(connected = ConnectedRecently.Loading) }
        viewModelScope.launch { reload() }
    }

    /**
     * Allowed / Blocked in background / Blocked (D8 helper). Only the app's own app-level
     * rules change; a per-name rule like "Block ads.example.com for this app" stays.
     * One change at a time: a second tap waits for the first.
     */
    fun setStatus(target: AppStatus) {
        if (_state.value.status == target) return
        val previous = statusJob
        statusJob = viewModelScope.launch {
            previous?.join()
            try {
                val context = getApplication<Application>()
                val change = AppsLogic.changeTo(
                    target, packageName, apps.userRules(),
                    blockLabel = context.getString(R.string.rule_block_app, appName),
                    backgroundLabel = context.getString(R.string.rule_block_app_bg, appName),
                )
                if (change.isEmpty) return@launch
                val reverse = apps.apply(change)
                _state.update { it.copy(status = target) }
                val text = context.getString(
                    when (target) {
                        AppStatus.ALLOWED -> R.string.ui_app_now_allowed
                        AppStatus.BLOCKED_IN_BACKGROUND -> R.string.ui_app_now_blocked_background
                        AppStatus.BLOCKED -> R.string.ui_app_now_blocked
                    },
                    appName,
                )
                _message.value = ActivityMessage(text, undo = { undo(reverse) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Status change failed: ${e.javaClass.simpleName}")
                _message.value = ActivityMessage(getApplication<Application>().getString(R.string.ui_action_failed))
            }
        }
    }

    private fun undo(reverse: AppsLogic.StatusChange) {
        viewModelScope.launch {
            try {
                apps.apply(reverse)
                refreshStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Undo failed: ${e.javaClass.simpleName}")
                _message.value = ActivityMessage(getApplication<Application>().getString(R.string.ui_action_failed))
            }
        }
    }

    private suspend fun refreshStatus() {
        val status = AppsLogic.statusOf(apps.userRules(), packageName)
        _state.update { it.copy(status = status) }
    }

    private suspend fun observe() {
        changes().collect { rulesChanged ->
            if (rulesChanged) activity.rulesChanged()
            reload()
            delay(RELOAD_MIN_MS)
        }
    }

    /** One signal per change that can alter this screen; true when rules or lists changed. */
    private fun changes(): Flow<Boolean> = flow {
        emit(false)
        val rulesFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        merge(
            db.invalidationTracker.createFlow("connections", "dns_queries", emitInitialState = false).map { },
            merge(
                apps.configDatabase().invalidationTracker.createFlow("rules", emitInitialState = false).map { },
                db.invalidationTracker.createFlow("blocklist", emitInitialState = false).map { },
                RuleSyncBus.version.drop(1).map { },
            ).map { rulesFlag.set(true) },
            StatsManager.snapshot
                .map { s -> s.activeConnections.filter { it.packageName == packageName }.map { it.destinationIp to it.destinationPort }.toSet() }
                .distinctUntilChanged().drop(1).map { },
            ticks(),
        ).conflate().collect { emit(rulesFlag.getAndSet(false)) }
    }

    /** "Today" and the 24 h window move with time alone. */
    private fun ticks(): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            delay(MINUTE_MS)
            emit(Unit)
        }
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        refreshStatus()
        val summary = runLogged { apps.itemFor(packageName, now) }
        val connected = runLogged { activity.forApp(packageName, now) }
            ?.let { ConnectedRecently.Loaded(AppDetailLogic.sections(it)) }
            ?: ConnectedRecently.Error
        _state.update { it.copy(summary = summary ?: it.summary, connected = connected) }
        runLogged { loadBehaviour(now) }
    }

    /** null (and logged, never silent) when [block] failed. */
    private suspend fun <T> runLogged(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Load failed: ${e.javaClass.simpleName}")
        null
    }

    /** 1.0's Activity and Connections tabs, now under Technical details. */
    private suspend fun loadBehaviour(now: Long) {
        val since = now - DAY_MS
        // Only this package's own traffic. Matching by keyword ("meta", "facebook") or
        // GeoIP organisation credited other apps' connections to this one.
        val persisted = db.connectionDao().forPackageBetween(packageName, since, now)
        val live = StatsManager.snapshot.value.activeConnections
            .filter { it.packageName == packageName }
            .map { it.toConnectionEntity(now) }
        val merged = (live + persisted)
            .distinctBy { listOf(it.packageName, it.destinationIp, it.destinationPort, it.domain ?: "", it.sniHostname ?: "", it.timestamp).joinToString("|") }
            .sortedByDescending { it.timestamp }

        val domainRows = merged
            .groupBy { (it.sniHostname ?: it.domain ?: it.destinationIp).ifBlank { it.destinationIp } }
            .map { (domain, connections) ->
                val cls = com.privacyguard.app.core.tracker.DomainClassifier.classify(domain, packageName)
                DomainRow(
                    domain = domain,
                    count = connections.size,
                    backgroundCount = connections.count { it.foreground == false },
                    bytesSent = connections.sumOf { it.bytesSent },
                    bytesReceived = connections.sumOf { it.bytesReceived },
                    // The app maker's own SDK in its own app is first-party, not a tracker.
                    trackerName = cls.trackerName?.takeIf { cls.purpose != com.privacyguard.app.core.tracker.DomainPurpose.FIRST_PARTY },
                    company = cls.company,
                )
            }
            .sortedByDescending { it.count }

        val allowedByHour = IntArray(24)
        val blockedByHour = IntArray(24)
        val suspiciousByHour = IntArray(24)
        val calendar = java.util.Calendar.getInstance()
        merged.forEach { c ->
            calendar.timeInMillis = c.timestamp
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            when {
                c.wasBlocked -> blockedByHour[hour]++
                c.foreground == false || c.screenOn == false -> suspiciousByHour[hour]++
                else -> allowedByHour[hour]++
            }
        }
        val dao = db.dnsQueryDao()
        // One unit per hour: connections, plus blocked lookups (which never become a
        // connection). Allowed lookups are not added: each one usually precedes a
        // connection that is already counted, so a visit would count two or three times.
        dao.hourlyForApp(packageName, since).forEach { bucket ->
            if (bucket.hour in 0..23) blockedByHour[bucket.hour] += bucket.blockedQueries
        }
        val recent = merged.take(12).map {
            RecentActivityRow(
                timestamp = it.timestamp,
                label = it.host ?: it.destinationIp,
                detail = "${it.protocol} ${it.destinationPort}",
                blocked = it.wasBlocked,
                idle = it.screenOn == false,
            )
        }
        _state.update {
            it.copy(
                domains = domainRows,
                isLoadingDomains = false,
                activityTimeline = (0..23).map { h -> ActivityHourRow(h, allowedByHour[h], blockedByHour[h], suspiciousByHour[h]) },
                dnsQueryCount = dao.countForApp(packageName, since),
                idleDnsQueryCount = dao.idleCountForApp(packageName, since),
                connectionCount = merged.size,
                idleConnectionCount = merged.count { c -> c.foreground == false },
                dnsDomains = dao.topDomainsForApp(packageName, since, 10),
                recentActivity = recent,
            )
        }
        refreshMismatchFindings(observedDomains = domainRows.map { it.domain })
    }

    private fun loadMismatchSignals() {
        viewModelScope.launch {
            val requestedPermissions = permissionsLookup.await()
            val observedDomains = runLogged {
                val now = System.currentTimeMillis()
                db.connectionDao().forPackageBetween(packageName, now - DAY_MS, now).mapNotNull { it.host }.distinct()
            }.orEmpty()
            val findings = PermissionMismatchDetector.analyze(
                appName = appName,
                packageName = packageName,
                requestedPermissions = requestedPermissions,
                observedDomains = observedDomains,
                detectedSdks = _state.value.detectedSdks,
                profiles = loadObservedProfiles(),
            )
            _state.update { it.copy(mismatchFindings = findings, declaredPermissions = requestedPermissions, isLoadingMismatch = false) }
        }
    }

    private fun scanApk() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true) }
            val found = ApkScanner.scanPackage(getApplication(), packageName)
            val findings = PermissionMismatchDetector.analyze(
                appName = appName,
                packageName = packageName,
                requestedPermissions = permissionsLookup.await(),
                observedDomains = _state.value.domains.map { it.domain },
                detectedSdks = found,
                profiles = loadObservedProfiles(),
            )
            _state.update { it.copy(detectedSdks = found, mismatchFindings = findings, isScanning = false, isLoadingMismatch = false) }
        }
    }

    private suspend fun refreshMismatchFindings(observedDomains: List<String>) {
        val findings = PermissionMismatchDetector.analyze(
            appName = appName,
            packageName = packageName,
            requestedPermissions = permissionsLookup.await(),
            observedDomains = observedDomains,
            detectedSdks = _state.value.detectedSdks,
            profiles = loadObservedProfiles(),
        )
        _state.update { it.copy(mismatchFindings = findings, isLoadingMismatch = false) }
    }

    private fun loadRequestedPermissions(packageName: String): Set<String> =
        runCatching {
            @Suppress("DEPRECATION")
            getApplication<Application>().packageManager
                .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

    private suspend fun loadObservedProfiles() = runLogged { metadataRepo.profilesForApp(packageName) }.orEmpty()

    private fun ActiveConnectionInfo.toConnectionEntity(now: Long): ConnectionEntity =
        ConnectionEntity(
            appUid = -1,
            appName = appName,
            packageName = packageName,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            destinationIpv6 = destinationIp.takeIf { it.contains(':') },
            isIPv6 = destinationIp.contains(':'),
            domain = hostName,
            sniHostname = hostName,
            protocol = protocol,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            timestamp = now,
            durationMs = 0L,
            wasBlocked = isBlocked,
            encryptionStatus = securityInfo,
            tlsVersion = encryptionInfo.takeIf { it.isNotBlank() },
            wasBackground = false,
        )

    private companion object {
        const val TAG = "AppDetailViewModel"
        const val RELOAD_MIN_MS = 5_000L
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
