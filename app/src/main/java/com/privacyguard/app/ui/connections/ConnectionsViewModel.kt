package com.privacyguard.app.ui.connections

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.core.geoip.GeoResult
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.data.repository.RuleSyncBus
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
    val payloadPreview: String? = null
)

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
    private val db = AppDatabase.getInstance(app)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())
    private val packageManager = app.packageManager

    private val _recentConnections = MutableStateFlow<List<Connection>>(emptyList())
    val recentConnections: StateFlow<List<Connection>> = _recentConnections.asStateFlow()

    private val _blockedRuleKeys = MutableStateFlow<Set<String>>(emptySet())

    val connections: StateFlow<List<Connection>> = StatsManager.snapshot
        .map { snapshot ->
            snapshot.activeConnections.map {
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
                    dataRate = formatRate(it.bytesTransferred),
                    bytesSent = it.bytesTransferred,
                    bytesReceived = 0,
                    hostName = it.hostName,
                    securityInfo = it.securityInfo,
                    encryptionInfo = it.encryptionInfo,
                    payloadPreview = it.payloadPreview
                ).withRuleState()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                val since = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                _recentConnections.value = db.connectionDao().getRecentConnections(since, 100).map {
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
                        dataRate = formatRate(it.bytesSent + it.bytesReceived),
                        bytesSent = it.bytesSent,
                        bytesReceived = it.bytesReceived,
                        hostName = it.sniHostname ?: it.domain,
                        securityInfo = it.encryptionStatus,
                        encryptionInfo = it.tlsVersion ?: "",
                        payloadPreview = null
                    ).withRuleState()
                }
                delay(1_000)
            }
        }
    }

    val dataRate: String
        get() = formatRate(connections.value.sumOf { it.bytesSent })

    private fun displayAppName(appName: String, packageName: String): String {
        val resolvedPackage = packageName.takeIf { it.isNotBlank() && it != "Unknown" }
        if (resolvedPackage != null) {
            runCatching {
                val info = packageManager.getApplicationInfo(resolvedPackage, 0)
                val label = packageManager.getApplicationLabel(info).toString().trim()
                if (label.isNotBlank()) return label
            }
        }

        val candidate = appName.takeIf { it.isNotBlank() && it != "Unknown" } ?: resolvedPackage.orEmpty()
        if (candidate.isBlank()) return "Unknown app"
        if (!candidate.contains('.')) return candidate

        return candidate
            .substringAfterLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
            }
    }

    fun setFilter(filter: ConnectionFilter) {
        _filter.value = filter
        resolveDnsIfHost(filter.query)
    }

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
                rulesRepo.upsertRule(target.toRule())
            }
            refreshBlockedRuleKeys()
            _selectedConnection.value = _selectedConnection.value?.let {
                if (it.connection.id == connection.id) {
                    it.copy(connection = it.connection.copy(isBlocked = _blockedRuleKeys.value.contains(id)))
                } else {
                    it
                }
            }
        }
    }

    fun blockApp(packageName: String) {
        viewModelScope.launch {
            if (packageName.isBlank() || packageName == "Unknown") return@launch
            val ruleId = BlockTarget(BlockTargetKind.PACKAGE, packageName).ruleId()
            rulesRepo.upsertRule(
                FilterRule(
                    id = ruleId,
                    label = "Block $packageName",
                    action = FilterRule.Action.DENY,
                    source = FilterRule.Source.USER,
                    priority = FilterRule.HIGH_PRIORITY,
                    matchPackage = packageName
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
        var result = (if (connections.value.isNotEmpty()) connections.value else recentConnections.value)
            .map { it.withRuleState() }

        if (_filter.value.showBlockedOnly) {
            result = result.filter { it.isBlocked }
        }

        if (_filter.value.showCleartextOnly) {
            result = result.filter {
                it.securityInfo.contains("CLEAR", ignoreCase = true) ||
                    it.securityInfo.contains("UNKNOWN", ignoreCase = true)
            }
        }

        if (_filter.value.query.isNotBlank()) {
            val q = _filter.value.query
            result = result.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true) ||
                    it.destinationIp.contains(q, ignoreCase = true) ||
                    (it.hostName?.contains(q, ignoreCase = true) == true) ||
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

    private fun Connection.withRuleState(): Connection =
        copy(isBlocked = isBlocked || _blockedRuleKeys.value.contains(blockTarget().ruleId()))

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
        fun ruleId(): String = "user:block:${kind.name.lowercase()}:$value"

        fun toRule(): FilterRule = FilterRule(
            id = ruleId(),
            label = "Block $value",
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
        if (bytes <= 0) return "0 KB/s"
        return String.format(Locale.US, "%.1f KB/s", bytes / 1024.0)
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
                        error = if (addresses.isEmpty()) "No DNS records returned" else null
                    )
                },
                onFailure = { error ->
                    DnsLookupState(
                        query = host,
                        error = error.message ?: "DNS lookup failed"
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
