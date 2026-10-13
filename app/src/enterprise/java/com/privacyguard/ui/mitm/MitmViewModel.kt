package com.privacyguard.ui.mitm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.app.data.db.PayloadLogSummary
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.MitmEngine
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Totals over every stored entry (not the filtered list); buckets add up to [total]. */
data class PayloadAnalytics(
    val total: Int = 0,
    val buckets: Map<PayloadRisk.Bucket, Int> = emptyMap(),
    val totalBytes: Long = 0L,
    val http1: Int = 0,
    val http2: Int = 0,
    val outbound: Int = 0,
    val inbound: Int = 0,
    /** Highest risk per app package, highest first. */
    val appRisks: List<Pair<String?, Int>> = emptyList(),
) {
    fun count(b: PayloadRisk.Bucket) = buckets[b] ?: 0

    companion object {
        fun of(rows: List<PayloadLogSummary>): PayloadAnalytics {
            val inputs = rows.map { it to it.riskInput() }
            return PayloadAnalytics(
                total = rows.size,
                buckets = inputs.groupingBy { PayloadRisk.bucket(it.second) }.eachCount(),
                totalBytes = rows.sumOf { it.sizeBytes.toLong() },
                http1 = rows.count { it.protocol == "HTTP1" },
                http2 = rows.count { it.protocol == "HTTP2" },
                outbound = rows.count { it.direction == "OUTBOUND" },
                inbound = rows.count { it.direction == "INBOUND" },
                appRisks = inputs.groupBy { it.first.ownerPackage }
                    .map { (pkg, list) -> pkg to list.maxOf { PayloadRisk.score(it.second) } }
                    .sortedByDescending { it.second }
                    .take(6),
            )
        }
    }
}

data class MitmUiState(
    val isEnabled: Boolean = false,
    val isConsentValid: Boolean = false,
    val pendingEvents: Int = 0,
    val recentLogs: List<PayloadLogEntity> = emptyList(),
    /** True when more entries match than the list shows ([MitmViewModel.LIST_LIMIT]). */
    val listCapped: Boolean = false,
    val filterDirection: String? = null,
    val filterHasBody: Boolean = false,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val mitmStatus: String = "IDLE",
    val mitmStatusMessage: String = "MITM idle",
    val mitmStatusDomain: String? = null,
    val methodFilter: String? = null,
    val showFlaggedOnly: Boolean = false,
    val blockQuic: Boolean = false,
    val analytics: PayloadAnalytics = PayloadAnalytics(),
)

class MitmViewModel(
    private val mitmConfig: MitmConfig,
    private val payloadLogRepository: PayloadLogRepository,
    private val payloadShipper: PayloadShipper
) : ViewModel() {

    companion object {
        /** Newest entries shown; search and filters still run over everything stored. */
        const val LIST_LIMIT = 500
    }

    private val _uiState = MutableStateFlow(MitmUiState(isLoading = true))
    val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

    // One-shot event: fires after the user accepts consent so the Screen can
    // immediately launch the system CA certificate install dialog.
    private val _installCaEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val installCaEvent: SharedFlow<Unit> = _installCaEvent.asSharedFlow()

    private data class ListQuery(val query: String, val method: String?, val direction: String?, val hasBody: Boolean, val flagged: Boolean)

    init {
        observeSettings()
        observeLogs()
        observeAnalytics()
        pollStatus()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            mitmConfig.isEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            MitmEngine.statusFlow.collect { status ->
                _uiState.update {
                    it.copy(
                        mitmStatus = status.state,
                        mitmStatusMessage = status.message,
                        mitmStatusDomain = status.domain
                    )
                }
            }
        }
        viewModelScope.launch {
            mitmConfig.blockQuicWhenMitmFlow.collect { block ->
                _uiState.update { it.copy(blockQuic = block) }
            }
        }
        _uiState.update { it.copy(blockQuic = mitmConfig.blockQuicWhenMitm) }
    }

    /** Consent expires and the shipper drains while the screen is open: re-read them. */
    private fun pollStatus() {
        viewModelScope.launch {
            while (true) {
                _uiState.update {
                    it.copy(isConsentValid = mitmConfig.isConsentValid(), pendingEvents = payloadShipper.pendingCount())
                }
                delay(5_000)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeLogs() {
        viewModelScope.launch {
            _uiState
                .map { ListQuery(it.searchQuery.trim(), it.methodFilter?.uppercase(), it.filterDirection, it.filterHasBody, it.showFlaggedOnly) }
                .distinctUntilChanged()
                .flatMapLatest { q ->
                    // One more than shown, to know whether the list is capped.
                    payloadLogRepository.filtered(q.query, q.method, q.direction, q.hasBody, LIST_LIMIT + 1)
                        .map { rows ->
                            val capped = rows.size > LIST_LIMIT
                            val shown = rows.take(LIST_LIMIT)
                                .filter { !q.flagged || PayloadRisk.isFlagged(it.riskInput()) }
                            shown to capped
                        }
                }
                .collect { (rows, capped) ->
                    _uiState.update { it.copy(recentLogs = rows, listCapped = capped, isLoading = false) }
                }
        }
    }

    private fun observeAnalytics() {
        viewModelScope.launch {
            payloadLogRepository.summaries()
                .map { PayloadAnalytics.of(it) }
                .collect { a -> _uiState.update { it.copy(analytics = a) } }
        }
    }

    fun enableMitm() {
        viewModelScope.launch {
            mitmConfig.setEnabled(true)
        }
    }

    fun disableMitm() {
        viewModelScope.launch {
            mitmConfig.setEnabled(false)
        }
    }

    fun recordConsent() {
        viewModelScope.launch {
            mitmConfig.recordConsent()
            _uiState.update { it.copy(isConsentValid = true) }
            enableMitm()
            // Trigger the CA certificate install dialog in the UI layer.
            _installCaEvent.tryEmit(Unit)
        }
    }

    fun shipNow() {
        payloadShipper.shipNow()
        viewModelScope.launch {
            _uiState.update { it.copy(pendingEvents = payloadShipper.pendingCount()) }
        }
    }

    fun setFilterDirection(direction: String?) = _uiState.update { it.copy(filterDirection = direction) }

    fun setFilterHasBody(hasBody: Boolean) = _uiState.update { it.copy(filterHasBody = hasBody) }

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun setMethodFilter(method: String?) = _uiState.update { it.copy(methodFilter = method) }

    fun setShowFlaggedOnly(flagged: Boolean) = _uiState.update { it.copy(showFlaggedOnly = flagged) }

    fun setBlockQuic(block: Boolean) {
        mitmConfig.setBlockQuicWhenMitm(block)
    }

    /** Lists are live Room flows; nothing to reload by hand. */
    fun refresh() = Unit
}
