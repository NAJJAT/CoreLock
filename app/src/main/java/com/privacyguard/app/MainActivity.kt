package com.privacyguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.theme.PrivacyGuardTheme
import com.privacyguard.app.vpn.KillSwitch
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.workers.WeeklyReportWorker
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.ui.AppNavHost

class MainActivity : com.privacyguard.BaseActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) VpnManager.startVpn(this)
    }

    // Android 13+ shows no notification (VPN-drop and threat alerts, weekly report)
    // until the user allows it; ask when protection is first turned on, then
    // continue to the VPN prompt whatever they answer.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { prepareAndStartVpn() }

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
        val initialRoute = intent.getStringExtra(NotificationHelper.EXTRA_NAV_ROUTE)
        setContent {
            PrivacyGuardTheme {
                AppNavHost(
                    onRequestVpn = { requestVpnPermission() },
                    initialRoute = initialRoute,
                )
            }
        }
    }

    private fun requestVpnPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareAndStartVpn()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else VpnManager.startVpn(this)
    }
}
