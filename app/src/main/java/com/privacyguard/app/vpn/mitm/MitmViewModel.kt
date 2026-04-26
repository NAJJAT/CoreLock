package com.privacyguard.app.vpn.mitm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase // FIXED
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.vpn.mitm.MitmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ADDED: UI state for the vpn/mitm MitmScreen
data class MitmUiState(
    val isEnabled: Boolean = false,
    val isConsentValid: Boolean = false,
    val pendingEvents: Int = 0,
    val filterDirection: String? = null,
    val filterHasBody: Boolean = false,
    val searchQuery: String = "",
    val recentLogs: List<PayloadLogEntity> = emptyList(),
)

// ADDED: ViewModel for the enterprise TLS-interception payload screen
class MitmViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val config = MitmConfig(app)

    private val _uiState = MutableStateFlow(MitmUiState())
    val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

    // All logs kept in-memory so filters can be applied without re-querying
    private var allLogs: List<PayloadLogEntity> = emptyList()

    init {
        _uiState.value = _uiState.value.copy(
            isEnabled = config.isEnabled,
            isConsentValid = config.isConsentValid(),
        )
        viewModelScope.launch {
            db.payloadLogDao().recentLogs(200).collectLatest { logs ->
                allLogs = logs
                applyFiltersAndEmit()
            }
        }
    }

    fun enableMitm() {
        config.setEnabled(true)
        _uiState.value = _uiState.value.copy(isEnabled = true)
    }

    fun disableMitm() {
        config.setEnabled(false)
        _uiState.value = _uiState.value.copy(isEnabled = false)
    }

    fun recordConsent() {
        config.recordConsent()
        _uiState.value = _uiState.value.copy(isConsentValid = true)
    }

    fun shipNow() {
        // Shipping is handled asynchronously by PayloadShipper in TcpForwarder
    }

    fun setFilterDirection(direction: String?) {
        _uiState.value = _uiState.value.copy(filterDirection = direction)
        applyFiltersAndEmit()
    }

    fun setFilterHasBody(hasBody: Boolean) {
        _uiState.value = _uiState.value.copy(filterHasBody = hasBody)
        applyFiltersAndEmit()
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFiltersAndEmit()
    }

    private fun applyFiltersAndEmit() {
        val state = _uiState.value
        val filtered = allLogs.filter { log ->
            val matchesDirection = state.filterDirection == null ||
                    log.direction.equals(state.filterDirection, ignoreCase = true)
            val matchesBody = !state.filterHasBody || !log.body.isNullOrEmpty()
            val query = state.searchQuery.trim().lowercase()
            val matchesQuery = query.isBlank() || listOf(
                log.sniHostname, log.ownerPackage, log.urlPath, log.destinationIp
            ).filterNotNull().any { it.lowercase().contains(query) }
            matchesDirection && matchesBody && matchesQuery
        }
        _uiState.value = state.copy(
            recentLogs = filtered,
            pendingEvents = allLogs.size,
        )
    }
}
