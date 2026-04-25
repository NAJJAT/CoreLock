package com.privacyguard.app

import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.theme.PrivacyGuardTheme
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.workers.WeeklyReportWorker
import com.privacyguard.ui.AppNavHost

class MainActivity : com.privacyguard.BaseActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) VpnManager.startVpn(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsPreferences.getInstance(this)
        if (settings.killSwitchEnabled.value) {
            KillSwitch.enable()
        } else {
            KillSwitch.disable()
        }
        if (settings.weeklyReport.value) {
            WeeklyReportWorker.scheduleWeekly(this)
        }
        setContent {
            PrivacyGuardTheme {
                AppNavHost(
                    onRequestVpn = { requestVpnPermission() },
                )
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else VpnManager.startVpn(this)
    }
}
