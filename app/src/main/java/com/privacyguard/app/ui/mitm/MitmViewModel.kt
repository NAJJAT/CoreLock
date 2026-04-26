package com.privacyguard.ui.mitm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.data.db.PayloadLogEntity
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.mitm.MitmEngine
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.PayloadShipper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MitmUiState(
    val isEnabled: Boolean = false,
    val isConsentValid: Boolean = false,
    val pendingEvents: Int = 0,
    val recentLogs: List<PayloadLogEntity> = emptyList(),
    val filterDirection: String? = null,
    val filterHasBody: Boolean = false,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val mitmStatus: String = "IDLE",
    val mitmStatusMessage: String = "MITM idle",
    val mitmStatusDomain: String? = null,
    val methodFilter: String? = null,
    val showFlaggedOnly: Boolean = false,
)

class MitmViewModel(
    private val mitmConfig: MitmConfig,
    private val payloadLogRepository: PayloadLogRepository,
    private val payloadShipper: PayloadShipper
) : ViewModel() {

    private val _uiState = MutableStateFlow(MitmUiState())
    val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        observeLogs()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            mitmConfig.isEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isConsentValid = mitmConfig.isConsentValid()) }
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
    }

    private fun observeLogs() {
        viewModelScope.launch {
            payloadLogRepository.recentLogs(200).collect { logs ->
                val filtered = applyFilters(logs)
                _uiState.update { it.copy(recentLogs = filtered, isLoading = false) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(pendingEvents = payloadShipper.pendingCount()) }
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
        }
    }

    fun shipNow() {
        payloadShipper.shipNow()
        viewModelScope.launch {
            _uiState.update { it.copy(pendingEvents = payloadShipper.pendingCount()) }
        }
    }

    fun setFilterDirection(direction: String?) {
        _uiState.update { it.copy(filterDirection = direction) }
        observeLogs()
    }

    fun setFilterHasBody(hasBody: Boolean) {
        _uiState.update { it.copy(filterHasBody = hasBody) }
        observeLogs()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        observeLogs()
    }

    fun setMethodFilter(method: String?) {
        _uiState.update { it.copy(methodFilter = method) }
        observeLogs()
    }

    fun setShowFlaggedOnly(flagged: Boolean) {
        _uiState.update { it.copy(showFlaggedOnly = flagged) }
        observeLogs()
    }

    private fun applyFilters(logs: List<PayloadLogEntity>): List<PayloadLogEntity> {
        var filtered = logs

        _uiState.value.filterDirection?.let { direction ->
            filtered = filtered.filter { it.direction == direction }
        }

        if (_uiState.value.filterHasBody) {
            filtered = filtered.filter { !it.body.isNullOrEmpty() }
        }

        _uiState.value.methodFilter?.let { method ->
            filtered = filtered.filter { it.method?.uppercase() == method.uppercase() }
        }

        if (_uiState.value.showFlaggedOnly) {
            filtered = filtered.filter { log ->
                log.piiRedacted ||
                log.headers.lowercase().contains("authorization") ||
                log.headers.lowercase().contains("x-device")
            }
        }

        if (_uiState.value.searchQuery.isNotBlank()) {
            val query = _uiState.value.searchQuery.lowercase()
            filtered = filtered.filter {
                it.sniHostname?.lowercase()?.contains(query) == true ||
                        it.ownerPackage?.lowercase()?.contains(query) == true ||
                        it.urlPath?.lowercase()?.contains(query) == true ||
                        it.body?.lowercase()?.contains(query) == true
            }
        }

        return filtered
    }

    fun refresh() {
        observeLogs()
    }
}
