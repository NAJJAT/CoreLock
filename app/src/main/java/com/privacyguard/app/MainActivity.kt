package com.privacyguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.privacyguard.app.ui.theme.CoreLockTheme
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.home.HomePreferences
import com.privacyguard.app.vpn.VpnManager
import com.privacyguard.app.workers.WeeklyReportWorker
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.ui.AppNavHost
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        if (settings.weeklyReport.value) {
            WeeklyReportWorker.scheduleWeekly(this)
        }
        // Before onboarding can complete: the first 1.1 start decides update vs fresh install.
        HomePreferences.getInstance(this).decideWhatsNew(settings.onboardingCompleted.value)
        // MainActivity is exported (launcher), so any app can set this extra. Only the
        // routes our own notifications use are honoured: an arbitrary route could open
        // internal screens directly, and an unknown one crashed navigate().
        val initialRoute = intent.getStringExtra(NotificationHelper.EXTRA_NAV_ROUTE)
            ?.takeIf { it in NOTIFICATION_ROUTES }
        setContent {
            // D4: the user's choice; the default while none was made is in SettingsLogic.
            val appearance by settings.appearance.collectAsStateWithLifecycle()
            CoreLockTheme(com.privacyguard.app.ui.settings.SettingsLogic.appearanceOf(appearance)) {
                // Dark icons on a light theme: the window theme is dark, so without this the
                // status-bar clock and battery are white on white in Light.
                val lightBars = !CoreLockTheme.colors.isDark
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
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

    private companion object {
        /** Destinations NotificationHelper deep-links to (see EXTRA_NAV_ROUTE). */
        val NOTIFICATION_ROUTES = setOf("alerts", "crypto", "connections", "apps")
    }
}
