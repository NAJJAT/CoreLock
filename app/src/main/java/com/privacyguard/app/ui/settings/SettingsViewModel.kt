package com.privacyguard.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecuritySettingsState(
    val blockCleartext: Boolean = false,
    val blockWeakTls: Boolean = false,
    val killSwitch: Boolean = false,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val settingsPreferences = SettingsPreferences.getInstance(app)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())

    private val _securityState = MutableStateFlow(SecuritySettingsState())
    val securityState: StateFlow<SecuritySettingsState> = _securityState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    fun toggleBlockCleartext(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = RULE_ID_BLOCK_CLEARTEXT,
                        label = "Block all HTTP (cleartext)",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchEncryption = EncryptionStatus.CLEARTEXT,
                    )
                )
            } else {
                rulesRepo.deleteRule(RULE_ID_BLOCK_CLEARTEXT)
            }
            refresh()
        }
    }

    fun toggleBlockWeakTls(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepo.upsertRule(
                    FilterRule(
                        id = RULE_ID_BLOCK_WEAK_TLS,
                        label = "Block deprecated TLS versions",
                        action = FilterRule.Action.DENY,
                        source = FilterRule.Source.USER,
                        priority = FilterRule.HIGH_PRIORITY,
                        matchEncryption = EncryptionStatus.WEAK_TLS,
                    )
                )
            } else {
                rulesRepo.deleteRule(RULE_ID_BLOCK_WEAK_TLS)
            }
            refresh()
        }
    }

    fun toggleKillSwitch(enabled: Boolean) {
        settingsPreferences.setKillSwitchEnabled(enabled)
        if (enabled) KillSwitch.enable() else KillSwitch.disable()
        _securityState.value = _securityState.value.copy(killSwitch = enabled)
    }

    fun clearConnectionData() {
        viewModelScope.launch {
            db.connectionDao().deleteAll()
            db.connectionProfileDao().deleteAll()
            db.dnsAnomalyDao().deleteAll()
        }
    }

    private suspend fun refresh() {
        val rules = db.rulesDao().getAllRules()
        _securityState.value = SecuritySettingsState(
            blockCleartext = rules.any {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.matchEncryption == EncryptionStatus.CLEARTEXT.name
            },
            blockWeakTls = rules.any {
                it.enabled &&
                    it.action == FilterRule.Action.DENY.name &&
                    it.matchEncryption == EncryptionStatus.WEAK_TLS.name
            },
            killSwitch = settingsPreferences.killSwitchEnabled.value,
        )
    }

    companion object {
        private const val RULE_ID_BLOCK_CLEARTEXT = "global:block:cleartext"
        private const val RULE_ID_BLOCK_WEAK_TLS = "global:block:weak_tls"
    }
}
