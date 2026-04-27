package com.privacyguard.app.ui.security

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.TlsAlertEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecurityAnalysisUiState(
    val ja3Threats: List<TlsAlertEntity> = emptyList(),
    val ctAlerts: List<TlsAlertEntity> = emptyList(),
    val cipherAlerts: List<TlsAlertEntity> = emptyList(),
    val ja3ThreatCount: Int = 0,
    val weakCipherCount: Int = 0,
    val ctNewCertCount: Int = 0,
    val totalScanned: Int = 0,
)

class SecurityAnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(SecurityAnalysisUiState())
    val uiState: StateFlow<SecurityAnalysisUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            db.tlsAlertDao().recentFlow(200).collect { all ->
                _uiState.value = SecurityAnalysisUiState(
                    ja3Threats      = all.filter { it.alertType == "JA3_THREAT"  }.take(20),
                    ctAlerts        = all.filter { it.alertType == "CT_NEW_CERT" }.take(10),
                    cipherAlerts    = all.filter { it.alertType == "WEAK_CIPHER" }.take(20),
                    ja3ThreatCount  = all.count  { it.alertType == "JA3_THREAT"  },
                    weakCipherCount = all.count  { it.alertType == "WEAK_CIPHER" },
                    ctNewCertCount  = all.count  { it.alertType == "CT_NEW_CERT" },
                    totalScanned    = all.size,
                )
            }
        }
    }
}
