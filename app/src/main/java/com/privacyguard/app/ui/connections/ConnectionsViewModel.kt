package com.privacyguard.app.ui.connections

import android.app.Application
import com.privacyguard.app.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.core.geoip.GeoResult
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
import com.privacyguard.app.ui.apps.AppsViewModel
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import java.net.InetAddress
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Connection(
    val id: String,
    val appName: String,
    val packageName: String,
    val destination: String,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val isBlocked: Boolean,
    val dataRate: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val hostName: String? = null,
    val securityInfo: String = "Unknown",
    val encryptionInfo: String = "Unknown",
    val payloadPreview: String? = null,
    val wasBackground: Boolean = false,
    /** GeoIP owner of the address, shown when no hostname is known. Never a block target. */
    val orgHint: String? = null,
    /** A block rule for this destination exists now (the session itself may have gone through). */
    val hasBlockRule: Boolean = false,
    /** Where a blocked connection was stopped, when it was its server name rather than DNS. */
    val blockSource: com.privacyguard.core.session.BlockSource? = null,
) {
    /** A device on your own network (router, Chromecast, printer, mDNS). */
    val isLocal: Boolean get() =
        com.privacyguard.app.core.network.LocalNetwork.isLocalAddress(destinationIp) ||
            com.privacyguard.app.core.network.LocalNetwork.isLocalHost(hostName)

    /** The OS checking for internet access (captive portal, MobileWips), not an app. */
    val isProbe: Boolean get() = com.privacyguard.app.core.network.ConnectivityProbes.isProbe(packageName, hostName)
}

/** Live sessions now, or connections that ended in the last 24 h. */
enum class TrafficTab { LIVE, HISTORY }

data class ConnectionFilter(
    val query: String = "",
    val showBlockedOnly: Boolean = false,
    val showCleartextOnly: Boolean = false
)

data class DnsLookupState(
    val query: String = "",
    val isLoading: Boolean = false,
    val addresses: List<String> = emptyList(),
    val error: String? = null
)

data class ConnectionDetailState(
    val connection: Connection,
    val displayHost: String,
    val reverseDns: String? = null,
    val geo: GeoResult? = null,
    val isResolvingReverseDns: Boolean = false,
)

class ConnectionsViewModel(app: Application) : AndroidViewModel(app) {
    private fun str(id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)
    private val db = AppDatabase.getInstance(app)
    private val configDb = ConfigDatabase.getInstance(app)
    private val rulesRepo = RulesRepo(configDb.rulesDao(), FilterEngine())
    private val packageManager = app.packageManager

    private val _recentConnections = MutableStateFlow<List<Connection>>(emptyList())
    val recentConnections: StateFlow<List<Connection>> = _recentConnections.asStateFlow()

    private val _blockedRuleKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Observed by the screen so BLOCK/UNBLOCK redraws as soon as a rule changes. */
    val blockedRuleKeys: StateFlow<Set<String>> = _blockedRuleKeys.asStateFlow()

    val connections: StateFlow<List<Connection>> = StatsManager.snapshot
        .map { snapshot ->
            snapshot.activeConnections.filterNot {
                isPrivacyGuardPackage(it.packageName)
            }.map {
                Connection(
                    id = it.id,
                    appName = displayAppName(
                        appName = it.appName,
                        packageName = it.packageName,
                    ),
                    packageName = it.packageName.ifBlank { it.appName },
                    destination = it.destination,
                    destinationIp = it.destinationIp,
                    destinationPort = it.destinationPort,
                    protocol = it.protocol,
                    isBlocked = it.isBlocked,
                    dataRate = formatRate(it.bytesPerSec),
                    bytesSent = it.bytesSent,
                    bytesReceived = it.bytesReceived,
                    hostName = it.hostName,
                    securityInfo = it.securityInfo,
                    encryptionInfo = it.encryptionInfo,
                    payloadPreview = it.payloadPreview
                ).withRuleState()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tab = MutableStateFlow(TrafficTab.LIVE)
    val tab: StateFlow<TrafficTab> = _tab.asStateFlow()

    fun setTab(t: TrafficTab) { _tab.value = t }

    private val _filter = MutableStateFlow(ConnectionFilter())
    val filter: StateFlow<ConnectionFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _dnsLookup = MutableStateFlow(DnsLookupState())
    val dnsLookup: StateFlow<DnsLookupState> = _dnsLookup.asStateFlow()

    private val _selectedConnection = MutableStateFlow<ConnectionDetailState?>(null)
    val selectedConnection: StateFlow<ConnectionDetailState?> = _selectedConnection.asStateFlow()

    init {
        viewModelScope.launch {
            RuleSyncBus.version.collect { refreshBlockedRuleKeys() }
        }

        viewModelScope.launch {
            while (true) {
                try {
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                // Every connection that ended in the last 24 h (blocked attempts included).
                _recentConnections.value = db.connectionDao().allSince(since).filterNot {
                    isPrivacyGuardPackage(it.packageName)
                }.map {
                    Connection(
                        id = it.id.toString(),
                        appName = displayAppName(
                            appName = it.appName,
                            packageName = it.packageName,
                        ),
                        packageName = it.packageName.ifBlank {
                            it.appName.ifBlank { "Unknown" }
                        },
                        destination = "${it.destinationIp}:${it.destinationPort}",
                        destinationIp = it.destinationIp,
                        destinationPort = it.destinationPort,
                        protocol = it.protocol,
                        isBlocked = it.wasBlocked,
                        blockSource = if (it.wasBlocked) com.privacyguard.core.session.BlockSource.fromStored(it.blockSource) else null,
                        // A total for a finished connection: never shown "per second".
                        dataRate = formatBytes(it.bytesSent + it.bytesReceived),
                        bytesSent = it.bytesSent,
                        bytesReceived = it.bytesReceived,
                        hostName = it.sniHostname ?: it.domain,
                        orgHint = if (it.sniHostname == null && it.domain == null) orgHintForIp(it.destinationIp) else null,
                        securityInfo = it.encryptionStatus,
                        encryptionInfo = it.tlsVersion ?: "",
                        payloadPreview = null,
                        wasBackground = it.wasBackground,
                    ).withRuleState()
                }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: Exception) { }
                // History is the whole day with a GeoIP lookup per unnamed row: refresh calmly.
                delay(5_000)
            }
        }
    }

    private fun formatBytes(bytes: Long): String = com.privacyguard.app.core.utils.ByteFormat.format(bytes)

    val dataRate: String
        get() = formatRate(StatsManager.snapshot.value.throughputBytesPerSec)

    /**
     * Returns a human-readable hint when neither SNI nor DNS domain is available.
     * WhatsApp over QUIC (UDP) and Telegram never populate sniHostname or domain
     * because the UDP forwarder doesn't parse TLS. GeoIpResolver maps the IP to
     * an org name so the user sees "Meta" or "Telegram" instead of a bare IP.
     */
    private fun orgHintForIp(ip: String): String? {
        val geo = com.privacyguard.app.core.geoip.GeoIpResolver.lookup(ip) ?: return null
        return geo.org?.takeIf { it != "Unknown" }
    }

    private fun displayAppName(appName: String, packageName: String): String {
        val resolvedPackage = packageName.takeIf { it.isNotBlank() && it != "Unknown" }
        if (resolvedPackage != null) {
            runCatching {
                val info = packageManager.getApplicationInfo(resolvedPackage, 0)
                val label = packageManager.getApplicationLabel(info).toString().trim()
                if (label.isNotBlank()) return label
            }
        }

        if (resolvedPackage == null && appName.isBlank()) return str(R.string.conn_unknown_app)
        // Never a package name or a fragment of one ("katana"): see AppNames.
        return com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), resolvedPackage.orEmpty(), appName)
    }

    private fun isPrivacyGuardPackage(packageName: String?): Boolean {
        return packageName == BuildConfig.APPLICATION_ID ||
                packageName?.startsWith("com.privacyguard.app") == true
    }

    fun setFilter(filter: ConnectionFilter) {
        if (filter.query != _filter.value.query) _dnsLookup.value = DnsLookupState()
        _filter.value = filter
    }

    /** Only on an explicit tap: a lookup goes out to the network and into your own history. */
    fun lookUpDns() = resolveDnsIfHost(_filter.value.query)

    fun queryLooksLikeHost(): Boolean = _filter.value.query.trim().looksLikeHostname()

    fun clearFilter() {
        _filter.value = ConnectionFilter()
        _dnsLookup.value = DnsLookupState()
    }

    fun toggleConnectionBlocked(connection: Connection) {
        viewModelScope.launch {
            val target = connection.blockTarget()
            if (target.value.isBlank() || target.value == "Unknown") return@launch

            val id = target.ruleId()
            if (_blockedRuleKeys.value.contains(id)) {
                rulesRepo.deleteRule(id)
            } else {
                rulesRepo.upsertRule(target.toRule(str(if (target.kind == BlockTargetKind.PACKAGE) R.string.rule_block_app else R.string.rule_block_domain, target.value)))
            }
            refreshBlockedRuleKeys()
            _selectedConnection.value = _selectedConnection.value?.let {
                if (it.connection.id == connection.id) {
                    it.copy(connection = it.connection.copy(hasBlockRule = _blockedRuleKeys.value.contains(id)))
                } else {
                    it
                }
            }
        }
    }

    fun blockApp(packageName: String) {
        viewModelScope.launch {
            if (packageName.isBlank() || packageName == "Unknown") return@launch
            rulesRepo.upsertRule(
                FilterRule(
                    id = AppsViewModel.packageBlockRuleId(packageName),
                    label = str(R.string.rule_block_app, packageName),
                    action = FilterRule.Action.DENY,
                    source = FilterRule.Source.USER,
                    priority = FilterRule.HIGH_PRIORITY,
                    matchPackage = packageName,
                )
            )
            refreshBlockedRuleKeys()
        }
    }

    fun selectConnection(connection: Connection) {
        val displayHost = connection.bestHost()
        _selectedConnection.value = ConnectionDetailState(
            connection = connection.withRuleState(),
            displayHost = displayHost,
            geo = GeoIpResolver.lookup(connection.destinationIp),
            isResolvingReverseDns = connection.hostName.isNullOrBlank()
        )

        if (connection.hostName.isNullOrBlank()) {
            viewModelScope.launch {
                val reverseDns = withContext(Dispatchers.IO) {
                    runCatching {
                        InetAddress.getByName(connection.destinationIp).canonicalHostName
                            .takeUnless { it == connection.destinationIp || it.isBlank() }
                    }.getOrNull()
                }

                val current = _selectedConnection.value ?: return@launch
                if (current.connection.id == connection.id) {
                    _selectedConnection.value = current.copy(
                        reverseDns = reverseDns,
                        displayHost = reverseDns ?: current.displayHost,
                        isResolvingReverseDns = false,
                    )
                }
            }
        }
    }

    fun clearSelectedConnection() {
        _selectedConnection.value = null
    }

    fun getFilteredConnections(): List<Connection> {
        // The chosen tab only; never a silent fallback from live to history.
        var result = (if (_tab.value == TrafficTab.LIVE) connections.value else recentConnections.value)
            .map { it.withRuleState() }

        if (_filter.value.showBlockedOnly) {
            result = result.filter { it.isBlocked }
        }

        if (_filter.value.showCleartextOnly) {
            // Plain HTTP actually seen (live sessions are classified as soon as data flows).
            result = result.filter { it.securityInfo == "CLEARTEXT" && !it.isLocal && !it.isProbe }
        }

        if (_filter.value.query.isNotBlank()) {
            val q = _filter.value.query
            result = result.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true) ||
                    it.destinationIp.contains(q, ignoreCase = true) ||
                    (it.hostName?.contains(q, ignoreCase = true) == true) ||
                    (it.orgHint?.contains(q, ignoreCase = true) == true) ||
                    it.destination.contains(q, ignoreCase = true)
            }
        }

        return result
    }

    private suspend fun refreshBlockedRuleKeys() {
        _blockedRuleKeys.value = rulesRepo.allRules()
            .filter { it.isEnabled && it.action == FilterRule.Action.DENY }
            .mapNotNull { it.blockRuleKey() }
            .toSet()
    }

    // isBlocked stays what happened to this connection; the rule state drives the button.
    private fun Connection.withRuleState(): Connection =
        copy(hasBlockRule = _blockedRuleKeys.value.contains(blockTarget().ruleId()))

    private fun Connection.blockTarget(): BlockTarget {
        val host = hostName?.trim()
        return when {
            !host.isNullOrBlank() && !host.isIpLiteral() -> BlockTarget(BlockTargetKind.DOMAIN, host.lowercase())
            destinationIp.isNotBlank() -> BlockTarget(BlockTargetKind.IP, destinationIp)
            packageName.isNotBlank() -> BlockTarget(BlockTargetKind.PACKAGE, packageName)
            else -> BlockTarget(BlockTargetKind.PACKAGE, appName)
        }
    }

    private fun Connection.bestHost(): String =
        hostName?.takeIf { it.isNotBlank() } ?: destinationIp.takeIf { it.isNotBlank() } ?: destination

    private fun FilterRule.blockRuleKey(): String? = when {
        matchDomain?.isNotBlank() == true -> BlockTarget(BlockTargetKind.DOMAIN, matchDomain.lowercase()).ruleId()
        matchIp?.isNotBlank() == true -> BlockTarget(BlockTargetKind.IP, matchIp).ruleId()
        matchPackage?.isNotBlank() == true -> BlockTarget(BlockTargetKind.PACKAGE, matchPackage).ruleId()
        else -> null
    }

    private data class BlockTarget(
        val kind: BlockTargetKind,
        val value: String,
    ) {
        fun ruleId(): String = when (kind) {
            // Use the canonical package rule ID so AppsScreen and AppDetailScreen can unblock it
            BlockTargetKind.PACKAGE -> AppsViewModel.packageBlockRuleId(value)
            else -> "user:block:${kind.name.lowercase()}:$value"
        }

        fun toRule(label: String): FilterRule = FilterRule(
            id = ruleId(),
            label = label,
            action = FilterRule.Action.DENY,
            source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY,
            matchPackage = value.takeIf { kind == BlockTargetKind.PACKAGE },
            matchDomain = value.takeIf { kind == BlockTargetKind.DOMAIN },
            matchIp = value.takeIf { kind == BlockTargetKind.IP },
        )
    }

    private enum class BlockTargetKind { DOMAIN, IP, PACKAGE }

    private fun formatRate(bytes: Long): String {
        return str(R.string.dash_per_second, android.text.format.Formatter.formatShortFileSize(getApplication(), bytes.coerceAtLeast(0)))
    }

    private fun resolveDnsIfHost(input: String) {
        val host = input.trim()
        if (!host.looksLikeHostname()) {
            _dnsLookup.value = DnsLookupState()
            return
        }

        _dnsLookup.value = DnsLookupState(query = host, isLoading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    InetAddress.getAllByName(host)
                        .map { it.hostAddress ?: "" }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
            }

            if (_filter.value.query.trim() != host) return@launch

            _dnsLookup.value = result.fold(
                onSuccess = { addresses ->
                    DnsLookupState(
                        query = host,
                        addresses = addresses,
                        error = if (addresses.isEmpty()) str(R.string.conn_no_records) else null
                    )
                },
                onFailure = { error ->
                    DnsLookupState(
                        query = host,
                        error = error.message ?: str(R.string.conn_lookup_failed)
                    )
                }
            )
        }
    }

    private fun String.looksLikeHostname(): Boolean {
        if (length < 3 || contains(' ') || startsWith(".") || endsWith(".")) return false
        if (!contains('.')) return false
        return all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
    }

    private fun String.isIpLiteral(): Boolean =
        all { it.isDigit() || it == '.' || it == ':' } && any { it == '.' || it == ':' }
}
