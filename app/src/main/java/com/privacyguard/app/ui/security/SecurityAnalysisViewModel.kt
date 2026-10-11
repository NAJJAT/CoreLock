package com.privacyguard.app.ui.security

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.TlsAlertEntity
import com.privacyguard.app.data.db.TlsFingerprintEntity
import com.privacyguard.app.vpn.originEnum
import com.privacyguard.core.tls.FingerprintOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** A fingerprint that appeared outside an app's baseline, ready to display. */
data class TlsChangeItem(
    val fingerprint: TlsFingerprintEntity,
    val appLabel: String,
    val trusted: Boolean,
)

data class TlsBaselineSummary(
    val appCount: Int = 0,
    val appsLearning: Int = 0,
    val knownFingerprints: Int = 0,
    val changes: List<TlsChangeItem> = emptyList(),
)

data class SecurityAnalysisUiState(
    val ja3Threats: List<TlsAlertEntity> = emptyList(),
    val ctAlerts: List<TlsAlertEntity> = emptyList(),
    val cipherAlerts: List<TlsAlertEntity> = emptyList(),
    val ja3ThreatCount: Int = 0,
    val weakCipherCount: Int = 0,
    val ctNewCertCount: Int = 0,
    val totalScanned: Int = 0,
    val baseline: TlsBaselineSummary = TlsBaselineSummary(),
)

class SecurityAnalysisViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(SecurityAnalysisUiState())
    val uiState: StateFlow<SecurityAnalysisUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            db.tlsAlertDao().recentFlow(200).collect { all ->
                _uiState.value = _uiState.value.copy(
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
        viewModelScope.launch {
            val dao = db.tlsBaselineDao()
            combine(dao.profilesFlow(), dao.fingerprintsFlow()) { profiles, fps ->
                val now = System.currentTimeMillis()
                val learningMs = TimeUnit.DAYS.toMillis(7)
                TlsBaselineSummary(
                    appCount = profiles.size,
                    appsLearning = profiles.count { now - it.learningStartedAt < learningMs },
                    knownFingerprints = fps.size,
                    changes = fps
                        .filter { it.originEnum() == FingerprintOrigin.ALERTED || it.originEnum() == FingerprintOrigin.USER_TRUSTED }
                        .sortedByDescending { it.firstSeen }
                        .map { TlsChangeItem(it, labelFor(it.packageName), it.originEnum() == FingerprintOrigin.USER_TRUSTED) },
                )
            }.collect { summary -> _uiState.value = _uiState.value.copy(baseline = summary) }
        }
    }

    fun trust(item: TlsChangeItem) {
        viewModelScope.launch {
            val fp = item.fingerprint
            db.tlsBaselineDao().markTrusted(fp.uid, fp.packageName, fp.ja4)
        }
    }

    private fun labelFor(pkg: String): String = runCatching {
        val pm = getApplication<Application>().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), pkg) }
}
