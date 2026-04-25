package com.privacyguard.app.ui.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.apk.ApkScanner
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.TopBlockedDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DomainRow(
    val domain: String,
    val count: Int,
    val trackerName: String?,
    val company: String,
)

data class AppDetailState(
    val packageName: String = "",
    val appName: String = "",
    val domains: List<DomainRow> = emptyList(),
    val detectedSdks: List<TrackerEntry> = emptyList(),
    val isScanning: Boolean = false,
    val isLoadingDomains: Boolean = true,
)

class AppDetailViewModel(
    app: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    private val packageName: String = savedState["packageName"] ?: ""
    private val appName: String     = savedState["appName"]     ?: packageName

    private val db by lazy { AppDatabase.getInstance(app) }

    private val _state = MutableStateFlow(AppDetailState(packageName = packageName, appName = appName))
    val state: StateFlow<AppDetailState> = _state.asStateFlow()

    init {
        loadDomains()
        scanApk()
    }

    private fun loadDomains() {
        viewModelScope.launch {
            val rows: List<TopBlockedDomain> = runCatching {
                db.connectionDao().getDomainsForPackage(packageName)
            }.getOrElse { emptyList() }

            val domainRows = rows.map { row ->
                val tracker = TrackerDatabase.lookupByDomain(row.domain)
                DomainRow(
                    domain      = row.domain,
                    count       = row.count,
                    trackerName = tracker?.name,
                    company     = tracker?.company ?: TrackerDatabase.companyForDomain(row.domain),
                )
            }
            _state.value = _state.value.copy(domains = domainRows, isLoadingDomains = false)
        }
    }

    private fun scanApk() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true)
            val found = ApkScanner.scanPackage(getApplication(), packageName)
            _state.value = _state.value.copy(detectedSdks = found, isScanning = false)
        }
    }
}
