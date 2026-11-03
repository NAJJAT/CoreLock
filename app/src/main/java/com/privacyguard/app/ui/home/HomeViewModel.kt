package com.privacyguard.app.ui.home

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.network.NetworkTrustRecorder
import com.privacyguard.app.core.network.PrivateDnsCheck
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.BlockStats
import com.privacyguard.app.ui.alerts.AlertFeed
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.ui.dashboard.StrictSuggestion
import com.privacyguard.app.ui.traffic.HomeEvidence
import com.privacyguard.app.vpn.BootReceiver
import com.privacyguard.app.vpn.UnexpectedStop
import com.privacyguard.platform.android.ForegroundAppDetector
import com.privacyguard.platform.android.PrivacyVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** "Blocked today": unknown until the encrypted database answers, never a made-up 0. */
sealed interface BlockedToday {
    data object Loading : BlockedToday
    data class Count(val n: Int) : BlockedToday
    data object Error : BlockedToday
}

data class HomeUiState(
    val protection: ProtectionState,
    /** Why it stopped, only while [protection] is STOPPED_UNEXPECTEDLY; null after a crash. */
    val stopReason: UnexpectedStop.Reason? = null,
    val level: String,
    val blockedToday: BlockedToday = BlockedToday.Loading,
    val attention: HomeLogic.Attention? = null,
)

/**
 * Home (DESIGN_SPEC_1.1.md §6.1). Everything is pushed, nothing polled: the service
 * publishes its state, preferences are StateFlows, and database numbers are recounted
 * when Room reports a write to their tables (at most every [RECOUNT_MIN_MS]). The 1.0
 * Home ran a 1 s and a 15 s loop for as long as it was visible (CLAUDE.md §16.2 item 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SettingsPreferences.getInstance(app)
    private val homePrefs = HomePreferences.getInstance(app)
    private val usageAccess = ForegroundAppDetector(app)

    /** Bumped on every resume: Private DNS, battery and Usage Access change in system settings. */
    private val resumed = MutableStateFlow(0)
    private val retryCount = MutableStateFlow(0)

    // The encrypted databases can take seconds to open after boot; nothing on the hero
    // waits for them.
    private val db: Flow<AppDatabase> = flow { emit(withContext(Dispatchers.IO) { AppDatabase.getInstance(app) }) }
    private val configDb: Flow<ConfigDatabase> = flow { emit(withContext(Dispatchers.IO) { ConfigDatabase.getInstance(app) }) }

    private val stopReason = UnexpectedStop.reason(app)

    private data class Hero(val state: ProtectionState, val reason: UnexpectedStop.Reason?)

    private fun readHero(
        service: PrivacyVpnService.State = PrivacyVpnService.state.value,
        reason: UnexpectedStop.Reason? = stopReason.value,
    ): Hero {
        val state = HomeLogic.protectionState(service, BootReceiver.shouldBeActive(getApplication()), reason)
        return Hero(state, reason.takeIf { state == ProtectionState.STOPPED_UNEXPECTEDLY })
    }

    private val protection: Flow<Hero> =
        combine(PrivacyVpnService.state, stopReason, resumed) { service, reason, _ -> readHero(service, reason) }

    private val blockedToday: Flow<BlockedToday> = retryCount.flatMapLatest {
        db.flatMapLatest { database ->
            val stats = BlockStats(database)
            merge(database.invalidationTracker.createFlow(*BLOCK_TABLES), midnights())
                .conflate()
                .transform<Any, BlockedToday> {
                    emit(BlockedToday.Count(blockedTodayCount(stats, System.currentTimeMillis())))
                    // Throttle: writes during the pause are conflated into one recount.
                    delay(RECOUNT_MIN_MS)
                }
        }.onStart { emit(BlockedToday.Loading) }
            .catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Blocked today failed: ${e.javaClass.simpleName}")
                emit(BlockedToday.Error)
            }
    }.flowOn(Dispatchers.IO)

    /** What the phone's settings say; read again on every resume. */
    private data class PhoneChecks(val privateDnsHost: String?, val battery: BatteryCheck.State, val hasUsageAccess: Boolean)

    private val phoneChecks: Flow<PhoneChecks> = resumed.map {
        val context = getApplication<Application>()
        PhoneChecks(PrivateDnsCheck.strictHostname(context), BatteryCheck.state(context), usageAccess.hasUsageAccess())
    }.flowOn(Dispatchers.Default)

    /** Background-only rules need Usage Access to know what is in the foreground. */
    private val hasBackgroundRule: Flow<Boolean> = configDb.flatMapLatest { config ->
        merge(config.invalidationTracker.createFlow("rules"), resumed).conflate().map {
            config.rulesDao().getEnabledRules().any { it.matchBackground == true }
        }
    }.catch { e -> if (e is CancellationException) throw e; Log.w(TAG, "Rules check failed: ${e.javaClass.simpleName}"); emit(false) }
        .flowOn(Dispatchers.IO)

    private val newHighAlert: Flow<HomeLogic.HighAlert?> = db.flatMapLatest { database ->
        val feed = AlertFeed(getApplication())
        combine(
            merge(database.invalidationTracker.createFlow("dns_anomalies", "tls_alerts"), resumed).conflate(),
            homePrefs.alertSeenAt,
        ) { _, seenAt ->
            val since = maxOf(seenAt, System.currentTimeMillis() - HomeLogic.HIGH_ALERT_WINDOW_MS)
            feed.loadItems().firstOrNull { it.severity >= HomeLogic.HIGH_ALERT_SEVERITY && it.timestamp > since }
                ?.let { HomeLogic.HighAlert(it.title, it.timestamp) }
        }
    }.catch { e -> if (e is CancellationException) throw e; Log.w(TAG, "Alerts failed: ${e.javaClass.simpleName}"); emit(null) }
        .flowOn(Dispatchers.IO)

    /**
     * Scores the network and records it (Statistics lists remembered networks), on every
     * resume, network change and new DNS anomaly; then "Switch to Strict?" if trust fell.
     */
    private val strictSuggestion: Flow<StrictSuggestion?> = db.flatMapLatest { database ->
        val trust = merge(database.invalidationTracker.createFlow("dns_anomalies"), resumed, networkChanges())
            .conflate()
            .map {
                val at = System.currentTimeMillis()
                val anomalies = database.dnsAnomalyDao().since(at - HomeEvidence.WEEK_MS).filter { it.timestamp <= at }
                NetworkTrustRecorder.record(getApplication(), database, anomalies, at)
            }
        combine(trust, prefs.protectionLevel, prefs.strictSuggestionDismissed) { summary, level, dismissed ->
            StrictSuggestion.of(summary, level, dismissed)
        }
    }.catch { e -> if (e is CancellationException) throw e; Log.w(TAG, "Network trust failed: ${e.javaClass.simpleName}"); emit(null) }
        .flowOn(Dispatchers.IO)

    private val attention: Flow<HomeLogic.Attention?> = combine(
        combine(phoneChecks, hasBackgroundRule.onStart { emit(false) }) { checks, bgRule -> checks to bgRule },
        homePrefs.batteryDismissed,
        newHighAlert.onStart { emit(null) },
        homePrefs.whatsNew,
        strictSuggestion.onStart { emit(null) },
    ) { (checks, bgRule), batteryDismissed, alert, whatsNew, strict ->
        HomeLogic.attention(
            HomeLogic.AttentionInputs(
                privateDnsHost = checks.privateDnsHost,
                usageAccessMissingForRule = bgRule && !checks.hasUsageAccess,
                battery = checks.battery,
                batteryDismissed = batteryDismissed,
                newHighAlert = alert,
                whatsNew = whatsNew && HomeLogic.WHATS_NEW_ENABLED,
                strictSuggestion = strict,
            )
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(protection, prefs.protectionLevel, blockedToday, attention) { p, level, blocked, att ->
        HomeUiState(protection = p.state, stopReason = p.reason, level = level, blockedToday = blocked, attention = att)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        // Read synchronously, so the first frame already shows the real state (no false "off").
        readHero().let { HomeUiState(protection = it.state, stopReason = it.reason, level = prefs.protectionLevel.value) },
    )

    fun onResume() { resumed.value++ }

    fun retryBlockedToday() { retryCount.value++ }

    fun setProtectionLevel(level: String) = prefs.setProtectionLevel(level)

    fun acceptStrict() = setProtectionLevel(HomeLogic.LEVEL_STRICT)

    fun dismissStrict(networkKey: String) = prefs.dismissStrictSuggestion(networkKey)

    fun markAlertsSeen(upTo: Long) = homePrefs.markAlertsSeen(upTo)

    fun dismissBattery() = homePrefs.dismissBattery()

    fun finishWhatsNew() = homePrefs.finishWhatsNew()

    /** Fires at every local midnight, so "today" restarts even when nothing is written. */
    private fun midnights(): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            delay((HomeLogic.nextMidnight(now) - now).coerceAtLeast(1_000L))
            emit(Unit)
        }
    }

    private fun networkChanges(): Flow<Unit> {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return flowOf()
        return callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { trySend(Unit) }
                override fun onLost(network: Network) { trySend(Unit) }
            }
            cm.registerDefaultNetworkCallback(callback)
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
        /** Tables behind BlockStats; a write to either can change the count. */
        private val BLOCK_TABLES = arrayOf("dns_queries", "connections")
        /** Rows arrive in batches while apps are busy; one recount per 2 s is plenty for a number. */
        const val RECOUNT_MIN_MS = 2_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The one "Blocked today" number: [BlockStats] from local midnight, as Activity and Statistics count. */
        suspend fun blockedTodayCount(stats: BlockStats, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int =
            stats.totalBetween(HomeLogic.startOfDay(now, zone), now)
    }
}
